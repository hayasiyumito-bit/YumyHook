package com.yumito.yumyhook.xposed

/** Build 字段 ↔ ro.* 系统属性映射。 */
object SystemPropertyMapper {

    private val BUILD_TO_PROP = mapOf(
        "MODEL" to "ro.product.model",
        "BRAND" to "ro.product.brand",
        "MANUFACTURER" to "ro.product.manufacturer",
        "DEVICE" to "ro.product.device",
        "PRODUCT" to "ro.product.name",
        "FINGERPRINT" to "ro.build.fingerprint",
        "DISPLAY" to "ro.build.display.id",
        "ID" to "ro.build.id",
        "TYPE" to "ro.build.type",
        "TAGS" to "ro.build.tags",
        "HOST" to "ro.build.host",
        "USER" to "ro.build.user",
        "RELEASE" to "ro.build.version.release",
        "INCREMENTAL" to "ro.build.version.incremental",
        "SECURITY_PATCH" to "ro.build.version.security_patch",
        "SDK_INT" to "ro.build.version.sdk",
        "CPU_ABI" to "ro.product.cpu.abi",
        "HARDWARE" to "ro.hardware",
        "BOARD" to "ro.board.platform",
        "BOOTLOADER" to "ro.bootloader",
        "RADIO" to "gsm.version.baseband",
    )

    private val PARTITION_SUFFIXES = listOf(
        "odm",
        "vendor",
        "product",
        "system",
        "system_ext",
        "system_dlkm",
        "vendor_dlkm",
    )

    private val PARTITION_PROP_FIELDS = mapOf(
        "model" to "MODEL",
        "brand" to "BRAND",
        "manufacturer" to "MANUFACTURER",
        "device" to "DEVICE",
        "name" to "PRODUCT",
    )

    private val PARTITION_FINGERPRINT_SUFFIXES = listOf(
        "odm",
        "vendor",
        "product",
        "system",
        "system_ext",
        "system_dlkm",
        "vendor_dlkm",
    )

    private val SECURITY_PROBE_PROPS: Map<String, String> = mapOf(
        "ro.secure" to "1",
        "ro.debuggable" to "0",
        "ro.boot.verifiedbootstate" to "green",
    )

    private val MAPPED_PROP_KEYS: Set<String> by lazy {
        val partitionKeys = PARTITION_SUFFIXES.flatMap { suffix ->
            PARTITION_PROP_FIELDS.keys.map { field -> "ro.product.$suffix.$field" }
        }
        val fingerprintKeys = PARTITION_FINGERPRINT_SUFFIXES.map { "ro.$it.build.fingerprint" }
        BUILD_TO_PROP.values.toSet() + SECURITY_PROBE_PROPS.keys + partitionKeys + fingerprintKeys + setOf(
            "ro.product.cpu.abilist",
            "ro.product.cpu.abilist64",
            "ro.product.cpu.abilist32",
            "ro.serialno",
            "ro.boot.serialno",
            "ro.build.description",
        )
    }

    fun securityProbeProperties(): Map<String, String> = SECURITY_PROBE_PROPS

    fun hasMapping(key: String): Boolean = key in MAPPED_PROP_KEYS

    fun mapProperty(key: String, values: HookSpoofValues): String? {
        when (key) {
            "ro.product.cpu.abilist" -> return abiList(values.getBuildField("SUPPORTED_ABIS"))
            "ro.product.cpu.abilist64" -> return abiList(values.getBuildField("SUPPORTED_64_BIT_ABIS"))
            "ro.product.cpu.abilist32" -> return abiList(values.getBuildField("SUPPORTED_32_BIT_ABIS"))
            "ro.serialno", "ro.boot.serialno" -> {
                return values.getBuildField("SERIAL")
                    ?: values.idsFields["serialNo"]
            }
            "ro.build.description" -> {
                val fp = values.getBuildField("FINGERPRINT") ?: return null
                return "$fp release-keys"
            }
        }
        SECURITY_PROBE_PROPS[key]?.let { return it }
        val partitionMatch = Regex("^ro\\.product\\.(odm|vendor|product|system|system_ext|system_dlkm|vendor_dlkm)\\.(model|brand|manufacturer|device|name)$")
            .matchEntire(key)
        if (partitionMatch != null) {
            val field = partitionMatch.groupValues[2]
            val buildKey = PARTITION_PROP_FIELDS[field] ?: return null
            return values.getBuildField(buildKey)
        }
        val fingerprintMatch = Regex("^ro\\.(odm|vendor|product|system|system_ext|system_dlkm|vendor_dlkm)\\.build\\.fingerprint$")
            .matchEntire(key)
        if (fingerprintMatch != null) {
            return values.getBuildField("FINGERPRINT")
        }
        val buildKey = BUILD_TO_PROP.entries.firstOrNull { it.value == key }?.key
        if (buildKey != null) {
            return values.getBuildField(buildKey)
        }
        return null
    }

    fun allProperties(values: HookSpoofValues): Map<String, String> {
        val map = linkedMapOf<String, String>()
        BUILD_TO_PROP.forEach { (buildKey, propKey) ->
            values.getBuildField(buildKey)?.let { map[propKey] = it }
        }
        abiList(values.getBuildField("SUPPORTED_ABIS"))?.let { map["ro.product.cpu.abilist"] = it }
        abiList(values.getBuildField("SUPPORTED_64_BIT_ABIS"))?.let { map["ro.product.cpu.abilist64"] = it }
        abiList(values.getBuildField("SUPPORTED_32_BIT_ABIS"))?.let { map["ro.product.cpu.abilist32"] = it }
        val serial = values.getBuildField("SERIAL") ?: values.idsFields["serialNo"]
        if (serial != null) {
            map["ro.serialno"] = serial
            map["ro.boot.serialno"] = serial
        }
        values.getBuildField("FINGERPRINT")?.let { map["ro.build.description"] = "$it release-keys" }
        map.putAll(SECURITY_PROBE_PROPS)
        appendPartitionProperties(map, values)
        return map
    }

    private fun appendPartitionProperties(map: LinkedHashMap<String, String>, values: HookSpoofValues) {
        for ((field, buildKey) in PARTITION_PROP_FIELDS) {
            val spoofed = values.getBuildField(buildKey) ?: continue
            for (suffix in PARTITION_SUFFIXES) {
                map["ro.product.$suffix.$field"] = spoofed
            }
        }
        values.getBuildField("FINGERPRINT")?.let { fingerprint ->
            for (suffix in PARTITION_FINGERPRINT_SUFFIXES) {
                map["ro.$suffix.build.fingerprint"] = fingerprint
            }
        }
    }

    fun abiList(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .split(",")
            .joinToString(",") { it.trim() }
            .ifBlank { null }
    }

    fun parseStringArray(raw: String?): Array<String> {
        if (raw.isNullOrBlank()) return emptyArray()
        return raw.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toTypedArray()
    }
}
