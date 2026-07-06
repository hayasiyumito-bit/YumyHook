package com.yumito.yumyhook.xposed.channel.strategy

/**
 * 单 App 四通道注入策略（内置档案 + 用户按包开关合并后生效）。
 *
 * @param profileId 日志标识
 * @param hookApplicationAttach 是否 hook Application.attach；部分 App attach 路径极脆
 * @param registerReceiverCompat API33+ 为无 flag 的 registerReceiver 补 RECEIVER_NOT_EXPORTED
 */
data class FourChannelStrategy(
    val profileId: String = "default",
    val buildChannel: Boolean = true,
    val systemPropertiesChannel: Boolean = true,
    val getpropChannel: Boolean = true,
    val nativeChannel: Boolean = true,
    val nativeInstallMode: NativeInstallMode = NativeInstallMode.APPLICATION_ATTACH,
    val skipNativeIfHostShadowhook: Boolean = true,
    val applyBuildAtPhase: InstallPhase = InstallPhase.LOAD_PACKAGE,
    val stealthInstallPhase: InstallPhase = InstallPhase.LOAD_PACKAGE,
    val registerReceiverCompat: Boolean = false,
    val hookApplicationAttach: Boolean = true,
)

/** 合并用户开关后的有效策略。 */
data class ResolvedChannelStrategy(
    val strategy: FourChannelStrategy,
    val fourChannelActive: Boolean,
    val buildActive: Boolean,
    val systemPropertiesActive: Boolean,
    val getpropActive: Boolean,
    val nativeActive: Boolean,
    val nativeInstallMode: NativeInstallMode,
) {
    fun summary(): String =
        "profile=${strategy.profileId} four=$fourChannelActive " +
            "b=$buildActive sp=$systemPropertiesActive gp=$getpropActive " +
            "native=$nativeActive mode=$nativeInstallMode " +
            "stealth@${strategy.stealthInstallPhase} build@${strategy.applyBuildAtPhase} " +
            "compat=${strategy.registerReceiverCompat} attachHook=${strategy.hookApplicationAttach}"
}
