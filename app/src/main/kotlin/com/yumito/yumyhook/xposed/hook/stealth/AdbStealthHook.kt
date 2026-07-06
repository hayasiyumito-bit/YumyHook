package com.yumito.yumyhook.xposed.hook.stealth

import android.content.ContentResolver
import com.yumito.yumyhook.xposed.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * CheckEmu.isAdbEnabled / USB.adbEnable：
 * Settings.Secure.getInt(..., ADB_ENABLED) → 0
 */
object AdbStealthHook {

  private val bootClassLoader: ClassLoader? = null
  private const val ADB_ENABLED = StealthConstants.SETTINGS_ADB_ENABLED

  fun install(@Suppress("UNUSED_PARAMETER") lpparam: XC_LoadPackage.LoadPackageParam) {
    hookGetInt("android.provider.Settings\$Secure")
    hookGetInt("android.provider.Settings\$Global")
  }

  private fun hookGetInt(className: String) {
    try {
      XposedHelpers.findAndHookMethod(
        className,
        bootClassLoader,
        "getInt",
        ContentResolver::class.java,
        String::class.java,
        Int::class.javaPrimitiveType,
        object : XC_MethodHook() {
          override fun beforeHookedMethod(param: MethodHookParam) {
            val name = param.args[1] as? String ?: return
            if (name == ADB_ENABLED) {
              param.result = 0
            }
          }
        },
      )
    } catch (e: Throwable) {
      XposedBridge.log("${XposedConstants.TAG}: AdbStealth $className skip: ${e.message}")
    }
  }
}
