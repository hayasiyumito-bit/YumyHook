package com.yumito.yumyhook.xposed.stealth.hide

/** /proc/self/status 反调试字段清洗。 */
object ProcStatusFilter {

    fun filter(content: String): String {
        return content.lineSequence()
            .map { line ->
                when {
                    line.startsWith("TracerPid:") -> "TracerPid:\t0"
                    line.startsWith("Ptrace:") -> "Ptrace:\t0"
                    else -> line
                }
            }
            .joinToString("\n")
    }
}
