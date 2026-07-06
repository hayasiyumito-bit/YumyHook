package com.yumito.yumyhook.xposed.stealth.hide

import com.yumito.yumyhook.xposed.stealth.common.StealthConstants
import com.yumito.yumyhook.xposed.runtime.HookReentryGuard
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File

/**
 * 系统层：读取 /proc/self/maps 时重定向到过滤后的临时文件。
 * java.io.FileInputStream 属于 BootClassLoader，必须用 null ClassLoader。
 */
object ProcMapsStealthHook {

    fun install() {
        try {
            XposedHelpers.findAndHookConstructor(
                "java.io.FileInputStream",
                null,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (HookReentryGuard.isMapsBypass()) return
                        val path = param.args[0] as? String ?: return
                        if (!isProcMapsPath(path)) return
                        val filteredPath = createFilteredMapsFile() ?: return
                        param.args[0] = filteredPath
                    }
                },
            )
            XposedHelpers.findAndHookConstructor(
                "java.io.FileReader",
                null,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (HookReentryGuard.isMapsBypass()) return
                        val path = param.args[0] as? String ?: return
                        if (!isProcMapsPath(path)) return
                        val filteredPath = createFilteredMapsFile() ?: return
                        param.args[0] = filteredPath
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: ProcMapsStealth skip: ${e.message}")
        }
    }

    private fun isProcMapsPath(path: String): Boolean {
        return path == StealthConstants.PROC_SELF_MAPS ||
            path.endsWith("/self/maps")
    }

    private fun createFilteredMapsFile(): String? {
        return try {
            HookReentryGuard.runMapsBypass {
                val real = File(StealthConstants.PROC_SELF_MAPS).readText()
                val filtered = ProcMapsFilter.filter(real)
                val temp = File.createTempFile("maps_filtered_", ".txt")
                temp.writeText(filtered)
                temp.absolutePath
            }
        } catch (e: Exception) {
            XposedBridge.log("${XposedConstants.TAG}: ProcMapsStealth filter failed: ${e.message}")
            null
        }
    }
}
