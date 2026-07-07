package com.yumito.yumyhook.xposed.channel.strategy.profiles

import com.yumito.yumyhook.xposed.channel.strategy.FourChannelStrategy
import com.yumito.yumyhook.xposed.channel.strategy.InstallPhase
import com.yumito.yumyhook.xposed.channel.strategy.NativeInstallMode
import com.yumito.yumyhook.xposed.config.XposedConstants

/**
 * 已知 App 四通道策略档案（安装时机 / 日志 profileId）。
 * 自带 shadowhook 的 App：Native 在宿主 crash 库加载后注入（见 HostShadowhookLoadGuard）。
 */
object BuiltinAppProfiles {

    /**
     * 默认策略（全作用域通用）：
     * - Stealth / registerReceiver compat 延后到 onCreate，避免 ContentProvider 启动期干扰
     * - Native 延到 attach；未知宿主 shadowhook 时 maps 竞态兜底
     */
    val DEFAULT = FourChannelStrategy(
        profileId = "default",
        stealthInstallPhase = InstallPhase.APPLICATION_ON_CREATE,
        registerReceiverCompat = true,
        hookApplicationAttach = false,
        skipNativeIfHostShadowhook = true,
    )

    /** 宿主 libpairipcore 等：LOAD_PACKAGE 装 SystemProperties 桩会触发 SIGSEGV。 */
    private fun integrityDeferredProfile(profileId: String): FourChannelStrategy =
        DEFAULT.copy(
            profileId = profileId,
            deferInstallUntilBindComplete = true,
            channelStubInstallPhase = InstallPhase.APPLICATION_ON_CREATE,
            applyBuildAtPhase = InstallPhase.APPLICATION_ON_CREATE,
            nativeInstallMode = NativeInstallMode.APPLICATION_ON_CREATE,
        )

    /** 微信 / QQ 等：等宿主 libwechatcrash 加载后复用 shadowhook 装 property hook。 */
    private fun shadowhookProfile(profileId: String): FourChannelStrategy =
        DEFAULT.copy(
            profileId = profileId,
            nativeInstallMode = NativeInstallMode.HOST_SHADOWHOOK_DEFERRED,
            // attachBaseContext 前不改 Build，避免 libwechatcrash JNI_OnLoad 自检失败
            applyBuildAtPhase = InstallPhase.APPLICATION_ON_CREATE,
        )

    private val PROFILES: Map<String, FourChannelStrategy> = mapOf(
        XposedConstants.TARGET_PACKAGE_DINGTALK to shadowhookProfile("dingtalk"),
        XposedConstants.TARGET_PACKAGE_WECHAT to shadowhookProfile("wechat"),
        XposedConstants.TARGET_PACKAGE_QQ to shadowhookProfile("qq"),
        XposedConstants.TARGET_PACKAGE_TIM to shadowhookProfile("tim"),
        XposedConstants.TARGET_PACKAGE_ALIPAY to shadowhookProfile("alipay"),
        XposedConstants.TARGET_PACKAGE_TWITTER to integrityDeferredProfile("twitter"),
        XposedConstants.TARGET_PACKAGE_DEVICE to DEFAULT.copy(
            profileId = "deviceinfo",
            applyBuildAtPhase = InstallPhase.LOAD_PACKAGE,
            stealthInstallPhase = InstallPhase.APPLICATION_ATTACH,
            hookApplicationAttach = true,
            nativeInstallMode = NativeInstallMode.LOAD_PACKAGE,
        ),
    )

    fun forPackage(packageName: String): FourChannelStrategy =
        PROFILES[packageName] ?: DEFAULT

    fun knownProfileIds(): Set<String> =
        PROFILES.values.map { it.profileId }.toSet() + DEFAULT.profileId
}
