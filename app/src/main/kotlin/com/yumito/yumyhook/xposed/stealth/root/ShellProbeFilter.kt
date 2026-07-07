package com.yumito.yumyhook.xposed.stealth.root
/** CheckEmu / Cmd.exe 等 Root / Magisk 探测用的 shell 子命令。 */
object ShellProbeFilter {

    private val BLOCKED_PATTERNS = listOf(
        Regex("""\bwhich\s+su\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwhich\s+magisk\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwhich\s+busybox\b""", RegexOption.IGNORE_CASE),
        Regex("""\btype\s+su\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcommand\s+-v\s+su\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcommand\s+-v\s+magisk\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(test|stat|\[)\s+.*\b(su|magisk|busybox)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(su|magisk)\s+(-c|--version|-v)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(busybox)\s+(su|magisk)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bgetprop\b.*\b(ro\.build\.tags|ro\.debuggable|ro\.secure|ro\.build\.type)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bgetprop\b.*\b(magisk|zygisk|kernelsu)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpm\s+(list|path)\s+packages\b.*\b(magisk|kernelsu|supersu)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpm\s+list\s+packages\b.*\b(magisk|kernelsu)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bls\s+.*/data/adb\b""", RegexOption.IGNORE_CASE),
        Regex("""\bls\s+.*/sbin\b.*\b(magisk|su)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bmount\b.*\b(magisk|overlay)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcat\s+.*\b/proc/\S+""", RegexOption.IGNORE_CASE),
        Regex("""\bhead\s+.*\b/proc/\S+""", RegexOption.IGNORE_CASE),
        Regex("""\bmore\s+.*\b/proc/\S+""", RegexOption.IGNORE_CASE),
        Regex("""\bgrep\s+.*\b/proc/\S+""", RegexOption.IGNORE_CASE),
        Regex("""\bgrep\s+.*\b(xposed|lsposed|frida|magisk|shadowhook|substrate|riru|zygisk|kernelsu|busybox)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bstrings\s+.*\b/proc/\S+""", RegexOption.IGNORE_CASE),
        Regex("""\bls\s+.*\b/proc\b""", RegexOption.IGNORE_CASE),
        Regex("""\bfind\s+.*\b/data/(adb|misc)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bstat\s+.*\b(/proc/\S+|su|magisk)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bgetprop\s+.*\b(secure|debug|xposed|magisk)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bid\b.*\buid=0\b""", RegexOption.IGNORE_CASE),
    )

    fun shouldSanitize(command: String?): Boolean {
        if (command.isNullOrBlank()) return false
        return BLOCKED_PATTERNS.any { it.containsMatchIn(command.trim()) }
    }

    fun shouldSanitizeArgv(parts: List<String>): Boolean {
        if (parts.isEmpty()) return false
        if (shouldSanitize(parts.joinToString(" "))) return true
        return parts.any { token ->
            val lower = token.lowercase()
            lower == "su" ||
                lower.endsWith("/su") ||
                lower.contains("magisk") ||
                lower.contains("busybox") ||
                lower.contains("kernelsu") ||
                lower.contains("supersu")
        }
    }

    fun parseShellCommand(parts: List<String>): String? {
        if (parts.isEmpty()) return null
        return parts.joinToString(" ")
    }

    fun extractShellSubcommand(command: String): String {
        val tokens = command.trim().split(Regex("\\s+"))
        val shIndex = tokens.indexOfFirst { it == "sh" || it.endsWith("/sh") }
        if (shIndex >= 0 && tokens.getOrNull(shIndex + 1) == "-c") {
            return tokens.drop(shIndex + 2).joinToString(" ")
        }
        return command.trim()
    }
}
