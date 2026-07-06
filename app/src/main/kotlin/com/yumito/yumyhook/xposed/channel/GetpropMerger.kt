package com.yumito.yumyhook.xposed.channel

import com.yumito.yumyhook.xposed.config.HookSpoofValues

/** 将真实 getprop 全量输出与伪装属性合并，避免全量 dump 仅含少量 key 暴露 Hook。 */
object GetpropMerger {

    private val LINE_PATTERN = Regex("""^\[(.+?)]: \[(.*)]$""")

    fun merge(realOutput: String, values: HookSpoofValues): String {
        val merged = linkedMapOf<String, String>()
        for (line in realOutput.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val match = LINE_PATTERN.matchEntire(trimmed) ?: continue
            merged[match.groupValues[1]] = match.groupValues[2]
        }
        merged.putAll(SystemPropertyMapper.allProperties(values))
        merged.putAll(SystemPropertyMapper.securityProbeProperties())
        return merged.entries.joinToString("\n") { "[${it.key}]: [${it.value}]" }
    }
}
