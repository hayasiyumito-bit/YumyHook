package com.yumito.yumyhook.xposed.hook.stealth

import android.app.Application
import com.yumito.yumyhook.xposed.HookConfig
import com.yumito.yumyhook.xposed.HookFeatureConfig
import com.yumito.yumyhook.xposed.NativeBridge
import com.yumito.yumyhook.xposed.NativeHookPolicy
import com.yumito.yumyhook.xposed.TargetContextHolder
import com.yumito.yumyhook.xposed.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** Application.attach 兜底重装 native + 刷新属性表（早期 install 失败时）。 */
object DeferredStealthInstaller {

    @Volatile
    private var nativeReady = false

    fun schedule(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                android.content.Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as Application
                        TargetContextHolder.bind(app)
                        ensureNativeReady(lpparam, app)
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: DeferredStealth schedule skip: ${e.message}")
        }
    }

    private fun ensureNativeReady(lpparam: XC_LoadPackage.LoadPackageParam, app: Application) {
        val features = HookFeatureConfig.refreshIfStale()
        if (!NativeHookPolicy.shouldInstallNative(lpparam.packageName, features)) return
        if (nativeReady) {
            NativeBridge.syncProperties(HookConfig.valuesForHook(), app)
            return
        }
        synchronized(this) {
            if (nativeReady) {
                NativeBridge.syncProperties(HookConfig.valuesForHook(), app)
                return
            }
            try {
                NativeBridge.install(lpparam, app)
                NativeBridge.retryDeferredHooks(lpparam.packageName)
                nativeReady = true
                XposedBridge.log("${XposedConstants.TAG}: native bridge ready pkg=${lpparam.packageName}")
            } catch (e: Throwable) {
                XposedBridge.log("${XposedConstants.TAG}: native bridge fallback failed: ${e.message}")
            }
        }
    }
}
