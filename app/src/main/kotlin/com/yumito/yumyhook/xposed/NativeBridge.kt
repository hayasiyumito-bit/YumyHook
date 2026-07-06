package com.yumito.yumyhook.xposed

import android.content.Context
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * JNI 桥：在 native 层 hook __system_property_get，对齐 Java getprop / SystemProperties 通道。
 * 失败时静默降级，不影响 Java 层 Hook。
 */
object NativeBridge {

    private const val MAX_NATIVE_PROP_LEN = 91

    @Volatile
    private var hooksInstalled = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam, hostContext: Context) {
        val features = HookFeatureConfig.refreshIfStale()
        if (!NativeHookPolicy.shouldInstallNative(lpparam.packageName, features)) return
        if (!NativeLibLoader.ensureLoaded(ModulePathHolder.moduleApkPath, hostContext)) return
        try {
            if (!hooksInstalled) {
                val ok = nativeInstallPropertyHook()
                hooksInstalled = ok
                XposedBridge.log("${XposedConstants.TAG}: native property hook=$ok")
            }
            syncProperties(HookConfig.valuesForHook(), hostContext)
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: NativeBridge.install failed: ${e.message}")
        }
    }

    fun syncProperties(values: HookSpoofValues, hostContext: Context? = TargetContextHolder.appContext) {
        val features = HookFeatureConfig.refreshIfStale()
        val pkg = hostContext?.packageName ?: TargetContextHolder.packageName
        if (!NativeHookPolicy.shouldInstallNative(pkg, features)) return
        val ctx = hostContext ?: return
        if (!NativeLibLoader.ensureLoaded(ModulePathHolder.moduleApkPath, ctx)) return
        try {
            val props = SystemPropertyMapper.allProperties(values)
                .mapValues { (_, v) -> if (v.length > MAX_NATIVE_PROP_LEN) "" else v }
            val keys = props.keys.toTypedArray()
            val vals = props.values.toTypedArray()
            nativeUpdateProperties(keys, vals)
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: NativeBridge.sync failed: ${e.message}")
        }
    }

    @JvmStatic
    private external fun nativeInstallPropertyHook(): Boolean

    @JvmStatic
    private external fun nativeUpdateProperties(keys: Array<String>, values: Array<String>)
}
