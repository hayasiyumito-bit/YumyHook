package com.yumito.yumyhook.xposed.channel

import de.robv.android.xposed.callbacks.XC_LoadPackage
import de.robv.android.xposed.XposedBridge
import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.channel.strategy.ChannelInstallCoordinator
import com.yumito.yumyhook.xposed.channel.strategy.StrategyResolver
import com.yumito.yumyhook.xposed.policy.HookScope
import com.yumito.yumyhook.xposed.stealth.install.FrameworkStealthInstaller

/** 系统层 Hook 统一安装入口。 */
object SystemHookInstaller {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        val values = HookConfig.refresh(pkg)
        val enabled = HookConfig.isEnabledForHook()
        
        if (HookScope.isFrameworkHookTarget(pkg)) {
            XposedBridge.log("${XposedConstants.TAG}: framework stealth rev=${XposedConstants.HOOK_REV} pkg=$pkg enabled=$enabled")
            FrameworkStealthInstaller.install(lpparam)
            return
        }
        
        val resolved = StrategyResolver.resolve(pkg, HookConfig.features())
        XposedBridge.log("${XposedConstants.TAG}: hook rev=${XposedConstants.HOOK_REV} pkg=$pkg enabled=$enabled profile=${values.profileLabel} build=${values.buildFields.size} active=${resolved.fourChannelActive}")

        ChannelInstallCoordinator.onLoadPackage(lpparam, resolved)
    }

    fun shouldHook(packageName: String): Boolean = HookScope.shouldHook(packageName)
}
