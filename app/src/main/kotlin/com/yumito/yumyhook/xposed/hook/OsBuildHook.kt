package com.yumito.yumyhook.xposed.hook

import android.app.Application
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.yumito.yumyhook.xposed.SpoofRuntime
import com.yumito.yumyhook.xposed.TargetContextHolder
import com.yumito.yumyhook.xposed.XposedConstants

/**
 * Build 伪装：
 * - attach 绑 Context
 * - handleBindApplication 结束 + onCreate 时 apply（framework 类须 null ClassLoader）
 */
object OsBuildHook {

  private val bootClassLoader: ClassLoader? = null

  fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
    val patcher = Runnable {
      SpoofRuntime.refreshAndApply(TargetContextHolder.appContext, "lifecycle")
    }
    installActivityThreadHook(patcher)
    installApplicationLifecycleHooks(patcher)
  }

  private fun installActivityThreadHook(patcher: Runnable) {
    try {
      XposedHelpers.findAndHookMethod(
        "android.app.ActivityThread",
        bootClassLoader,
        "handleBindApplication",
        "android.app.ActivityThread\$AppBindData",
        object : XC_MethodHook() {
          override fun afterHookedMethod(param: MethodHookParam) {
            patcher.run()
          }
        },
      )
    } catch (e: Throwable) {
      XposedBridge.log("${XposedConstants.TAG}: ActivityThread hook skip: ${e.message}")
    }
  }

  private fun installApplicationLifecycleHooks(patcher: Runnable) {
    try {
      XposedHelpers.findAndHookMethod(
        Application::class.java,
        "attach",
        Context::class.java,
        object : XC_MethodHook() {
          override fun afterHookedMethod(param: MethodHookParam) {
            TargetContextHolder.bind(param.args[0] as Context)
            patcher.run()
          }
        },
      )
      XposedHelpers.findAndHookMethod(
        Application::class.java,
        "onCreate",
        object : XC_MethodHook() {
          override fun afterHookedMethod(param: MethodHookParam) {
            TargetContextHolder.bind(param.thisObject as Application)
            patcher.run()
          }
        },
      )
    } catch (e: Throwable) {
      XposedBridge.log("${XposedConstants.TAG}: Application lifecycle hook skip: ${e.message}")
    }
  }
}
