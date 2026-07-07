package com.yumito.yumyhook.xposed.stealth.hide

import com.yumito.yumyhook.xposed.config.HookFeatureConfig
import com.yumito.yumyhook.xposed.stealth.common.StealthConstants

/** 规范化路径并判断是否属于 Hook / Root 探测文件。 */
object SensitivePathFilter {

    fun isHidden(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val normalized = normalize(path)
        if (normalized in StealthConstants.HIDDEN_PROBE_PATHS) return true
        if (StealthConstants.HIDDEN_PROBE_PREFIXES.any { prefix -> normalized.startsWith(prefix) }) {
            return true
        }
        if (HookFeatureConfig.refreshIfStale().hideRoot) {
            if (StealthConstants.HIDDEN_ROOT_PREFIXES.any { prefix -> normalized.startsWith(prefix) }) {
                return true
            }
            if (isRootBinaryPath(normalized)) return true
        }
        return false
    }

    private fun isRootBinaryPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith("/su") ||
            lower.contains("/magisk") ||
            lower.endsWith("/busybox") ||
            lower.contains("supersu") ||
            lower.contains("kernelsu")
    }

    fun normalize(path: String): String {
        var p = path.trim()
        while (p.contains("//")) {
            p = p.replace("//", "/")
        }
        if (p.length > 1 && p.endsWith('/')) {
            p = p.dropLast(1)
        }
        return p
    }
}
