package com.yumito.yumyhook.xposed.stealth.hide

import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.runtime.HookReentryGuard
import com.yumito.yumyhook.xposed.stealth.common.StealthConstants
import de.robv.android.xposed.XposedBridge
import java.io.File

/** 读取真实 /proc 内容并写入过滤后的临时文件，供 Java I/O 重定向。 */
object ProcFsRedirect {

    fun redirectPath(originalPath: String): String? {
        return when (ProcFsPaths.kind(originalPath)) {
            ProcFsPaths.Kind.MAPS, ProcFsPaths.Kind.SMAPS -> createFilteredFile(
                StealthConstants.PROC_SELF_MAPS,
                prefix = "maps_filtered_",
            ) { raw -> ProcMapsFilter.filter(raw) }

            ProcFsPaths.Kind.STATUS -> createFilteredFile(
                StealthConstants.PROC_SELF_STATUS,
                prefix = "status_filtered_",
            ) { raw -> ProcStatusFilter.filter(raw) }

            null -> null
        }
    }

    private fun createFilteredFile(
        sourcePath: String,
        prefix: String,
        filter: (String) -> String,
    ): String? {
        return try {
            HookReentryGuard.runMapsBypass {
                val real = File(sourcePath).readText()
                val filtered = filter(real)
                val temp = File.createTempFile(prefix, ".txt")
                temp.writeText(filtered)
                temp.absolutePath
            }
        } catch (e: Exception) {
            XposedBridge.log("${XposedConstants.TAG}: ProcFsRedirect failed: ${e.message}")
            null
        }
    }
}
