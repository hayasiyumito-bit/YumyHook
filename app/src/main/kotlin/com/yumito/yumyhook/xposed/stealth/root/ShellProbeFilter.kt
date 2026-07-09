package com.yumito.yumyhook.xposed.stealth.root
/** CheckEmu / Cmd.exe 等 Root / Magisk 探测用的 shell 子命令。 */
object ShellProbeFilter {

    private val BLOCKED_PATTERNS = listOf(
        Regex("""\bresetprop\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwhich\s+(su|magisk|apatch|ksud?|busybox|ap|apd)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcommand\s+-v\s+(su|magisk|ksu|ap)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bls\s+.*/system(?:_ext)?/(?:bin|xbin)/su\b""", RegexOption.IGNORE_CASE),
        Regex("""\bls\s+.*/system/(?:bin|xbin)/su\b""", RegexOption.IGNORE_CASE),
        Regex("""\bls\s+.*/xbin/su\b""", RegexOption.IGNORE_CASE),
        Regex("""\btype\s+su\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(test|stat|\[)\s+.*\b(su|magisk|busybox|ap|apd|ksu|ksud)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(test|stat|\[)\s+.*\b/data/adb\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(su|magisk)\s+(-c|--version|-v)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bsu\s+(-c|--version|-v)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(busybox)\s+(su|magisk)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bsu\s+-c\s+id\b""", RegexOption.IGNORE_CASE),
        Regex("""\bsu\s+-c\s+whoami\b""", RegexOption.IGNORE_CASE),
        Regex("""\bsu\s+-v\b""", RegexOption.IGNORE_CASE),
        Regex("""\bsu\s+--version\b""", RegexOption.IGNORE_CASE),
        Regex("""\bgetprop\b.*\b(ro\.build\.tags|ro\.debuggable|ro\.secure|ro\.build\.type|magisk|zygisk|kernelsu|apatch|ksud?|apd?)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpm\s+(list|path)\s+packages\b.*\b(magisk|kernelsu|supersu|apatch)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpm\s+list\s+packages\b.*\b(magisk|kernelsu|apatch)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bls\s+.*/data/adb\b""", RegexOption.IGNORE_CASE),
        Regex("""\bls\s+.*/data/adb/.*""", RegexOption.IGNORE_CASE),
        Regex("""\bls\s+.*/sbin\b.*\b(magisk|su)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bmount\b.*\b(magisk|overlay|apatch|kernelsu)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcat\s+.*\b/proc/\S+""", RegexOption.IGNORE_CASE),
        Regex("""\bhead\s+.*\b/proc/\S+""", RegexOption.IGNORE_CASE),
        Regex("""\bmore\s+.*\b/proc/\S+""", RegexOption.IGNORE_CASE),
        Regex("""\bgrep\s+.*\b/proc/\S+""", RegexOption.IGNORE_CASE),
        Regex("""\bgrep\s+.*\b(xposed|lsposed|frida|magisk|shadowhook|substrate|riru|zygisk|kernelsu|busybox|apatch|apd?|ksud?)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bstrings\s+.*\b/proc/\S+""", RegexOption.IGNORE_CASE),
        Regex("""\bls\s+.*\b/proc\b""", RegexOption.IGNORE_CASE),
        Regex("""\bfind\s+.*\b/data/(adb|misc)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bstat\s+.*\b(/proc/\S+|su|magisk|apatch|ksu|ap|apd)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bgetprop\s+.*\b(secure|debug|xposed|magisk|apatch|ksu|ap)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bid\b.*\buid=0\b""", RegexOption.IGNORE_CASE),
        Regex("""\bid\b.*\broot\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwhoami\b""", RegexOption.IGNORE_CASE),
        Regex("""\bsu\b""", RegexOption.IGNORE_CASE),
    )

    fun shouldSanitize(command: String?): Boolean {
        if (command.isNullOrBlank()) return false
        val trimmed = command.trim()
        return BLOCKED_PATTERNS.any { it.containsMatchIn(trimmed) }
    }

    fun shouldSanitizeArgv(parts: List<String>): Boolean {
        if (parts.isEmpty()) return false
        if (shouldSanitize(parts.joinToString(" "))) return true
        return parts.any { token ->
            val lower = token.lowercase()
            lower == "ksu" ||
                lower == "ksud" ||
                lower.endsWith("/su") ||
                lower.contains("magisk") ||
                lower.contains("apatch") ||
                lower.contains("busybox") ||
                lower.contains("kernelsu") ||
                lower.contains("supersu") ||
                lower == "ap" ||
                lower == "apd"
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
