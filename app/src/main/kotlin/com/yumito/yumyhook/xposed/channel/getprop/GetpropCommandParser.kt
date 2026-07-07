package com.yumito.yumyhook.xposed.channel.getprop

import com.yumito.yumyhook.xposed.stealth.root.ShellProbeFilter
import java.util.Locale

/** 解析 shell / ProcessBuilder 中的 getprop 命令（含 `/bin/sh -c getprop key`）。 */
object GetpropCommandParser {

    fun isGetpropCommand(command: String?): Boolean {
        if (command.isNullOrBlank()) return false
        val sub = ShellProbeFilter.extractShellSubcommand(command.trim())
        val lower = sub.trim().lowercase(Locale.US)
        return lower == "getprop" || lower.startsWith("getprop ")
    }

    fun parseKey(command: String): String? {
        val sub = ShellProbeFilter.extractShellSubcommand(command.trim())
        val tokens = sub.split(Regex("\\s+"))
        val idx = tokens.indexOfFirst { it == "getprop" || it.endsWith("/getprop") }
        if (idx < 0) return null
        return tokens.getOrNull(idx + 1)?.takeIf { !it.startsWith("-") }
    }
}
