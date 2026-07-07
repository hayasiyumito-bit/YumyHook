package com.yumito.yumyhook.xposed.stealth.root
/** CheckEmu / Cmd.exe 等 Root 探测用的 shell 子命令。 */
object ShellProbeFilter {

    private val BLOCKED_PATTERNS = listOf(
        Regex("""\bwhich\s+su\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwhich\s+magisk\b""", RegexOption.IGNORE_CASE),
        Regex("""\btype\s+su\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcommand\s+-v\s+su\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcat\s+.*\b/proc/\S+""", RegexOption.IGNORE_CASE),
        Regex("""\bhead\s+.*\b/proc/\S+""", RegexOption.IGNORE_CASE),
        Regex("""\bmore\s+.*\b/proc/\S+""", RegexOption.IGNORE_CASE),
        Regex("""\bgrep\s+.*\b/proc/\S+""", RegexOption.IGNORE_CASE),
        Regex("""\bgrep\s+.*\b(xposed|lsposed|frida|magisk|shadowhook|substrate|riru|zygisk)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bstrings\s+.*\b/proc/\S+""", RegexOption.IGNORE_CASE),
    )

    fun shouldSanitize(command: String?): Boolean {
        if (command.isNullOrBlank()) return false
        val normalized = command.trim()
        return BLOCKED_PATTERNS.any { it.containsMatchIn(normalized) }
    }

    fun parseShellCommand(parts: List<String>): String? {
        if (parts.isEmpty()) return null
        return parts.joinToString(" ")
    }

    /** 从 `/bin/sh -c which su` 等形式抽出实际子命令。 */
    fun extractShellSubcommand(command: String): String {
        val tokens = command.trim().split(Regex("\\s+"))
        val shIndex = tokens.indexOfFirst { it == "sh" || it.endsWith("/sh") }
        if (shIndex >= 0 && tokens.getOrNull(shIndex + 1) == "-c") {
            return tokens.drop(shIndex + 2).joinToString(" ")
        }
        return command.trim()
    }
}
