package com.yumito.yumyhook.xposed.stealth.root

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale

/** 过滤 shell 探测命令输出（对齐 persie / deviceinfo_collect df、ps 等）。 */
object ShellOutputFilter {

    private val DF_ROOT_LINE = Regex(
        """magisk|zygisk|kernelsu|/data/adb/(?:magisk|ksu|ap)""",
        RegexOption.IGNORE_CASE,
    )

    private val PS_ROOT_LINE = Regex(
        """\b(magiskd|zygisk|ksud|apd)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val GETPROP_ROOT_LINE = Regex(
        """magisk|zygisk|kernelsu|ksu|ksud|apatch|xposed|lsposed|lspatch|riru|supersu|frida""",
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

    fun processWithStdout(text: String): Process {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return object : Process() {
            private val stream = ByteArrayInputStream(bytes)

            override fun getInputStream(): InputStream = stream

            override fun getOutputStream(): java.io.OutputStream {
                throw UnsupportedOperationException()
            }

            override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

            override fun waitFor(): Int = 0

            override fun exitValue(): Int = 0

            override fun destroy() {
                stream.close()
            }
        }
    }
}
