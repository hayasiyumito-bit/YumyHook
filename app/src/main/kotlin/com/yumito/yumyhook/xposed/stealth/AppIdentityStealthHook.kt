package com.yumito.yumyhook.xposed.stealth

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** 伪装应用身份：去除目标 App 自身 DEBUGGABLE 标记 */
object AppIdentityStealthHook {
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clearDebug = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val pkg = param.args[0] as? String ?: return
                if (pkg != lpparam.packageName) return
                when (val result = param.result) {
                    is ApplicationInfo ->
                        result.flags = result.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
                    is PackageInfo -> {
                        val appInfo = result.applicationInfo ?: return
                        appInfo.flags = appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
                    }
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                lpparam.classLoader,
                "getApplicationInfo",
                String::class.java,
                Int::class.javaPrimitiveType,
                clearDebug,
            )
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                lpparam.classLoader,
                "getPackageInfo",
                String::class.java,
                Int::class.javaPrimitiveType,
                clearDebug,
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: AppIdentityStealth skip: ${e.message}")
        }
    }
}
