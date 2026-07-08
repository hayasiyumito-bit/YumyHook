package com.yumito.yumyhook.xposed.channel.getprop

import java.util.Locale

/** 解析 shell / ProcessBuilder 中的 getprop 命令（含 `/bin/sh -c getprop key`）。 */
object GetpropCommandParser {

    fun isGetpropCommand(command: String?): Boolean {
        if (command.isNullOrBlank()) return false
        val sub = extract(command)
        val lower = sub.lowercase(Locale.US)
        return lower == "getprop" || lower.startsWith("getprop ")
    }

    fun parseKey(command: String): String? {
        val sub = extract(command)
        val tokens = sub.split(Regex("\\s+"))
        val idx = tokens.indexOfFirst { it == "getprop" || it.endsWith("/getprop") }
        if (idx < 0) return null
        return tokens.getOrNull(idx + 1)?.takeIf { !it.startsWith("-") }
    }

    private fun extract(cmd: String): String {
        val trimmed = cmd.trim()
        if (trimmed.contains("sh -c ")) return trimmed.substringAfter("sh -c ").trim('\'', '"')
        return trimmed
    }
}
