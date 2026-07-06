package com.yumito.yumyhook.xposed

import android.content.Context
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * JNI 桥：hook __system_property_get / property_get，对齐 Java getprop / SystemProperties。
 * 须在 handleLoadPackage 尽早安装，避免 ContentProvider 探测早于 Application.onCreate。
 */
object NativeBridge {

    private const val MAX_NATIVE_PROP_LEN = 91

    @Volatile
    private var hooksInstalled = false

    /** handleLoadPackage 即装 native（不等待 Application.onCreate）。 */
    fun installEarly(lpparam: XC_LoadPackage.LoadPackageParam) {
        val features = HookFeatureConfig.current()
        if (!NativeHookPolicy.shouldInstallNative(lpparam.packageName, features)) return
        val dataDir = lpparam.appInfo.dataDir ?: return
        if (!ensureNativeLoaded(dataDir)) return
        installHooksIfNeeded(lpparam.packageName, "early")
        pushPropertyMap(HookConfig.valuesForHook(), lpparam.packageName)
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam, hostContext: Context) {
        val features = HookFeatureConfig.refreshIfStale()
        if (!NativeHookPolicy.shouldInstallNative(lpparam.packageName, features)) return
        val dataDir = hostContext.applicationInfo.dataDir
        if (!ensureNativeLoaded(dataDir)) return
        installHooksIfNeeded(lpparam.packageName, "context")
        pushPropertyMap(HookConfig.valuesForHook(), lpparam.packageName)
    }

    fun syncProperties(values: HookSpoofValues, hostContext: Context? = TargetContextHolder.appContext) {
        val pkg = hostContext?.packageName ?: TargetContextHolder.packageName
        pushPropertyMap(values, pkg)
    }

    private fun ensureNativeLoaded(appDataDir: String): Boolean {
        val apk = ModulePathHolder.moduleApkPath
        if (apk.isBlank()) {
            XposedBridge.log("${XposedConstants.TAG}: native skip empty module apk path")
            return false
        }
        return NativeLibLoader.ensureLoaded(apk, appDataDir)
    }

    private fun installHooksIfNeeded(packageName: String, stage: String) {
        if (hooksInstalled) return
        try {
            val ok = nativeInstallPropertyHook()
            hooksInstalled = ok
            val probe = if (ok) nativeProbeProperty("ro.product.model") else "n/a"
            XposedBridge.log(
                "${XposedConstants.TAG}: native property hook $stage=$ok pkg=$packageName probe_model=$probe stats=${nativeHookStats()}",
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: nativeInstallPropertyHook failed: ${e.message}")
        }
    }

    /** Application.attach 兜底：libcutils 可能此阶段才加载。 */
    fun retryDeferredHooks(packageName: String) {
        if (!hooksInstalled) {
            installHooksIfNeeded(packageName, "deferred")
            return
        }
        try {
            val ok = nativeRetryDeferredHooks()
            val probe = nativeProbeProperty("ro.product.model")
            XposedBridge.log(
                "${XposedConstants.TAG}: native deferred retry=$ok pkg=$packageName probe_model=$probe",
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: nativeRetryDeferredHooks failed: ${e.message}")
        }
    }

    private fun pushPropertyMap(values: HookSpoofValues, packageName: String?) {
        val features = HookFeatureConfig.refreshIfStale()
        if (!NativeHookPolicy.shouldInstallNative(packageName, features)) return
        if (!hooksInstalled) return
        if (!HookConfig.isEnabledForHook()) {
            try {
                nativeUpdateProperties(emptyArray(), emptyArray())
            } catch (_: Throwable) {
            }
            return
        }
        try {
            val props = SystemPropertyMapper.allProperties(values)
                .mapValues { (_, v) -> if (v.length > MAX_NATIVE_PROP_LEN) "" else v }
            nativeUpdateProperties(props.keys.toTypedArray(), props.values.toTypedArray())
            XposedBridge.log("${XposedConstants.TAG}: native props synced count=${props.size} pkg=$packageName")
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: NativeBridge.sync failed: ${e.message}")
        }
    }

    @JvmStatic
    private external fun nativeInstallPropertyHook(): Boolean

    @JvmStatic
    private external fun nativeRetryDeferredHooks(): Boolean

    @JvmStatic
    private external fun nativeProbeProperty(name: String): String

    @JvmStatic
    private external fun nativeHookStats(): String

    @JvmStatic
    private external fun nativeUpdateProperties(keys: Array<String>, values: Array<String>)
}
