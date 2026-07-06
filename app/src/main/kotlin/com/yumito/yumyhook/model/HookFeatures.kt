package com.yumito.yumyhook.model

import org.json.JSONArray
import org.json.JSONObject

/** 隐私 / 反检测功能开关（对应参考 App 配置项 + YumyHook 扩展）。 */
data class HookFeatures(
    val configName: String = "默认",
    val hideDeveloperOptions: Boolean = true,
    val hideVpn: Boolean = true,
    val spoofInstallSourcePlay: Boolean = true,
    val spoofWifiInfo: Boolean = true,
    val spoofPartialDeviceId: Boolean = true,
    val spoofFullDeviceId: Boolean = true,
    val simSimulation: Boolean = false,
    val blockLanScan: Boolean = true,
    val hideLsposed: Boolean = true,
    val hideRoot: Boolean = true,
    val hideAirplaneMode: Boolean = true,
    val hideProxy: Boolean = true,
    val hideWifiNetworks: Boolean = true,
    val hideBluetooth: Boolean = true,
    val spoofUptime: Boolean = false,
    val spoofAppIdentity: Boolean = false,
    val spoofBrowserFingerprint: Boolean = true,
    /** 全局地理位置伪装（LocationManager / Location，不按包名分支） */
    val spoofLocation: Boolean = false,
    /** 四通道属性对齐主开关：Build + getprop + SystemProperties + JNI Native */
    val spoofBuildProperties: Boolean = true,
    val nativePropertyHook: Boolean = true,
    val preventNativeCrash: Boolean = false,
    /** 按 LSPosed 作用域包名关闭四通道（仅存被关闭的包名） */
    val disabledScopedFourChannel: Set<String> = emptySet(),
    /** 四通道仍开、仅关 JNI Native（宿主自带 shadowhook 等场景） */
    val disabledScopedNative: Set<String> = emptySet(),
) {
    fun shouldInstallNative(): Boolean = spoofBuildProperties && nativePropertyHook

    /** 本地 per-app 三通道开关（不含总开关门控，供 UI 还原）。 */
    fun isJavaThreeChannelStoredFor(packageName: String): Boolean =
        packageName.isNotBlank() && !disabledScopedFourChannel.contains(packageName)

    /** 本地 per-app Native 开关（不含总开关门控，供 UI 还原）。 */
    fun isNativeStoredFor(packageName: String): Boolean =
        packageName.isNotBlank() && !disabledScopedNative.contains(packageName)

    fun isJavaThreeChannelEnabledFor(packageName: String): Boolean =
        spoofBuildProperties && isJavaThreeChannelStoredFor(packageName)

    fun isFourChannelEnabledFor(packageName: String): Boolean = isJavaThreeChannelEnabledFor(packageName)

    fun isNativeEnabledFor(packageName: String): Boolean =
        nativePropertyHook &&
            isJavaThreeChannelEnabledFor(packageName) &&
            isNativeStoredFor(packageName)

    fun withScopedFourChannel(packageName: String, enabled: Boolean): HookFeatures {
        val next = disabledScopedFourChannel.toMutableSet()
        if (enabled) {
            next.remove(packageName)
        } else {
            next.add(packageName)
        }
        return copy(disabledScopedFourChannel = next)
    }

    fun withScopedNative(packageName: String, enabled: Boolean): HookFeatures {
        val next = disabledScopedNative.toMutableSet()
        if (enabled) {
            next.remove(packageName)
        } else {
            next.add(packageName)
        }
        return copy(disabledScopedNative = next)
    }

    fun withToggle(key: String, enabled: Boolean): HookFeatures = when (key) {
        "hideDeveloperOptions" -> copy(hideDeveloperOptions = enabled)
        "hideVpn" -> copy(hideVpn = enabled)
        "spoofInstallSourcePlay" -> copy(spoofInstallSourcePlay = enabled)
        "spoofWifiInfo" -> copy(spoofWifiInfo = enabled)
        "spoofPartialDeviceId" -> copy(spoofPartialDeviceId = enabled)
        "spoofFullDeviceId" -> copy(spoofFullDeviceId = enabled)
        "simSimulation" -> copy(simSimulation = enabled)
        "blockLanScan" -> copy(blockLanScan = enabled)
        "hideLsposed" -> copy(hideLsposed = enabled)
        "hideRoot" -> copy(hideRoot = enabled)
        "hideAirplaneMode" -> copy(hideAirplaneMode = enabled)
        "hideProxy" -> copy(hideProxy = enabled)
        "hideWifiNetworks" -> copy(hideWifiNetworks = enabled)
        "hideBluetooth" -> copy(hideBluetooth = enabled)
        "spoofUptime" -> copy(spoofUptime = enabled)
        "spoofAppIdentity" -> copy(spoofAppIdentity = enabled)
        "spoofBrowserFingerprint" -> copy(spoofBrowserFingerprint = enabled)
        "spoofLocation" -> copy(spoofLocation = enabled)
        "spoofBuildProperties" -> copy(spoofBuildProperties = enabled).normalized()
        "nativePropertyHook" -> copy(nativePropertyHook = enabled).normalized()
        "preventNativeCrash" -> copy(nativePropertyHook = !enabled, spoofBuildProperties = true).normalized()
        else -> this
    }

    fun isEnabled(key: String): Boolean = when (key) {
        "hideDeveloperOptions" -> hideDeveloperOptions
        "hideVpn" -> hideVpn
        "spoofInstallSourcePlay" -> spoofInstallSourcePlay
        "spoofWifiInfo" -> spoofWifiInfo
        "spoofPartialDeviceId" -> spoofPartialDeviceId
        "spoofFullDeviceId" -> spoofFullDeviceId
        "simSimulation" -> simSimulation
        "blockLanScan" -> blockLanScan
        "hideLsposed" -> hideLsposed
        "hideRoot" -> hideRoot
        "hideAirplaneMode" -> hideAirplaneMode
        "hideProxy" -> hideProxy
        "hideWifiNetworks" -> hideWifiNetworks
        "hideBluetooth" -> hideBluetooth
        "spoofUptime" -> spoofUptime
        "spoofAppIdentity" -> spoofAppIdentity
        "spoofBrowserFingerprint" -> spoofBrowserFingerprint
        "spoofLocation" -> spoofLocation
        "spoofBuildProperties" -> spoofBuildProperties
        "nativePropertyHook" -> nativePropertyHook
        "preventNativeCrash" -> !nativePropertyHook
        else -> false
    }

    fun shouldSpoofBuild(): Boolean =
        spoofPartialDeviceId || spoofFullDeviceId || spoofBuildProperties

    fun normalized(): HookFeatures = copy(preventNativeCrash = !nativePropertyHook)

    fun toJson(): JSONObject = normalized().let { n ->
        JSONObject().apply {
            put(KEY_CONFIG_NAME, n.configName)
            put(KEY_HIDE_DEV_OPTIONS, n.hideDeveloperOptions)
            put(KEY_HIDE_VPN, n.hideVpn)
            put(KEY_SPOOF_INSTALL_PLAY, n.spoofInstallSourcePlay)
            put(KEY_SPOOF_WIFI, n.spoofWifiInfo)
            put(KEY_SPOOF_PARTIAL_ID, n.spoofPartialDeviceId)
            put(KEY_SPOOF_FULL_ID, n.spoofFullDeviceId)
            put(KEY_SIM_SIMULATION, n.simSimulation)
            put(KEY_BLOCK_LAN, n.blockLanScan)
            put(KEY_HIDE_LSPOSED, n.hideLsposed)
            put(KEY_HIDE_ROOT, n.hideRoot)
            put(KEY_HIDE_AIRPLANE, n.hideAirplaneMode)
            put(KEY_HIDE_PROXY, n.hideProxy)
            put(KEY_HIDE_WIFI_NET, n.hideWifiNetworks)
            put(KEY_HIDE_BT, n.hideBluetooth)
            put(KEY_SPOOF_UPTIME, n.spoofUptime)
            put(KEY_SPOOF_APP_ID, n.spoofAppIdentity)
            put(KEY_SPOOF_BROWSER_FP, n.spoofBrowserFingerprint)
            put(KEY_SPOOF_LOCATION, n.spoofLocation)
            put(KEY_SPOOF_BUILD_PROPS, n.spoofBuildProperties)
            put(KEY_NATIVE_HOOK, n.nativePropertyHook)
            put(KEY_PREVENT_NATIVE_CRASH, n.preventNativeCrash)
            put(KEY_DISABLED_SCOPE_FOUR_CHANNEL, JSONArray(n.disabledScopedFourChannel.toList()))
            put(KEY_DISABLED_SCOPE_NATIVE, JSONArray(n.disabledScopedNative.toList()))
        }
    }

    companion object {
        val DEFAULT = HookFeatures()

        private const val KEY_CONFIG_NAME = "configName"
        private const val KEY_HIDE_DEV_OPTIONS = "hideDeveloperOptions"
        private const val KEY_HIDE_VPN = "hideVpn"
        private const val KEY_SPOOF_INSTALL_PLAY = "spoofInstallSourcePlay"
        private const val KEY_SPOOF_WIFI = "spoofWifiInfo"
        private const val KEY_SPOOF_PARTIAL_ID = "spoofPartialDeviceId"
        private const val KEY_SPOOF_FULL_ID = "spoofFullDeviceId"
        private const val KEY_SIM_SIMULATION = "simSimulation"
        private const val KEY_BLOCK_LAN = "blockLanScan"
        private const val KEY_HIDE_LSPOSED = "hideLsposed"
        private const val KEY_HIDE_ROOT = "hideRoot"
        private const val KEY_HIDE_AIRPLANE = "hideAirplaneMode"
        private const val KEY_HIDE_PROXY = "hideProxy"
        private const val KEY_HIDE_WIFI_NET = "hideWifiNetworks"
        private const val KEY_HIDE_BT = "hideBluetooth"
        private const val KEY_SPOOF_UPTIME = "spoofUptime"
        private const val KEY_SPOOF_APP_ID = "spoofAppIdentity"
        private const val KEY_SPOOF_BROWSER_FP = "spoofBrowserFingerprint"
        private const val KEY_SPOOF_LOCATION = "spoofLocation"
        private const val KEY_SPOOF_BUILD_PROPS = "spoofBuildProperties"
        private const val KEY_NATIVE_HOOK = "nativePropertyHook"
        private const val KEY_PREVENT_NATIVE_CRASH = "preventNativeCrash"
        private const val KEY_DISABLED_SCOPE_FOUR_CHANNEL = "disabledScopedFourChannel"
        private const val KEY_DISABLED_SCOPE_NATIVE = "disabledScopedNative"

        private fun parseDisabledScope(arr: JSONArray?): Set<String> {
            if (arr == null) return emptySet()
            val out = linkedSetOf<String>()
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let(out::add)
            }
            return out
        }

        fun fromJson(obj: JSONObject?): HookFeatures {
            if (obj == null) return DEFAULT.normalized()
            val spoofBuild = obj.optBoolean(KEY_SPOOF_BUILD_PROPS, DEFAULT.spoofBuildProperties)
            val nativeHook = if (obj.has(KEY_NATIVE_HOOK)) {
                obj.optBoolean(KEY_NATIVE_HOOK, DEFAULT.nativePropertyHook)
            } else {
                spoofBuild
            }
            return HookFeatures(
                configName = obj.optString(KEY_CONFIG_NAME, DEFAULT.configName),
                hideDeveloperOptions = obj.optBoolean(KEY_HIDE_DEV_OPTIONS, DEFAULT.hideDeveloperOptions),
                hideVpn = obj.optBoolean(KEY_HIDE_VPN, DEFAULT.hideVpn),
                spoofInstallSourcePlay = obj.optBoolean(KEY_SPOOF_INSTALL_PLAY, DEFAULT.spoofInstallSourcePlay),
                spoofWifiInfo = obj.optBoolean(KEY_SPOOF_WIFI, DEFAULT.spoofWifiInfo),
                spoofPartialDeviceId = obj.optBoolean(KEY_SPOOF_PARTIAL_ID, DEFAULT.spoofPartialDeviceId),
                spoofFullDeviceId = obj.optBoolean(KEY_SPOOF_FULL_ID, DEFAULT.spoofFullDeviceId),
                simSimulation = obj.optBoolean(KEY_SIM_SIMULATION, DEFAULT.simSimulation),
                blockLanScan = obj.optBoolean(KEY_BLOCK_LAN, DEFAULT.blockLanScan),
                hideLsposed = obj.optBoolean(KEY_HIDE_LSPOSED, DEFAULT.hideLsposed),
                hideRoot = obj.optBoolean(KEY_HIDE_ROOT, DEFAULT.hideRoot),
                hideAirplaneMode = obj.optBoolean(KEY_HIDE_AIRPLANE, DEFAULT.hideAirplaneMode),
                hideProxy = obj.optBoolean(KEY_HIDE_PROXY, DEFAULT.hideProxy),
                hideWifiNetworks = obj.optBoolean(KEY_HIDE_WIFI_NET, DEFAULT.hideWifiNetworks),
                hideBluetooth = obj.optBoolean(KEY_HIDE_BT, DEFAULT.hideBluetooth),
                spoofUptime = obj.optBoolean(KEY_SPOOF_UPTIME, DEFAULT.spoofUptime),
                spoofAppIdentity = obj.optBoolean(KEY_SPOOF_APP_ID, DEFAULT.spoofAppIdentity),
                spoofBrowserFingerprint = obj.optBoolean(KEY_SPOOF_BROWSER_FP, DEFAULT.spoofBrowserFingerprint),
                spoofLocation = obj.optBoolean(KEY_SPOOF_LOCATION, DEFAULT.spoofLocation),
                spoofBuildProperties = spoofBuild,
                nativePropertyHook = nativeHook,
                preventNativeCrash = !nativeHook,
                disabledScopedFourChannel = parseDisabledScope(obj.optJSONArray(KEY_DISABLED_SCOPE_FOUR_CHANNEL)),
                disabledScopedNative = parseDisabledScope(obj.optJSONArray(KEY_DISABLED_SCOPE_NATIVE)),
            ).normalized()
        }

        fun experimentalCatalog(): List<HookFeatureItem> = listOf(
            HookFeatureItem(
                "blockLanScan",
                "阻止扫描局域网设备",
                "NetworkInterface 枚举过滤（当前机型 Hook 未生效）",
                "实验 / 未完善",
                implemented = false,
            ),
            HookFeatureItem(
                "spoofAppIdentity",
                "伪装应用身份信息",
                "仅清除 DEBUGGABLE 标记，完整 PackageManager 伪装待完善",
                "实验 / 未完善",
                implemented = false,
            ),
            HookFeatureItem(
                "spoofUptime",
                "伪装设备运行时间",
                "SystemClock 偏移，可能导致目标 App 异常",
                "实验 / 未完善",
                implemented = false,
            ),
        )

        fun isImplemented(key: String): Boolean =
            uiCatalog().any { it.key == key && it.implemented } ||
                experimentalCatalog().none { it.key == key }

        fun privacyCatalog(): List<HookFeatureItem> = uiCatalog().filter {
            it.key !in SECTION_GROUPED_KEYS && it.implemented
        }

        val SECTION_GROUPED_KEYS = setOf(
            "spoofPartialDeviceId",
            "simSimulation",
            "spoofFullDeviceId",
            "spoofLocation",
        )

        fun uiCatalog(): List<HookFeatureItem> = listOf(
            HookFeatureItem("hideDeveloperOptions", "隐藏开发者选项状态", "Settings ADB / 开发选项", "基础保护"),
            HookFeatureItem("hideVpn", "从应用隐藏活动 VPN", "HackChecker.isVPN", "基础保护"),
            HookFeatureItem("spoofInstallSourcePlay", "伪装安装来源为 Google Play", "InstallSourceInfo", "基础保护"),
            HookFeatureItem("spoofWifiInfo", "伪装 Wi-Fi 信息", "WifiManager / DHCP", "基础保护"),
            HookFeatureItem("spoofPartialDeviceId", "启用 Build 字段伪装", "android.os.Build 静态字段（见下方设备参数）", "基础保护"),
            HookFeatureItem("spoofFullDeviceId", "启用设备标识伪装", "IMEI / IMSI / 手机号 / Android ID（见下方设备标识）", "高级保护"),
            HookFeatureItem("simSimulation", "启用 SIM 卡伪装", "运营商 MCC/MNC、名称、国家（见下方 SIM 卡）", "高级保护"),
            HookFeatureItem("hideLsposed", "隐藏 LSPosed", "maps / 特征文件 / 模块列表", "高级保护"),
            HookFeatureItem("hideRoot", "隐藏 Root", "CheckEmu root 探测", "高级保护"),
            HookFeatureItem("hideAirplaneMode", "隐藏飞行模式状态", "Settings.Global", "高级保护"),
            HookFeatureItem("hideProxy", "隐藏活动代理连接", "System.getProperty 代理", "高级保护"),
            HookFeatureItem("hideWifiNetworks", "隐藏 Wi-Fi 网络列表", "getScanResults", "高级保护"),
            HookFeatureItem("hideBluetooth", "隐藏蓝牙设备", "BluetoothAdapter", "高级保护"),
            HookFeatureItem("spoofBrowserFingerprint", "更改浏览器指纹", "WebSettings User-Agent", "其他"),
            HookFeatureItem(
                "spoofLocation",
                "启用地理位置伪装",
                "LocationManager / GPS 坐标（见下方地理位置）",
                "其他",
            ),
            HookFeatureItem(
                "spoofBuildProperties",
                "属性伪装（Java 三通道）",
                "Build / getprop / SystemProperties；关闭后下方按 App 配置隐藏",
                "实现功能",
            ),
            HookFeatureItem(
                "nativePropertyHook",
                "Native 四通道",
                "JNI 属性拦截；部分 App 可能闪退，异常时请先关闭此项（Java 三通道仍可用）",
                "实现功能",
            ),
        )
    }
}

data class HookFeatureItem(
    val key: String,
    val title: String,
    val description: String,
    val section: String,
    val implemented: Boolean = true,
)
