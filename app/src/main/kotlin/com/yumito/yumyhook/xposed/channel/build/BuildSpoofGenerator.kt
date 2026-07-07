package com.yumito.yumyhook.xposed.channel.build

import com.yumito.yumyhook.xposed.config.HookSpoofValues

import java.util.Locale
import kotlin.random.Random

/** 生成合理、彼此一致的 Build 伪装档案（品牌 / ABI / 指纹等）。 */
object BuildSpoofGenerator {

    data class Preset(val label: String, val build: Map<String, String>, val ids: Map<String, String>)

    object Presets {
        val PIXEL_9_PRO = Preset(
            label = "Google Pixel 9 Pro",
            build = mapOf(
                "MODEL" to "Pixel 9 Pro",
                "BRAND" to "google",
                "MANUFACTURER" to "Google",
                "DEVICE" to "caiman",
                "PRODUCT" to "caiman",
                "HARDWARE" to "caiman",
                "BOARD" to "caiman",
                "BOOTLOADER" to "caiman-15.0-11223344",
                "DISPLAY" to "AP2A.240805.005",
                "HOST" to "ab-build-042",
                "ID" to "AP2A.240805.005",
                "TAGS" to "release-keys",
                "TYPE" to "user",
                "USER" to "android-build",
                "TIME" to "1722556800000",
                "RADIO" to "unknown",
                "CPU_ABI" to "arm64-v8a",
                "CPU_ABI2" to "",
                "SUPPORTED_ABIS" to "[arm64-v8a, armeabi-v7a, armeabi]",
                "SUPPORTED_32_BIT_ABIS" to "[armeabi-v7a, armeabi]",
                "SUPPORTED_64_BIT_ABIS" to "[arm64-v8a]",
                "SERIAL" to "R58M9K4A2B7C",
                "SDK_INT" to "35",
                "RELEASE" to "15",
                "INCREMENTAL" to "12235381",
                "CODENAME" to "REL",
                "SECURITY_PATCH" to "2025-06-05",
                "BASE_OS" to "",
                "PREVIEW_SDK_INT" to "0",
                "RESOURCES_SDK_INT" to "35",
                "FINGERPRINT" to "google/caiman/caiman:15/AP2A.240805.005/12235381:user/release-keys",
            ),
            ids = mapOf(
                "androidId" to "a3f2c89104b27e16",
                "serialNo" to "R58M9K4A2B7C",
            ),
        )
    }

    private data class ModelSpec(
        val model: String,
        val device: String,
        val product: String,
        val hardware: String,
        val board: String,
    )

    private data class BrandProfile(
        val brand: String,
        val manufacturer: String,
        val models: List<ModelSpec>,
    )

    private val PROFILES = listOf(
        BrandProfile(
            brand = "samsung",
            manufacturer = "samsung",
            models = listOf(
                ModelSpec("SM-S928B", "e3q", "e3qxxx", "qcom", "kalama"),
                ModelSpec("SM-S926B", "e2q", "e2qxxx", "qcom", "kalama"),
                ModelSpec("SM-A546B", "a54x", "a54xnaxx", "s5e8835", "s5e8835"),
            ),
        ),
        BrandProfile(
            brand = "google",
            manufacturer = "Google",
            models = listOf(
                ModelSpec("Pixel 9 Pro", "caiman", "caiman", "caiman", "caiman"),
                ModelSpec("Pixel 8", "shiba", "shiba", "shiba", "shiba"),
                ModelSpec("Pixel 7a", "lynx", "lynx", "lynx", "lynx"),
            ),
        ),
        BrandProfile(
            brand = "Xiaomi",
            manufacturer = "Xiaomi",
            models = listOf(
                ModelSpec("23127PN0CC", "houji", "houji", "qcom", "kalama"),
                ModelSpec("2304FPN6DC", "fuxi", "fuxi", "qcom", "kalama"),
                ModelSpec("2201123G", "cupid", "cupid", "qcom", "taro"),
            ),
        ),
        BrandProfile(
            brand = "OnePlus",
            manufacturer = "OnePlus",
            models = listOf(
                ModelSpec("CPH2581", "salami", "salami", "qcom", "kalama"),
                ModelSpec("PHB110", "aston", "aston", "qcom", "kalama"),
            ),
        ),
        BrandProfile(
            brand = "OPPO",
            manufacturer = "OPPO",
            models = listOf(
                ModelSpec("CPH2609", "ossi", "ossi", "qcom", "kalama"),
                ModelSpec("PHZ110", "waffle", "waffle", "qcom", "kalama"),
            ),
        ),
    )

    private val SDK_RELEASE = mapOf(
        33 to "13",
        34 to "14",
        35 to "15",
    )

    private val ABI_SETS = listOf(
        AbiSet(
            cpuAbi = "arm64-v8a",
            cpuAbi2 = "",
            supported = "[arm64-v8a, armeabi-v7a, armeabi]",
            supported32 = "[armeabi-v7a, armeabi]",
            supported64 = "[arm64-v8a]",
        ),
        AbiSet(
            cpuAbi = "arm64-v8a",
            cpuAbi2 = "",
            supported = "[arm64-v8a]",
            supported32 = "[]",
            supported64 = "[arm64-v8a]",
        ),
    )

    private data class AbiSet(
        val cpuAbi: String,
        val cpuAbi2: String,
        val supported: String,
        val supported32: String,
        val supported64: String,
    )

    data class RandomizeResult(
        val values: HookSpoofValues,
    )

    fun fromPreset(preset: Preset): HookSpoofValues =
        HookSpoofValues(preset.label, preset.build, preset.ids)

    fun randomize(): RandomizeResult {
        val profile = PROFILES.random()
        val model = profile.models.random()
        val sdkInt = SDK_RELEASE.keys.random()
        val release = SDK_RELEASE.getValue(sdkInt)
        val abi = ABI_SETS.random()
        val incremental = Random.nextInt(10_000_000, 99_999_999).toString()
        val buildId = randomBuildId(release)
        val serial = randomSerial()
        val androidId = randomHex(16)
        val securityPatch = randomSecurityPatch()
        val tags = "release-keys"
        val buildType = "user"
        val user = "android-build"
        val host = "ab-build-${Random.nextInt(100, 999)}"
        val time = (System.currentTimeMillis() - Random.nextLong(30L * 24 * 3600_000, 180L * 24 * 3600_000)).toString()
        val fingerprint = "${profile.brand}/${model.product}/${model.device}:$release/$buildId/$incremental:$user/$tags"
        val display = buildId
        val bootloader = "${model.device}-${release}.0-${Random.nextInt(10_000_000, 99_999_999)}"

        val buildFields = linkedMapOf(
            "MODEL" to model.model,
            "BRAND" to profile.brand,
            "MANUFACTURER" to profile.manufacturer,
            "DEVICE" to model.device,
            "PRODUCT" to model.product,
            "HARDWARE" to model.hardware,
            "BOARD" to model.board,
            "BOOTLOADER" to bootloader,
            "DISPLAY" to display,
            "HOST" to host,
            "ID" to buildId,
            "TAGS" to tags,
            "TYPE" to buildType,
            "USER" to user,
            "TIME" to time,
            "RADIO" to "unknown",
            "CPU_ABI" to abi.cpuAbi,
            "CPU_ABI2" to abi.cpuAbi2,
            "SUPPORTED_ABIS" to abi.supported,
            "SUPPORTED_32_BIT_ABIS" to abi.supported32,
            "SUPPORTED_64_BIT_ABIS" to abi.supported64,
            "SERIAL" to serial,
            "SDK_INT" to sdkInt.toString(),
            "RELEASE" to release,
            "INCREMENTAL" to incremental,
            "CODENAME" to "REL",
            "SECURITY_PATCH" to securityPatch,
            "BASE_OS" to "",
            "PREVIEW_SDK_INT" to "0",
            "RESOURCES_SDK_INT" to sdkInt.toString(),
            "FINGERPRINT" to fingerprint,
        )

        val idsFields = mapOf(
            "androidId" to androidId,
            "serialNo" to serial,
            "imei" to randomImei(),
            "imsi" to "46000${Random.nextLong(1_000_000_000L, 9_999_999_999L)}",
            "phoneNo" to "1${Random.nextInt(30, 99)}${Random.nextInt(10000000, 99999999)}",
            "simOperator" to "46000",
            "simOperatorName" to "中国移动",
            "simCountryIso" to "cn",
        )

        val label = "${profile.manufacturer} ${model.model}"
        val values = HookSpoofValues(label, buildFields, idsFields)
        return RandomizeResult(values)
    }

    private fun randomBuildId(release: String): String {
        val prefix = when (release) {
            "15" -> "AP2A"
            "14" -> "UP1A"
            else -> "TP1A"
        }
        return String.format(Locale.US, "%s.%06d.%03d", prefix, Random.nextInt(240_000, 241_000), Random.nextInt(1, 999))
    }

    private fun randomSerial(): String =
        "R58M${randomHex(8).uppercase(Locale.US)}"

    private fun randomHex(length: Int): String =
        (1..length).joinToString("") { Random.nextInt(16).toString(16) }

    private fun randomImei(): String {
        val base = (1..14).joinToString("") { Random.nextInt(10).toString() }
        var sum = 0
        base.forEachIndexed { index, c ->
            var digit = c.digitToInt()
            if (index % 2 == 1) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
        }
        val check = (10 - (sum % 10)) % 10
        return base + check
    }

    private fun randomSecurityPatch(): String {
        val year = 2024 + Random.nextInt(0, 2)
        val month = Random.nextInt(1, 13)
        val day = Random.nextInt(1, 29)
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }
}
