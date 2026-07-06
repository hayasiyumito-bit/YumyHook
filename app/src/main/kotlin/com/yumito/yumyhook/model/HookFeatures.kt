package com.yumito.yumyhook.model

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
    /** 四通道属性对齐主开关：Build + getprop + SystemProperties + JNI Native */
    val spoofBuildProperties: Boolean = true,
    val nativePropertyHook: Boolean = true,
    val preventNativeCrash: Boolean = false,
) {
    fun shouldInstallNative(): Boolean = spoofBuildProperties

    fun shouldSpoofBuild(): Boolean =
        spoofPartialDeviceId || spoofFullDeviceId || spoofBuildProperties

    fun normalized(): HookFeatures = copy(
        nativePropertyHook = spoofBuildProperties,
        preventNativeCrash = !spoofBuildProperties,
    )

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
            put(KEY_SPOOF_BUILD_PROPS, n.spoofBuildProperties)
            put(KEY_NATIVE_HOOK, n.nativePropertyHook)
            put(KEY_PREVENT_NATIVE_CRASH, n.preventNativeCrash)
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
        private const val KEY_SPOOF_BUILD_PROPS = "spoofBuildProperties"
        private const val KEY_NATIVE_HOOK = "nativePropertyHook"
        private const val KEY_PREVENT_NATIVE_CRASH = "preventNativeCrash"

        fun fromJson(obj: JSONObject?): HookFeatures {
            if (obj == null) return DEFAULT.normalized()
            val spoofBuild = obj.optBoolean(KEY_SPOOF_BUILD_PROPS, DEFAULT.spoofBuildProperties)
            val native = obj.optBoolean(KEY_NATIVE_HOOK, false)
            val prevent = obj.optBoolean(KEY_PREVENT_NATIVE_CRASH, true)
            val aligned = spoofBuild || (native && !prevent)
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
                spoofBuildProperties = aligned,
                nativePropertyHook = aligned,
                preventNativeCrash = !aligned,
            ).normalized()
        }

        fun experimentalCatalog(): List<HookFeatureItem> = listOf(
            HookFeatureItem(
                "spoofUptime",
                "伪装设备运行时间",
                "实验性，可能导致目标 App 异常",
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
        )

        fun uiCatalog(): List<HookFeatureItem> = listOf(
            HookFeatureItem("hideDeveloperOptions", "隐藏开发者选项状态", "Settings ADB / 开发选项", "基础保护"),
            HookFeatureItem("hideVpn", "从应用隐藏活动 VPN", "HackChecker.isVPN", "基础保护"),
            HookFeatureItem("spoofInstallSourcePlay", "伪装安装来源为 Google Play", "InstallSourceInfo", "基础保护"),
            HookFeatureItem("spoofWifiInfo", "伪装 Wi-Fi 信息", "WifiManager / DHCP", "基础保护"),
            HookFeatureItem("spoofPartialDeviceId", "模拟部分设备标识", "Build 静态字段", "基础保护"),
            HookFeatureItem("spoofFullDeviceId", "模拟全部设备标识", "IDs / Telephony", "高级保护"),
            HookFeatureItem("simSimulation", "SIM 模拟", "TelephonyManager / SimCard", "高级保护"),
            HookFeatureItem("blockLanScan", "阻止扫描局域网设备", "NetworkInterface / Wi-Fi 扫描", "高级保护"),
            HookFeatureItem("hideLsposed", "隐藏 LSPosed", "maps / 特征文件 / 模块列表", "高级保护"),
            HookFeatureItem("hideRoot", "隐藏 Root", "CheckEmu root 探测", "高级保护"),
            HookFeatureItem("hideAirplaneMode", "隐藏飞行模式状态", "Settings.Global", "高级保护"),
            HookFeatureItem("hideProxy", "隐藏活动代理连接", "System.getProperty 代理", "高级保护"),
            HookFeatureItem("hideWifiNetworks", "隐藏 Wi-Fi 网络列表", "getScanResults", "高级保护"),
            HookFeatureItem("hideBluetooth", "隐藏蓝牙设备", "BluetoothAdapter", "高级保护"),
            HookFeatureItem("spoofUptime", "伪装设备运行时间", "实验性，可能导致异常", "其他", implemented = false),
            HookFeatureItem("spoofAppIdentity", "伪装应用身份信息", "PackageManager", "其他"),
            HookFeatureItem("spoofBrowserFingerprint", "更改浏览器指纹", "WebSettings User-Agent", "其他"),
            HookFeatureItem(
                "spoofBuildProperties",
                "四通道属性对齐",
                "Build + getprop + SystemProperties + JNI（QQ 等自带 shadowhook 的 App 自动跳过 JNI）",
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
