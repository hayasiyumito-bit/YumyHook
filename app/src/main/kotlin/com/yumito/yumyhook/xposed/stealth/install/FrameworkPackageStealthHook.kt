package com.yumito.yumyhook.xposed.stealth.install

import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.stealth.hide.StealthPackageFilter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * system_server：PMS 单包查询藏包（Magisk / 模块）。
 * 不 Hook 列表类方法，避免 ParceledListSlice 重建导致 system_server 崩溃。
 */
object FrameworkPackageStealthHook {

    private const val PMS = "com.android.server.pm.PackageManagerService"

    private val installed = AtomicBoolean(false)

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!installed.compareAndSet(false, true)) return
        val cl = lpparam.classLoader
        var hooked = 0
        hooked += hookPackageInfo(cl, "getPackageInfo", String::class.java, Integer.TYPE, Integer.TYPE)
        hooked += hookPackageInfo(cl, "getPackageInfo", String::class.java, Long::class.javaPrimitiveType!!, Integer.TYPE)
        hooked += hookPackageInfo(cl, "getApplicationInfo", String::class.java, Integer.TYPE, Integer.TYPE)
        hooked += hookPackageInfo(cl, "getApplicationInfo", String::class.java, Long::class.javaPrimitiveType!!, Integer.TYPE)
        if (hooked == 0) {
            installed.set(false)
            XposedBridge.log("${XposedConstants.TAG}: framework PMS lookup hide skipped (no methods)")
        } else {
            XposedBridge.log("${XposedConstants.TAG}: framework PMS lookup hide installed ($hooked)")
        }
    }

    private fun hookPackageInfo(cl: ClassLoader, method: String, vararg params: Any): Int {
        return try {
            XposedHelpers.findAndHookMethod(
                PMS,
                cl,
                method,
                *params,
                PackageLookupHook(),
            )
            1
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: framework PMS.$method skip: ${e.message}")
            0
        }
    }

    private class PackageLookupHook : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                val pkg = param.args.firstOrNull() as? String ?: return
                if (!StealthPackageFilter.isHidden(pkg)) return
                param.throwable = android.content.pm.PackageManager.NameNotFoundException(pkg)
            } catch (e: Throwable) {
                XposedBridge.log("${XposedConstants.TAG}: framework PMS lookup hook err: ${e.message}")
            }
        }
    }
}
