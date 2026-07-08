package com.yumito.yumyhook.xposed.stealth.hide

import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.runtime.HookReentryGuard
import com.yumito.yumyhook.xposed.stealth.common.StealthConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.io.FileNotFoundException

/**
 * 敏感路径伪装：File.exists / 构造函数拦截。
 */
object SensitivePathStealthHook {

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            hookFileStatMethods()
            listOf("java.io.FileInputStream", "java.io.FileOutputStream", "java.io.FileReader").forEach {
                hookConstructor(it, String::class.java)
                if (it != "java.io.FileOutputStream") hookConstructor(it, File::class.java)
            }
            installed = true
        }
    }

    private fun hookFileStatMethods() {
        listOf("exists", "isFile", "canRead", "canExecute").forEach { name ->
            try {
                XposedHelpers.findAndHookMethod(File::class.java, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (HookReentryGuard.isFileBypass()) return
                        val path = pathOf(param.thisObject as File)
                        if (isHidden(path)) param.result = false
                    }
                })
            } catch (e: Throwable) {
                XposedBridge.log("${XposedConstants.TAG}: SensitivePath.$name skip: ${e.message}")
            }
        }
    }

    private fun hookConstructor(className: String, pathArg: Class<*>) {
        try {
            XposedHelpers.findAndHookConstructor(className, null, pathArg, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (HookReentryGuard.isFileBypass()) return
                    val path = when (val arg = param.args[0]) {
                        is String -> arg
                        is File -> arg.absolutePath
                        else -> return
                    }
                    if (isHidden(path)) param.throwable = FileNotFoundException(path)
                }
            })
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: SensitivePath $className skip: ${e.message}")
        }
    }

    fun isHidden(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val normalized = normalize(path)
        if (normalized in StealthConstants.HIDDEN_PROBE_PATHS) return true
        if (StealthConstants.HIDDEN_PROBE_PREFIXES.any { normalized.startsWith(it) }) return true
        if (HookConfig.features().hideRoot) {
            if (StealthConstants.HIDDEN_ROOT_PREFIXES.any { normalized.startsWith(it) }) return true
            val lower = normalized.lowercase()
            if (lower.endsWith("/su") || lower.contains("/magisk") || lower.endsWith("/busybox") ||
                lower.contains("supersu") || lower.contains("kernelsu") || lower.contains("/ksu") ||
                lower.endsWith("/ksu") || lower.contains("apatch") || lower.contains("ksud")
            ) return true
        }
        return normalized.contains("/proc/") && normalized.endsWith("/mem")
    }

    fun normalize(path: String): String {
        var p = path.trim()
        while (p.contains("//")) p = p.replace("//", "/")
        return if (p.length > 1 && p.endsWith('/')) p.dropLast(1) else p
    }

    private fun pathOf(file: File): String = try {
        XposedHelpers.getObjectField(file, "path") as? String ?: file.path
    } catch (_: Throwable) { file.path }
}
