package com.yumito.yumyhook.xposed

import com.yumito.yumyhook.model.HookFeatures
import com.yumito.yumyhook.util.isEnabled
import org.json.JSONObject

data class HookSpoofValues(
    val profileLabel: String,
    val buildFields: Map<String, String>,
    val idsFields: Map<String, String>,
    val updatedAt: Long = 0L,
) {
    fun getBuildField(key: String): String? = buildFields[key]

    fun buildSummary(): String {
        val model = buildFields["MODEL"] ?: "?"
        val brand = buildFields["BRAND"] ?: "?"
        val release = buildFields["RELEASE"] ?: "?"
        val abi = buildFields["CPU_ABI"] ?: buildFields["SUPPORTED_ABIS"] ?: "?"
        return "$brand $model · Android $release · $abi"
    }

    fun fullParametersSummary(features: HookFeatures): String {
        val lines = mutableListOf<String>()
        lines += "配置：${features.configName}"
        if (buildFields.isNotEmpty()) {
            lines += "—— 设备参数 ——"
            buildFields.toSortedMap().forEach { (k, v) -> lines += "$k=$v" }
        }
        if (idsFields.isNotEmpty()) {
            lines += "—— SIM / 标识 ——"
            idsFields.toSortedMap().forEach { (k, v) -> lines += "$k=$v" }
        }
        lines += "—— 已开开关 ——"
        HookFeatures.uiCatalog()
            .plus(HookFeatures.experimentalCatalog())
            .filter { features.isEnabled(it.key) }
            .forEach { lines += "[开] ${it.title}" }
        return lines.joinToString("\n")
    }

    fun revisionToken(): String = "$profileLabel|$updatedAt|${buildFields["MODEL"]}|${buildFields["FINGERPRINT"]}"

    companion object {
        val DEFAULT: HookSpoofValues = BuildSpoofGenerator.fromPreset(BuildSpoofGenerator.Presets.PIXEL_9_PRO)

        fun fromJson(
            buildJson: String,
            idsJson: String,
            profileLabel: String,
            updatedAt: Long = 0L,
        ): HookSpoofValues {
            val build = parseMap(buildJson)
            val ids = parseMap(idsJson)
            return HookSpoofValues(profileLabel, build, ids, updatedAt)
        }

        private fun parseMap(json: String): Map<String, String> {
            return try {
                val obj = JSONObject(json)
                obj.keys().asSequence().associateWith { obj.getString(it) }
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }
}
