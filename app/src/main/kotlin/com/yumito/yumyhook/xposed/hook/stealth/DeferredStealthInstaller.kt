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

/** 仅 native 延到 onCreate；Java 反检测在 handleLoadPackage 即装。 */
object DeferredStealthInstaller {

  @Volatile
  private var nativeInstalled = false

  fun schedule(lpparam: XC_LoadPackage.LoadPackageParam) {
    try {
      XposedHelpers.findAndHookMethod(
        Application::class.java,
        "onCreate",
        object : XC_MethodHook() {
          override fun afterHookedMethod(param: MethodHookParam) {
            val app = param.thisObject as Application
            TargetContextHolder.bind(app)
            installNativeOnce(lpparam, app)
          }
        },
      )
    } catch (e: Throwable) {
      XposedBridge.log("${XposedConstants.TAG}: DeferredStealth schedule skip: ${e.message}")
    }
  }

  private fun installNativeOnce(lpparam: XC_LoadPackage.LoadPackageParam, app: Application) {
    if (nativeInstalled) return
    synchronized(this) {
      if (nativeInstalled) return
      val features = HookFeatureConfig.refreshIfStale()
      if (!NativeHookPolicy.shouldInstallNative(lpparam.packageName, features)) return
      nativeInstalled = true
      try {
        val values = HookConfig.refreshHookCache()
        NativeBridge.install(lpparam, app)
        NativeBridge.syncProperties(values, app)
        XposedBridge.log("${XposedConstants.TAG}: native bridge installed pkg=${lpparam.packageName}")
      } catch (e: Throwable) {
        nativeInstalled = false
        XposedBridge.log("${XposedConstants.TAG}: native bridge failed: ${e.message}")
      }
    }
  }
}
