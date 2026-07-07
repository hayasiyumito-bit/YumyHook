package com.yumito.yumyhook.xposed.stealth.hide

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.stealth.common.StealthConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** 系统层：PackageManager 列表中隐藏本模块包名。 */
object PackageHideStealthHook {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pmClass = "android.app.ApplicationPackageManager"
        installListHook(lpparam, pmClass, "getInstalledApplications", Integer.TYPE)
        installListHook(lpparam, pmClass, "getInstalledPackages", Integer.TYPE)
        installPackageInfoHook(lpparam, pmClass, "getPackageInfo", String::class.java, Integer.TYPE)
        installApplicationInfoHook(lpparam, pmClass, "getApplicationInfo", String::class.java, Integer.TYPE)
        installResolveHook(lpparam, pmClass)
    }

    private fun installResolveHook(lpparam: XC_LoadPackage.LoadPackageParam, className: String) {
        try {
            val intentClass = android.content.Intent::class.java
            XposedHelpers.findAndHookMethod(
                className,
                lpparam.classLoader,
                "queryIntentActivities",
                intentClass,
                Integer.TYPE,
                object : XC_MethodHook() {
                    @Suppress("UNCHECKED_CAST")
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val list = param.result as? List<*> ?: return
                        param.result = list.filterNot { item ->
                            val pkg = XposedHelpers.getObjectField(item, "activityInfo")
                                ?.let { XposedHelpers.getObjectField(it, "packageName") as? String }
                            pkg in StealthConstants.HIDDEN_PACKAGES
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: PackageHide.queryIntentActivities skip: ${e.message}")
        }
    }

    private fun installListHook(lpparam: XC_LoadPackage.LoadPackageParam, className: String, method: String, flagsType: Any) {
        try {
            XposedHelpers.findAndHookMethod(
                className,
                lpparam.classLoader,
                method,
                flagsType,
                object : XC_MethodHook() {
                    @Suppress("UNCHECKED_CAST")
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val list = param.result as? List<*> ?: return
                        param.result = list.filterNot { item ->
                            packageNameOf(item) in StealthConstants.HIDDEN_PACKAGES
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: PackageHide.$method skip: ${e.message}")
        }
    }

    private fun installPackageInfoHook(
        lpparam: XC_LoadPackage.LoadPackageParam,
        className: String,
        method: String,
        packageNameType: Any,
        flagsType: Any,
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                className,
                lpparam.classLoader,
                method,
                packageNameType,
                flagsType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkg = param.args[0] as? String ?: return
                        if (pkg in StealthConstants.HIDDEN_PACKAGES) {
                            param.throwable = android.content.pm.PackageManager.NameNotFoundException(pkg)
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: PackageHide.$method skip: ${e.message}")
        }
    }

    private fun installApplicationInfoHook(
        lpparam: XC_LoadPackage.LoadPackageParam,
        className: String,
        method: String,
        packageNameType: Any,
        flagsType: Any,
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                className,
                lpparam.classLoader,
                method,
                packageNameType,
                flagsType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkg = param.args[0] as? String ?: return
                        if (pkg in StealthConstants.HIDDEN_PACKAGES) {
                            param.throwable = android.content.pm.PackageManager.NameNotFoundException(pkg)
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: PackageHide.$method skip: ${e.message}")
        }
    }

    private fun packageNameOf(item: Any?): String? {
        return when (item) {
            is ApplicationInfo -> item.packageName
            is PackageInfo -> item.packageName
            else -> XposedHelpers.getObjectField(item, "packageName") as? String
        }
    }
}
