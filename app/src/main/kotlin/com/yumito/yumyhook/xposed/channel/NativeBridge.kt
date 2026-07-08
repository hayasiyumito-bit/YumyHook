package com.yumito.yumyhook.xposed.channel

import android.app.Application
import android.content.Context
import com.yumito.yumyhook.xposed.channel.strategy.profiles.ShadowhookKnownApps
import com.yumito.yumyhook.xposed.channel.systemproperty.SystemPropertyMapper
import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.config.HookSpoofValues
import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.policy.FourChannelGate
import com.yumito.yumyhook.xposed.policy.NativeHookPolicy
import com.yumito.yumyhook.xposed.runtime.ModulePathHolder
import com.yumito.yumyhook.xposed.runtime.TargetContextHolder
import com.yumito.yumyhook.xposed.stealth.hide.NativeStealthBridge
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** JNI 桥：hook __system_property_get / property_get 及其生命周期管理。 */
object NativeBridge {

    @Volatile private var hooksInstalled = false
    @Volatile private var guardInstalled = false
    private var lastDataDir: String? = null
    private var lastCL: ClassLoader? = null

    fun isHooksInstalled(): Boolean = hooksInstalled

    fun installEarly(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        val pkg = lpparam.packageName
        if (!FourChannelGate.isActive(pkg) || !NativeHookPolicy.shouldInstallNative(pkg, HookConfig.features())) return false
        lastDataDir = lpparam.appInfo.dataDir; lastCL = lpparam.classLoader
        if (!NativeLibLoader.ensureLoaded(ModulePathHolder.moduleApkPath, lastDataDir!!, pkg, lastCL)) return false
        return installHooks(pkg, "early", lastDataDir, lastCL)
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam, app: Application) {
        val pkg = lpparam.packageName
        if (!FourChannelGate.isActive(pkg) || !NativeHookPolicy.shouldInstallNative(pkg, HookConfig.features())) { deactivate(pkg); return }
        lastDataDir = app.applicationInfo.dataDir; lastCL = app.classLoader
        if (NativeLibLoader.ensureLoaded(ModulePathHolder.moduleApkPath, lastDataDir!!, pkg, lastCL, callerClass = app.javaClass)) {
            installHooks(pkg, "app", lastDataDir, lastCL)
        }
    }

    fun retryInstallWithCaller(lpparam: XC_LoadPackage.LoadPackageParam, caller: Class<*>): Boolean {
        if (hooksInstalled) return true
        val pkg = lpparam.packageName; val dataDir = lpparam.appInfo.dataDir ?: return false
        if (!NativeLibLoader.ensureLoaded(ModulePathHolder.moduleApkPath, dataDir, pkg, lpparam.classLoader, callerClass = caller)) return false
        return installHooks(pkg, "retry", dataDir, lpparam.classLoader)
    }

    private fun installHooks(pkg: String, stage: String, dir: String?, cl: ClassLoader?): Boolean {
        if (hooksInstalled) return true
        return try {
            val ok = NativeJniHost.nativeInstallPropertyHook(false, dir)
            hooksInstalled = ok
            if (ok) { NativeLibLoader.markProcStealthActive(); syncFromGate(HookConfig.valuesForHook(), pkg); NativeStealthBridge.retryAfterNativeEngine(pkg, dir, cl) }
            XposedBridge.log("${XposedConstants.NATIVE_PROP_TAG}: hook $stage=$ok pkg=$pkg stats=${NativeJniHost.nativeHookStats()}")
            ok
        } catch (_: Throwable) { false }
    }

    fun syncProperties(values: HookSpoofValues, context: Context?) = syncFromGate(values, context?.packageName ?: TargetContextHolder.packageName)

    fun syncFromGate(values: HookSpoofValues, packageName: String?) {
        val pkg = packageName.orEmpty()
        if (!FourChannelGate.isActive(pkg.ifBlank { null }) || !NativeHookPolicy.shouldInstallNative(pkg, HookConfig.features())) { 
            if (values.buildFields.isNotEmpty()) deactivate(pkg)
            return 
        }
        if (!hooksInstalled) return
        try {
            NativeJniHost.nativeSetSpoofActive(true)
            val props = SystemPropertyMapper.allChannelProperties(values, HookConfig.features().hideRoot).mapValues { if (it.value.length > 91) "" else it.value }
            NativeJniHost.nativeUpdateProperties(props.keys.toTypedArray(), props.values.toTypedArray())
        } catch (_: Throwable) {}
    }

    private fun deactivate(pkg: String) {
        if (!hooksInstalled) return
        try { NativeJniHost.nativeSetSpoofActive(false); NativeJniHost.nativeUpdateProperties(emptyArray(), emptyArray()) } catch (_: Throwable) {}
    }

    fun retryDeferredHooks(pkg: String) { if (hooksInstalled) try { NativeJniHost.nativeRetryDeferredHooks() } catch (_: Throwable) {} }

    fun installLoadWatcher(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (guardInstalled) return
        synchronized(this) {
            if (guardInstalled) return
            val cl = lpparam.classLoader; val pkg = lpparam.packageName
            val hook = object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (hooksInstalled) { NativeStealthBridge.install(pkg, lastDataDir, cl); return }
                    if (NativeLibLoader.isHostNativeReady() || ShadowhookKnownApps.isKnown(pkg)) retryInstallWithCaller(lpparam, Application::class.java)
                }
            }
            try {
                XposedHelpers.findAndHookMethod("android.app.ActivityThread", null, "handleBindApplication", "android.app.ActivityThread\$AppBindData", hook)
                XposedHelpers.findAndHookMethod(Runtime::class.java, "loadLibrary0", Class::class.java, String::class.java, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (hooksInstalled || (p.args[0] as Class<*>).classLoader !== cl) return
                        if (NativeLibLoader.isHostNativeReady() || ShadowhookKnownApps.isKnown(pkg)) retryInstallWithCaller(lpparam, p.args[0] as Class<*>)
                    }
                    override fun afterHookedMethod(p: MethodHookParam) {
                        if (hooksInstalled && (p.args[0] as Class<*>).classLoader === cl) NativeStealthBridge.install(pkg, lastDataDir, cl)
                    }
                })
                guardInstalled = true
            } catch (_: Throwable) {}
        }
    }
}
