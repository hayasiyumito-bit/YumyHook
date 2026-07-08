package com.yumito.yumyhook.xposed.stealth.root

import com.yumito.yumyhook.xposed.channel.getprop.GetpropCommandParser
import com.yumito.yumyhook.xposed.channel.getprop.GetpropMerger
import com.yumito.yumyhook.xposed.channel.systemproperty.SystemPropertyMapper
import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.policy.FourChannelGate
import com.yumito.yumyhook.xposed.runtime.HookReentryGuard
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.ByteArrayInputStream
import java.io.InputStream

/** 拦截并伪装 Shell 探测命令（Runtime.exec / ProcessBuilder）。 */
object ShellStealthHook {

    private val BLOCKED = listOf(
        Regex("""\bresetprop\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwhich\s+(su|magisk|apatch|ksud?|busybox)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcommand\s+-v\s+(su|magisk)\b""", RegexOption.IGNORE_CASE),
        Regex("""\btype\s+su\b""", RegexOption.IGNORE_CASE),
        Regex("""\bls\s+.*/system(?:_ext)?/(?:bin|xbin)/su\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(test|stat|\[)\s+.*\b(su|magisk|busybox)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(su|magisk)\s+(-c|--version|-v)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bgetprop\b.*\b(ro\.build\.tags|ro\.debuggable|ro\.secure|ro\.build\.type|magisk|zygisk|kernelsu)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpm\s+(list|path)\s+packages\b.*\b(magisk|kernelsu|supersu)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bls\s+.*/data/adb\b""", RegexOption.IGNORE_CASE),
        Regex("""\bmount\b.*\b(magisk|overlay)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bgrep\s+.*\b(xposed|lsposed|frida|magisk|shadowhook|substrate|riru|zygisk|kernelsu|busybox)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bid\b.*\buid=0\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(cat|head|more|grep|strings)\s+.*\b/proc/\S+""", RegexOption.IGNORE_CASE),
    )

    fun install() {
        val interceptor = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (HookReentryGuard.isShellProbeBypass() || !HookReentryGuard.enter()) return
                try {
                    val argv = when (val first = param.args.firstOrNull()) {
                        is String -> listOf(first)
                        is Array<*> -> first.map { it.toString() }
                        else -> return
                    }
                    val joined = argv.joinToString(" ")
                    sanitizedProcess(joined, argv)?.let { param.result = it; return }
                    if (FourChannelGate.isActive() && GetpropCommandParser.isGetpropCommand(joined)) return
                    if (shouldIntercept(joined, argv)) param.result = failingProcess(joined)
                } finally { HookReentryGuard.exit() }
            }
        }
        Runtime::class.java.declaredMethods.filter { it.name == "exec" }.forEach {
            XposedHelpers.findAndHookMethod(Runtime::class.java, "exec", *it.parameterTypes, interceptor)
        }
        try {
            XposedHelpers.findAndHookMethod(ProcessBuilder::class.java, "start", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (HookReentryGuard.isShellProbeBypass() || !HookReentryGuard.enter()) return
                    try {
                        val builder = param.thisObject as ProcessBuilder
                        val argv = builder.command()
                        val joined = argv.joinToString(" ")
                        sanitizedProcess(joined, argv)?.let { param.result = it; return }
                        if (shouldIntercept(joined, argv)) builder.command("/system/bin/sh", "-c", "exit 1")
                    } finally { HookReentryGuard.exit() }
                }
            })
        } catch (_: Throwable) {}
    }

    private fun shouldIntercept(cmd: String, argv: List<String>): Boolean {
        if (BLOCKED.any { it.containsMatchIn(cmd) }) return true
        val sub = if (cmd.contains("sh -c ")) cmd.substringAfter("sh -c ").trim('\'', '"') else cmd
        if (BLOCKED.any { it.containsMatchIn(sub) }) return true
        return argv.any { it.lowercase().let { l -> l == "ksu" || l == "ksud" || l.endsWith("/su") || l.contains("magisk") || l.contains("apatch") || l.contains("kernelsu") } }
    }

    private fun sanitizedProcess(joined: String, argv: List<String>): Process? {
        return when {
            joined.startsWith("df") -> filterOutput(joined, Regex("""magisk|zygisk|kernelsu|/data/adb/""", RegexOption.IGNORE_CASE))
            joined.contains("ps ") || joined == "ps" -> filterOutput(joined, Regex("""\b(magiskd|zygisk|ksud|apd)\b""", RegexOption.IGNORE_CASE))
            GetpropCommandParser.isGetpropCommand(joined) -> {
                if (FourChannelGate.isActive()) spoofGetprop(joined)
                else filterOutput(joined, Regex("""magisk|zygisk|kernelsu|ksu|ksud|apatch|xposed|lsposed|riru""", RegexOption.IGNORE_CASE))
            }
            else -> null
        }
    }

    private fun filterOutput(cmd: String, pattern: Regex): Process {
        val raw = HookReentryGuard.runShellProbeBypass { Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd)).inputStream.bufferedReader().readText() }
        return mockProcess(raw.lineSequence().filterNot { pattern.containsMatchIn(it) }.joinToString("\n").let { if (it.isEmpty()) "\n" else it })
    }

    private fun spoofGetprop(cmd: String): Process {
        val key = GetpropCommandParser.parseKey(cmd)
        val values = HookConfig.refreshIfStale()
        val out = if (key == null) {
            val raw = HookReentryGuard.runGetpropBypass { Runtime.getRuntime().exec(arrayOf("/system/bin/getprop")).inputStream.bufferedReader().readText() }
            GetpropMerger.merge(raw, values)
        } else SystemPropertyMapper.resolveChannelValue(key, values, HookConfig.features().hideRoot).orEmpty()
        return mockProcess(out)
    }

    private fun failingProcess(cmd: String): Process {
        val sub = if (cmd.contains("sh -c ")) cmd.substringAfter("sh -c ").trim('\'', '"') else cmd
        val msg = if (sub.startsWith("which ") || sub.startsWith("type ")) "" else "/system/bin/sh: $sub: not found"
        return mockProcess(msg, 127)
    }

    private fun mockProcess(text: String, exitCode: Int = 0): Process = object : Process() {
        private val stream = ByteArrayInputStream(text.toByteArray())
        override fun getInputStream(): InputStream = stream
        override fun getOutputStream(): java.io.OutputStream = throw UnsupportedOperationException()
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = exitCode
        override fun exitValue(): Int = exitCode
        override fun destroy() = try { stream.close() } catch (_: Exception) {}
    }
}
