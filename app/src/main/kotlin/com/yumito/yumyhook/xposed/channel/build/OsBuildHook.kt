package com.yumito.yumyhook.xposed.channel.build

import android.app.Application
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.yumito.yumyhook.xposed.channel.strategy.InstallPhase
import com.yumito.yumyhook.xposed.channel.strategy.profiles.BuiltinAppProfiles
import com.yumito.yumyhook.xposed.runtime.SpoofRuntime
import com.yumito.yumyhook.xposed.runtime.TargetContextHolder
import com.yumito.yumyhook.xposed.config.XposedConstants

/**
 * Build 伪装桩安装。
 */
object OsBuildHook {

  private val bootClassLoader: ClassLoader? = null

  fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
    val pkg = lpparam.packageName
    val patchAtBind = Runnable {
      applyIfAllowed(pkg, InstallPhase.APPLICATION_ATTACH, "bindApplication")
    }
    val patchAtAttach = Runnable {
      applyIfAllowed(pkg, InstallPhase.APPLICATION_ATTACH, "attach")
    }
    val patchAtCreate = Runnable {
      applyIfAllowed(pkg, InstallPhase.APPLICATION_ON_CREATE, "onCreate")
    }
    installActivityThreadHook(patchAtBind)
    installApplicationLifecycleHooks(patchAtAttach, patchAtCreate)
  }

  private fun applyIfAllowed(packageName: String, phase: InstallPhase, reason: String) {
    if (phase.ordinal < BuiltinAppProfiles.forPackage(packageName).applyBuildAtPhase.ordinal) return
    SpoofRuntime.refreshAndApply(TargetContextHolder.appContext, reason)
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

  private fun installApplicationLifecycleHooks(patchAtAttach: Runnable, patchAtCreate: Runnable) {
    try {
      XposedHelpers.findAndHookMethod(
        Application::class.java,
        "attach",
        Context::class.java,
        object : XC_MethodHook() {
          override fun afterHookedMethod(param: MethodHookParam) {
            TargetContextHolder.bind(param.args[0] as Context)
            patchAtAttach.run()
          }
        },
      )
      XposedHelpers.findAndHookMethod(
        Application::class.java,
        "onCreate",
        object : XC_MethodHook() {
          override fun afterHookedMethod(param: MethodHookParam) {
            TargetContextHolder.bind(param.thisObject as Application)
            patchAtCreate.run()
          }
        },
      )
    } catch (e: Throwable) {
      XposedBridge.log("${XposedConstants.TAG}: Application lifecycle hook skip: ${e.message}")
    }
  }
}
