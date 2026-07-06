package com.yumito.yumyhook.xposed.channel

import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.yumito.yumyhook.ProjectAttribution
import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.config.HookFeatureConfig
import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.channel.strategy.ChannelDiagLog
import com.yumito.yumyhook.xposed.channel.strategy.ChannelInstallCoordinator
import com.yumito.yumyhook.xposed.channel.strategy.StrategyResolver
import com.yumito.yumyhook.xposed.policy.HookScope

/** 系统层 Hook 统一安装入口（不 Hook 任何目标 App 业务类）。 */
object SystemHookInstaller {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        HookConfig.refreshHookCache()
        val features = HookFeatureConfig.refresh()
        val resolved = StrategyResolver.resolve(lpparam.packageName, features)
        ChannelDiagLog.strategy(lpparam.packageName, resolved)
        de.robv.android.xposed.XposedBridge.log(
            "${XposedConstants.TAG}: hooks rev=${XposedConstants.HOOK_REV} pkg=${lpparam.packageName} " +
                "lineage=${ProjectAttribution.LINEAGE_FINGERPRINT}",
        )
        ChannelInstallCoordinator.onLoadPackage(lpparam, resolved)
    }

    fun shouldHook(packageName: String): Boolean = HookScope.shouldHook(packageName)
}
