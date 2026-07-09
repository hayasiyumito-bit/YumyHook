package com.yumito.yumyhook.xposed.stealth.root

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale

/** 过滤 shell 探测命令输出（对齐 persie / deviceinfo_collect df、ps 等）。 */
object ShellOutputFilter {

    private val DF_ROOT_LINE = Regex(
        """magisk|zygisk|kernelsu|ksu|ksud|apatch|ap|apd|/data/adb/|/sbin/|su\b""",
        RegexOption.IGNORE_CASE,
    )

    private val PS_ROOT_LINE = Regex(
        """\b(magiskd|zygisk|ksud?|apd?|ap|magisk|su|ksu)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val GETPROP_ROOT_LINE = Regex(
        """magisk|zygisk|kernelsu|ksu|ksud|apatch|ap|apd|xposed|lsposed|lspatch|riru|supersu|frida""",
        RegexOption.IGNORE_CASE,
    )

    fun isGetpropCommand(command: String?): Boolean =
        com.yumito.yumyhook.xposed.channel.getprop.GetpropCommandParser.isGetpropCommand(command)

    fun filterGetpropOutput(raw: String): String {
        return raw.lineSequence()
            .filterNot { line -> GETPROP_ROOT_LINE.containsMatchIn(line) }
            .joinToString("\n")
    }

    fun isDfCommand(command: String?): Boolean {
        if (command.isNullOrBlank()) return false
        val trimmed = command.trim()
        return trimmed == "df" || trimmed.startsWith("df ")
    }

    fun isPsCommand(command: String?): Boolean {
        if (command.isNullOrBlank()) return false
        val lower = command.trim().lowercase(Locale.US)
        return lower == "ps" || lower.startsWith("ps ") || lower.contains(" ps ")
    }

    fun filterDfOutput(raw: String): String {
        return raw.lineSequence()
            .filterNot { line -> DF_ROOT_LINE.containsMatchIn(line) }
            .joinToString("\n")
            .let { if (it.isEmpty()) "\n" else it }
    }

    fun filterPsOutput(raw: String): String {
        return raw.lineSequence()
            .filterNot { line -> PS_ROOT_LINE.containsMatchIn(line) }
            .joinToString("\n")
    }

    fun processWithStdout(text: String, exitCode: Int = 0): Process {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return object : Process() {
            private val stream = ByteArrayInputStream(bytes)
            private val nullStream = ByteArrayInputStream(ByteArray(0))

            override fun getInputStream(): InputStream = stream

            override fun getOutputStream(): java.io.OutputStream = object : java.io.OutputStream() {
                override fun write(b: Int) {
                    // no-op: discard writes
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    // no-op: discard writes
                }
            }

            override fun getErrorStream(): InputStream = nullStream

            override fun waitFor(): Int = exitCode

            override fun exitValue(): Int = exitCode

            override fun destroy() {
                stream.close()
            }
        }
    }
}
