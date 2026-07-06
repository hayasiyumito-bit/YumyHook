package com.yumito.yumyhook.xposed.channel.strategy

import android.app.Application
import com.yumito.yumyhook.xposed.config.HookFeatureConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** 按策略在 Application.attach / onCreate 触发 ChannelInstallCoordinator。 */
object ApplicationLifecycleScheduler {

    fun schedule(lpparam: XC_LoadPackage.LoadPackageParam, initial: ResolvedChannelStrategy) {
        val needAttach = initial.strategy.hookApplicationAttach ||
            initial.nativeInstallMode == NativeInstallMode.APPLICATION_ATTACH ||
            initial.strategy.stealthInstallPhase == InstallPhase.APPLICATION_ATTACH ||
            initial.strategy.applyBuildAtPhase == InstallPhase.APPLICATION_ATTACH

        val needOnCreate = initial.nativeInstallMode == NativeInstallMode.APPLICATION_ON_CREATE ||
            initial.strategy.stealthInstallPhase == InstallPhase.APPLICATION_ON_CREATE ||
            initial.strategy.applyBuildAtPhase == InstallPhase.APPLICATION_ON_CREATE ||
            initial.strategy.channelStubInstallPhase == InstallPhase.APPLICATION_ON_CREATE ||
            !initial.strategy.hookApplicationAttach

        if (needAttach) {
            hookAttach(lpparam)
        }
        if (needOnCreate) {
            hookOnCreate(lpparam)
        }
    }

    private fun hookAttach(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                android.content.Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as Application
                        val resolved = StrategyResolver.resolve(
                            lpparam.packageName,
                            HookFeatureConfig.refreshIfStale(),
                        )
                        ChannelInstallCoordinator.onApplicationAttach(lpparam, app, resolved)
                    }
                },
            )
        } catch (e: Throwable) {
            ChannelDiagLog.skip(lpparam.packageName, "attach hook: ${e.message}")
        }
    }

    private fun hookOnCreate(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as Application
                        val resolved = StrategyResolver.resolve(
                            lpparam.packageName,
                            HookFeatureConfig.refreshIfStale(),
                        )
                        ChannelInstallCoordinator.onApplicationCreate(lpparam, app, resolved)
                    }
                },
            )
        } catch (e: Throwable) {
            ChannelDiagLog.skip(lpparam.packageName, "onCreate hook: ${e.message}")
        }
    }
}
