package com.yumito.yumyhook.xposed.channel

import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.policy.FourChannelGate
import com.yumito.yumyhook.xposed.policy.NativeHookPolicy
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 仅在 [NativeInstallMode.LOAD_PACKAGE] 启用：宿主 .so 加载前抢先装 native。
 * 只 hook `Runtime.loadLibrary0(ClassLoader, String)` 且限定宿主 CL，避免干扰 ART/微信 crash 库。
 */
object NativeLoadGuard {

    @Volatile
    private var installed = false

    @Volatile
    private var primed = false

    @Volatile
    private var hostParam: XC_LoadPackage.LoadPackageParam? = null

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            hostParam = lpparam
            installHook(lpparam.classLoader)
            installed = true
        }
    }

    private fun installHook(hostClassLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                Runtime::class.java,
                "loadLibrary0",
                Class::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val loader = param.args.getOrNull(0) as? ClassLoader ?: return
                        if (loader !== hostClassLoader) return
                        primeNativeOnce()
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.NATIVE_PROP_TAG}: loadLibrary0 hook skip: ${e.message}")
        }
    }

    private fun primeNativeOnce() {
        if (NativeBridge.isHooksInstalled()) return
        val lpparam = hostParam ?: return
        synchronized(this) {
            if (NativeBridge.isHooksInstalled()) return
            if (primed) return
        }
        val pkg = lpparam.packageName
        if (!FourChannelGate.isActive(pkg)) return
        if (!NativeHookPolicy.shouldInstallNative(pkg, FourChannelGate.currentFeatures())) return
        val ok = NativeBridge.installEarly(lpparam)
        if (ok) {
            synchronized(this) { primed = true }
        }
    }
}
