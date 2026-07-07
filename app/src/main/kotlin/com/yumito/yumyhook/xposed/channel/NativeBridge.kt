package com.yumito.yumyhook.xposed.channel

import com.yumito.yumyhook.xposed.channel.systemproperty.SystemPropertyMapper
import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.config.HookFeatureConfig
import com.yumito.yumyhook.xposed.config.HookSpoofValues
import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.policy.FourChannelGate
import com.yumito.yumyhook.xposed.policy.NativeHookPolicy
import com.yumito.yumyhook.xposed.stealth.hide.NativeStealthBridge
import com.yumito.yumyhook.xposed.runtime.ModulePathHolder
import com.yumito.yumyhook.xposed.runtime.TargetContextHolder

import android.content.Context
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * JNI 桥：hook __system_property_get / property_get，对齐 Java getprop / SystemProperties。
 * 须在 handleLoadPackage 尽早安装，避免 ContentProvider 探测早于 Application.onCreate。
 */
object NativeBridge {

    private const val MAX_NATIVE_PROP_LEN = 91
    private const val LOG = XposedConstants.NATIVE_PROP_TAG

    private fun log(msg: String) = XposedBridge.log("$LOG: $msg")

    @Volatile
    private var hooksInstalled = false

    fun isHooksInstalled(): Boolean = hooksInstalled

    /** 宿主 crash/shadowhook 库加载后装 Native（仅 libyumyhook_native + 宿主 shadowhook）。 */
    fun installAfterHostLibrary(dataDir: String, packageName: String, classLoader: ClassLoader): Boolean {
        if (hooksInstalled) return true
        if (!FourChannelGate.isActive(packageName)) return false
        if (!NativeHookPolicy.shouldInstallNative(packageName, FourChannelGate.currentFeatures())) return false
        if (!ensureNativeLoaded(dataDir, packageName, classLoader, reuseHostShadowhook = true)) return false
        installHooksIfNeeded(packageName, "host-lib", libcOnly = true)
        if (!hooksInstalled) return false
        syncFromGate(HookConfig.valuesForHook(), packageName)
        retryDeferredHooks(packageName)
        return true
    }

    private var lastDataDir: String? = null
    private var lastClassLoader: ClassLoader? = null

    /** handleLoadPackage 即装 native；成功返回 true。 */
    fun installEarly(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        val pkg = lpparam.packageName
        if (!FourChannelGate.isActive(pkg)) {
            logGate("installEarly-skip", pkg)
            return false
        }
        if (!NativeHookPolicy.shouldInstallNative(pkg, FourChannelGate.currentFeatures())) {
            log("early skip policy pkg=$pkg")
            return false
        }
        val dataDir = lpparam.appInfo.dataDir ?: return false
        lastDataDir = dataDir
        lastClassLoader = lpparam.classLoader
        if (!ensureNativeLoaded(dataDir, pkg, lpparam.classLoader, callerClass = null)) return false
        installHooksIfNeeded(pkg, "early", dataDir = dataDir, classLoader = lpparam.classLoader)
        if (!hooksInstalled) return false
        syncFromGate(HookConfig.valuesForHook(), pkg)
        return true
    }

    /** LOAD_PACKAGE 早装失败时，用宿主 Application 作 nativeLoad caller 再试。 */
    fun retryInstallWithCaller(lpparam: XC_LoadPackage.LoadPackageParam, caller: Class<*>): Boolean {
        if (hooksInstalled) return true
        val pkg = lpparam.packageName
        if (!FourChannelGate.isActive(pkg)) return false
        if (!NativeHookPolicy.shouldInstallNative(pkg, FourChannelGate.currentFeatures())) return false
        val dataDir = lpparam.appInfo.dataDir ?: return false
        lastDataDir = dataDir
        lastClassLoader = lpparam.classLoader
        if (!ensureNativeLoaded(dataDir, pkg, lpparam.classLoader, callerClass = caller)) return false
        installHooksIfNeeded(pkg, "caller-retry", dataDir = dataDir, classLoader = lpparam.classLoader)
        if (!hooksInstalled) return false
        syncFromGate(HookConfig.valuesForHook(), pkg)
        return true
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam, hostContext: Context) {
        val pkg = lpparam.packageName
        if (!FourChannelGate.isActive(pkg)) {
            logGate("install-skip", pkg)
            deactivateNative(pkg)
            return
        }
        if (!NativeHookPolicy.shouldInstallNative(pkg, FourChannelGate.currentFeatures())) {
            log("install skip policy pkg=$pkg")
            deactivateNative(pkg)
            return
        }
        val dataDir = hostContext.applicationInfo.dataDir
        lastDataDir = dataDir
        lastClassLoader = hostContext.classLoader
        if (!ensureNativeLoaded(dataDir, pkg, hostContext.classLoader, callerClass = hostContext.javaClass)) return
        installHooksIfNeeded(pkg, "context", dataDir = dataDir, classLoader = hostContext.classLoader)
        syncFromGate(HookConfig.valuesForHook(), pkg)
    }

    fun syncProperties(values: HookSpoofValues, hostContext: Context?) {
        val pkg = hostContext?.packageName ?: TargetContextHolder.packageName
        syncFromGate(values, pkg)
    }

    /** 四通道关：清属性表 + 关 native 伪装；开：同步属性表。 */
    fun syncFromGate(values: HookSpoofValues, packageName: String?) {
        val pkg = packageName.orEmpty()
        if (!FourChannelGate.isActive(pkg.ifBlank { null })) {
            deactivateNative(pkg)
            return
        }
        if (!NativeHookPolicy.shouldInstallNative(pkg, FourChannelGate.currentFeatures())) {
            deactivateNative(pkg)
            return
        }
        if (!hooksInstalled) return
        try {
            nativeSetSpoofActive(true)
            val props = SystemPropertyMapper.allChannelProperties(
                values,
                HookFeatureConfig.current().hideRoot,
            ).mapValues { (_, v) -> if (v.length > MAX_NATIVE_PROP_LEN) "" else v }
            nativeUpdateProperties(props.keys.toTypedArray(), props.values.toTypedArray())
            log("props synced count=${props.size} pkg=$pkg")
        } catch (e: Throwable) {
            log("sync failed: ${e.message}")
        }
    }

    private fun deactivateNative(packageName: String) {
        if (!hooksInstalled) return
        try {
            nativeSetSpoofActive(false)
            nativeUpdateProperties(emptyArray(), emptyArray())
            log("spoof off pkg=$packageName")
        } catch (_: Throwable) {
        }
    }

    private fun logGate(stage: String, packageName: String) {
        val f = FourChannelGate.currentFeatures()
        XposedBridge.log(
            "${XposedConstants.TAG}: four-channel gate $stage pkg=$packageName " +
                "master=${f.spoofBuildProperties} disabled=${f.disabledScopedFourChannel}",
        )
    }

    private fun ensureNativeLoaded(
        appDataDir: String,
        packageName: String,
        classLoader: ClassLoader? = null,
        reuseHostShadowhook: Boolean = false,
        callerClass: Class<*>? = null,
    ): Boolean {
        val apk = ModulePathHolder.moduleApkPath
        if (apk.isBlank()) {
            log("skip empty module apk path")
            return false
        }
        return NativeLibLoader.ensureLoaded(
            apk,
            appDataDir,
            packageName,
            classLoader,
            reuseHostShadowhook,
            callerClass,
        )
    }

    private fun installHooksIfNeeded(
        packageName: String,
        stage: String,
        libcOnly: Boolean = false,
        dataDir: String? = lastDataDir,
        classLoader: ClassLoader? = lastClassLoader,
    ) {
        if (hooksInstalled) {
            NativeStealthBridge.retryAfterNativeEngine(packageName, dataDir, classLoader)
            retryDeferredHooks(packageName)
            return
        }
        if (!NativeHookPolicy.shouldInstallNative(packageName, FourChannelGate.currentFeatures())) {
            log("property hook $stage=skip pkg=$packageName")
            return
        }
        try {
            val ok = nativeInstallPropertyHook(libcOnly, dataDir)
            hooksInstalled = ok
            val probe = if (ok) nativeProbeProperty("ro.product.model") else "n/a"
            val libcProbe = if (ok) nativeProbeLibcutilsProperty("ro.product.model") else "n/a"
            log(
                "rev=${XposedConstants.HOOK_REV} property hook $stage=$ok pkg=$packageName " +
                    "probe_model=$probe libcutils_model=$libcProbe stats=${nativeHookStats()}",
            )
            if (ok) {
                NativeStealthBridge.retryAfterNativeEngine(packageName, dataDir, classLoader)
            }
        } catch (e: Throwable) {
            log("nativeInstallPropertyHook failed: ${e.message}")
        }
    }

    fun retryDeferredHooks(packageName: String) {
        if (!FourChannelGate.isActive(packageName)) return
        if (!hooksInstalled) {
            installHooksIfNeeded(packageName, "deferred")
            return
        }
        try {
            val ok = nativeRetryDeferredHooks()
            val probe = nativeProbeProperty("ro.product.model")
            val libcProbe = nativeProbeLibcutilsProperty("ro.product.model")
            log("deferred retry=$ok pkg=$packageName probe_model=$probe libcutils_model=$libcProbe")
        } catch (e: Throwable) {
            log("nativeRetryDeferredHooks failed: ${e.message}")
        }
    }

    @JvmStatic
    private external fun nativeInstallPropertyHook(libcOnly: Boolean, cacheDir: String?): Boolean

    @JvmStatic
    private external fun nativeRetryDeferredHooks(): Boolean

    @JvmStatic
    private external fun nativeSetSpoofActive(active: Boolean)

    @JvmStatic
    private external fun nativeProbeProperty(name: String): String

    @JvmStatic
    private external fun nativeProbeLibcutilsProperty(name: String): String

    @JvmStatic
    private external fun nativeHookStats(): String

    @JvmStatic
    private external fun nativeUpdateProperties(keys: Array<String>, values: Array<String>)
}
