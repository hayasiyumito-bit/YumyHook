package com.yumito.yumyhook.xposed.channel.strategy

import com.yumito.yumyhook.model.HookFeatures
import com.yumito.yumyhook.xposed.channel.strategy.profiles.BuiltinAppProfiles
import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.policy.FourChannelPolicy

object StrategyResolver {

    fun resolve(packageName: String?, features: HookFeatures): ResolvedChannelStrategy {
        val pkg = packageName.orEmpty()
        val base = if (pkg.isBlank()) BuiltinAppProfiles.DEFAULT else BuiltinAppProfiles.forPackage(pkg)
        val hookEnabled = HookConfig.isEnabledForHook()
        val fourChannel = hookEnabled && FourChannelPolicy.isEnabledFor(pkg, features)
        val nativeAllowed = fourChannel && features.isNativeEnabledFor(pkg) && base.nativeChannel
        val nativeMode = when {
            !nativeAllowed -> NativeInstallMode.DISABLED
            else -> base.nativeInstallMode
        }
        return ResolvedChannelStrategy(
            strategy = base,
            fourChannelActive = fourChannel,
            buildActive = fourChannel && base.buildChannel,
            systemPropertiesActive = fourChannel && base.systemPropertiesChannel,
            getpropActive = fourChannel && base.getpropChannel,
            nativeActive = nativeMode != NativeInstallMode.DISABLED,
            nativeInstallMode = nativeMode,
        )
    }
}
