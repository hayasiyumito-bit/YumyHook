package com.yumito.yumyhook.xposed.stealth.install

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.stealth.hide.StealthPackageFilter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * system_server：PackageManagerService 层藏包（跨进程 PM 查询）。
 * 仅用于 Android 系统框架作用域，不装 Native / 四通道。
 */
object FrameworkPackageStealthHook {

    private const val PMS = "com.android.server.pm.PackageManagerService"

    @Volatile
    private var installed = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val cl = lpparam.classLoader
            hookList(cl, "getInstalledPackages", Integer.TYPE, Integer.TYPE)
            hookList(cl, "getInstalledPackages", Long::class.javaPrimitiveType!!, Integer.TYPE)
            hookList(cl, "getInstalledApplications", Integer.TYPE, Integer.TYPE)
            hookList(cl, "getInstalledApplications", Long::class.javaPrimitiveType!!, Integer.TYPE)
            hookPackageInfo(cl, "getPackageInfo", String::class.java, Integer.TYPE, Integer.TYPE)
            hookPackageInfo(cl, "getPackageInfo", String::class.java, Long::class.javaPrimitiveType!!, Integer.TYPE)
            hookPackageInfo(cl, "getApplicationInfo", String::class.java, Integer.TYPE, Integer.TYPE)
            hookPackageInfo(cl, "getApplicationInfo", String::class.java, Long::class.javaPrimitiveType!!, Integer.TYPE)
            installed = true
            XposedBridge.log("${XposedConstants.TAG}: framework PMS package hide installed")
        }
    }

    private fun hookList(cl: ClassLoader, method: String, vararg params: Any) {
        try {
            XposedHelpers.findAndHookMethod(
                PMS,
                cl,
                method,
                *params,
                ListFilterHook(),
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: framework PMS.$method skip: ${e.message}")
        }
    }

    private fun hookPackageInfo(cl: ClassLoader, method: String, vararg params: Any) {
        try {
            XposedHelpers.findAndHookMethod(
                PMS,
                cl,
                method,
                *params,
                PackageLookupHook(),
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: framework PMS.$method skip: ${e.message}")
        }
    }

    private class ListFilterHook : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val result = param.result ?: return
            if (result is List<*>) {
                param.result = filterList(result)
                return
            }
            if (result.javaClass.name.contains("ParceledListSlice")) {
                val list = XposedHelpers.callMethod(result, "getList") as? List<*> ?: return
                val filtered = filterList(list)
                param.result = XposedHelpers.newInstance(result.javaClass, filtered)
            }
        }
    }

    private class PackageLookupHook : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val pkg = param.args.firstOrNull() as? String ?: return
            if (!StealthPackageFilter.isHidden(pkg)) return
            param.throwable = android.content.pm.PackageManager.NameNotFoundException(pkg)
        }
    }

    private fun filterList(list: List<*>): List<Any?> {
        return list.filter { item ->
            !StealthPackageFilter.isHidden(packageNameOf(item))
        }
    }

    private fun packageNameOf(item: Any?): String? {
        return when (item) {
            is ApplicationInfo -> item.packageName
            is PackageInfo -> item.packageName
            else -> runCatching {
                XposedHelpers.getObjectField(item, "packageName") as? String
            }.getOrNull()
        }
    }
}
