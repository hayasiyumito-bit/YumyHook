package com.yumito.yumyhook.xposed.stealth.identity
import android.content.pm.InstallSourceInfo
import android.os.Build
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** PackageInfo / AppsflyerInfo 安装来源 → Google Play */
object InstallSourceStealthHook {
    private const val PLAY_STORE = "com.android.vending"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                XposedHelpers.findAndHookMethod(
                    "android.app.ApplicationPackageManager",
                    lpparam.classLoader,
                    "getInstallSourceInfo",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val info = param.result as? InstallSourceInfo ?: return
                            try {
                                XposedHelpers.setObjectField(info, "mInitiatingPackageName", PLAY_STORE)
                                XposedHelpers.setObjectField(info, "mInstallingPackageName", PLAY_STORE)
                                XposedHelpers.setObjectField(info, "mOriginatingPackageName", PLAY_STORE)
                            } catch (_: Throwable) {
                            }
                        }
                    },
                )
            } catch (e: Throwable) {
                XposedBridge.log("${XposedConstants.TAG}: InstallSourceInfo skip: ${e.message}")
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                lpparam.classLoader,
                "getInstallerPackageName",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = PLAY_STORE
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: getInstallerPackageName skip: ${e.message}")
        }
    }
}
