package com.yumito.yumyhook.xposed.stealth

import java.util.Locale

object ProcMapsFilter {

    fun filter(content: String): String {
        return content.lineSequence()
            .filter { line ->
                val lower = line.lowercase(Locale.US)
                StealthConstants.PROC_MAPS_FILTER_KEYWORDS.none { keyword -> lower.contains(keyword) }
            }
            .joinToString("\n")
    }
}
