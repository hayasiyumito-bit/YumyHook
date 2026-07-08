package com.yumito.yumyhook.xposed.stealth.hide

import com.yumito.yumyhook.xposed.runtime.HookReentryGuard
import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.stealth.common.StealthConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

/** /proc 敏感文件重定向与内容过滤。 */
object ProcFsStealthHook {

    private val PROC_MAPS = Regex("""^/proc/(?:self|\d+)/(?:task/\d+/)?s?maps$""")
    private val PROC_STATUS = Regex("""^/proc/(?:self|\d+)/(?:task/\d+/)?status$""")
    private val PROC_MOUNTS = Regex("""^/proc/(?:self|\d+)?/?mount(?:s|info)$""")

    fun install() {
        hookConstructor("java.io.FileInputStream", String::class.java)
        hookConstructor("java.io.FileInputStream", File::class.java)
        hookConstructor("java.io.FileReader", String::class.java)
        hookConstructor("java.io.FileReader", File::class.java)
        hookRandomAccessFile()
        hookFilesIo()
    }

    private fun hookConstructor(className: String, pathArg: Class<*>) {
        try {
            XposedHelpers.findAndHookConstructor(className, null, pathArg, ProcPathRedirectHook(pathArg))
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: ProcFs $className skip: ${e.message}")
        }
    }

    private fun hookRandomAccessFile() {
        try {
            XposedHelpers.findAndHookConstructor("java.io.RandomAccessFile", null, String::class.java, String::class.java, ProcPathRedirectHook(String::class.java))
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: ProcFs RandomAccessFile skip: ${e.message}")
        }
    }

    private fun hookFilesIo() {
        val pathClass = runCatching { XposedHelpers.findClass("java.nio.file.Path", null) }.getOrNull() ?: return
        val openOptionsClass = runCatching { XposedHelpers.findClass("[Ljava.nio.file.OpenOption;", null) }.getOrNull() ?: return
        val redirect = ProcPathRedirectHook(pathClass)
        listOf("newInputStream", "readAllLines", "newBufferedReader").forEach { name ->
            try {
                if (name == "newInputStream") XposedHelpers.findAndHookMethod("java.nio.file.Files", null, name, pathClass, openOptionsClass, redirect)
                else XposedHelpers.findAndHookMethod("java.nio.file.Files", null, name, pathClass, redirect)
            } catch (_: Throwable) {}
        }
    }

    private class ProcPathRedirectHook(private val pathArg: Class<*>) : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (HookReentryGuard.isMapsBypass()) return
            val path = pathText(param.args[0]) ?: return
            val normalized = SensitivePathStealthHook.normalize(path)
            if (normalized.endsWith("/mem") && (normalized.contains("/proc/self/") || normalized.contains("/proc/${android.os.Process.myPid()}/"))) {
                param.throwable = FileNotFoundException(path)
                return
            }
            val redirect = redirectPath(normalized) ?: return
            if (pathArg == String::class.java) param.args[0] = redirect
            else if (pathArg.name == "java.io.File") param.args[0] = File(redirect)
            else try {
                param.args[0] = XposedHelpers.callStaticMethod(XposedHelpers.findClass("java.nio.file.Paths", null), "get", redirect, arrayOf<String>())
            } catch (_: Throwable) {}
        }

        private fun pathText(arg: Any?): String? = when (arg) {
            is String -> arg
            is File -> arg.absolutePath
            null -> null
            else -> try { arg.toString() } catch (_: Throwable) { null }
        }

        private fun redirectPath(path: String): String? {
            val filter: ((String) -> String)? = when {
                PROC_MAPS.matches(path) || path.contains("/map_files") -> { s ->
                    s.lineSequence().filterNot { line ->
                        val lower = line.lowercase(Locale.US)
                        if (StealthConstants.PROC_MAPS_FILTER_KEYWORDS.any { lower.contains(it) }) true
                        else if (lower.contains("r-xp") && lower.contains("[anon:")) {
                            lower.contains("hook") || lower.contains("shadow") || lower.contains("trampoline") || lower.contains("jit-cache")
                        } else false
                    }.joinToString("\n")
                }
                PROC_STATUS.matches(path) -> { s ->
                    s.lineSequence().map { if (it.startsWith("TracerPid:") || it.startsWith("Ptrace:")) it.substringBefore(":") + ":\t0" else it }.joinToString("\n")
                }
                PROC_MOUNTS.matches(path) -> { s ->
                    s.lineSequence().filterNot { line ->
                        val lower = line.lowercase(Locale.US)
                        StealthConstants.PROC_MAPS_FILTER_KEYWORDS.any { lower.contains(it) }
                    }.joinToString("\n")
                }
                else -> null
            } ?: return null

            return try {
                HookReentryGuard.runMapsBypass {
                    val real = File(path).readText()
                    val temp = File.createTempFile("yh_proc_", ".txt")
                    temp.writeText(filter!!(real))
                    temp.absolutePath
                }
            } catch (_: Exception) { null }
        }
    }
}
