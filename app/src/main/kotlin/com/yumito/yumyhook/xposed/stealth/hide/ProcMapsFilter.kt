package com.yumito.yumyhook.xposed.stealth.hide

import com.yumito.yumyhook.xposed.stealth.common.StealthConstants
import java.util.Locale

object ProcMapsFilter {

    fun filter(content: String): String {
        return content.lineSequence()
            .filterNot { line -> shouldHideLine(line) }
            .joinToString("\n")
    }

    internal fun shouldHideLine(line: String): Boolean {
        val lower = line.lowercase(Locale.US)
        if (StealthConstants.PROC_MAPS_FILTER_KEYWORDS.any { keyword -> lower.contains(keyword) }) {
            return true
        }
        if (!lower.contains("r-xp") || !lower.contains("[anon:")) {
            return false
        }
        return lower.contains("hook") ||
            lower.contains("shadow") ||
            lower.contains("trampoline") ||
            lower.contains("jit-cache")
    }
}
