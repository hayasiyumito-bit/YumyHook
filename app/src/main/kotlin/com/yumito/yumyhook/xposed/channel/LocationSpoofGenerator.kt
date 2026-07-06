package com.yumito.yumyhook.xposed.channel

import kotlin.random.Random

/** 预设 / 随机地理位置（经纬度 + 地名）。 */
object LocationSpoofGenerator {

    data class Preset(
        val placeName: String,
        val latitude: Double,
        val longitude: Double,
        val altitude: Double = 35.0,
        val accuracy: String = "12",
    )

    private val PRESETS = listOf(
        Preset("北京市朝阳区", 39.9219, 116.4436, 43.0),
        Preset("上海市浦东新区", 31.2304, 121.4737, 4.0),
        Preset("广州市天河区", 23.1291, 113.2644, 18.0),
        Preset("深圳市南山区", 22.5333, 113.9300, 28.0),
        Preset("杭州市西湖区", 30.2590, 120.1300, 12.0),
        Preset("成都市武侯区", 30.5728, 104.0668, 500.0),
        Preset("武汉市武昌区", 30.5465, 114.3162, 28.0),
        Preset("西安市雁塔区", 34.2185, 108.9402, 400.0),
        Preset("南京市鼓楼区", 32.0603, 118.7969, 15.0),
        Preset("重庆市渝中区", 29.5630, 106.5516, 220.0),
        Preset("香港中环", 22.2819, 114.1580, 8.0),
        Preset("台北市信义区", 25.0330, 121.5654, 10.0),
        Preset("东京涩谷", 35.6595, 139.7005, 32.0),
        Preset("新加坡滨海湾", 1.2834, 103.8607, 5.0),
        Preset("首尔江南区", 37.4979, 127.0276, 38.0),
    )

    fun randomize(): Map<String, String> = toFields(PRESETS.random(Random.Default))

    fun fieldsOrDefault(fields: Map<String, String>): Map<String, String> =
        if (fields["latitude"].isNullOrBlank() || fields["longitude"].isNullOrBlank()) {
            defaultFields()
        } else {
            fields
        }

    fun defaultFields(): Map<String, String> = toFields(PRESETS.first())

    fun toFields(preset: Preset): Map<String, String> = mapOf(
        "placeName" to preset.placeName,
        "latitude" to formatCoord(preset.latitude),
        "longitude" to formatCoord(preset.longitude),
        "altitude" to preset.altitude.toString(),
        "accuracy" to preset.accuracy,
    )

    private fun formatCoord(value: Double): String =
        String.format(java.util.Locale.US, "%.6f", value)
}
