package com.yumito.yumyhook.xposed.stealth

/** 规范化路径并判断是否属于 Hook 探测文件（CheckEmu HOOK_FRAMEWORK_FILES）。 */
object SensitivePathFilter {

    fun isHidden(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return normalize(path) in StealthConstants.HIDDEN_PROBE_PATHS
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
