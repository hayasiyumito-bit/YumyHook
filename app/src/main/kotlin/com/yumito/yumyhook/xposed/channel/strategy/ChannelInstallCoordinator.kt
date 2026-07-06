package com.yumito.yumyhook.xposed.channel.strategy

import android.app.Application
import com.yumito.yumyhook.xposed.channel.GetpropHook
import com.yumito.yumyhook.xposed.channel.HostShadowhookLoadGuard
import com.yumito.yumyhook.xposed.channel.NativeBridge
import com.yumito.yumyhook.xposed.channel.OsBuildHook
import com.yumito.yumyhook.xposed.channel.SystemPropertiesHook
import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.config.HookFeatureConfig
import com.yumito.yumyhook.xposed.runtime.SpoofRuntime
import com.yumito.yumyhook.xposed.runtime.TargetContextHolder
import com.yumito.yumyhook.xposed.stealth.FeatureStealthInstaller
import com.yumito.yumyhook.xposed.stealth.RegisterReceiverCompatHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 按 [ResolvedChannelStrategy] 分阶段安装四通道与 Stealth。
 * handleLoadPackage 只装桩；是否生效仍由 Gate 运行时判定。
 */
object ChannelInstallCoordinator {

    @Volatile
    private var stealthInstalled = false

    @Volatile
    private var nativeReady = false

    fun onLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, resolved: ResolvedChannelStrategy) {
        val pkg = lpparam.packageName
        TargetContextHolder.packageName = pkg
        val dataDir = lpparam.appInfo.dataDir.orEmpty()
        if (resolved.nativeInstallMode == NativeInstallMode.HOST_SHADOWHOOK_DEFERRED && dataDir.isNotBlank()) {
            HostShadowhookLoadGuard.bind(pkg, dataDir, lpparam.classLoader)
            HostShadowhookLoadGuard.schedulePostBindInstall()
            ChannelDiagLog.native(pkg, "deferred until host shadowhook lib")
        }
        if (resolved.strategy.registerReceiverCompat &&
            resolved.nativeInstallMode != NativeInstallMode.LOAD_PACKAGE &&
            resolved.nativeInstallMode != NativeInstallMode.HOST_SHADOWHOOK_DEFERRED
        ) {
            RegisterReceiverCompatHook.installIfNeeded()
            ChannelDiagLog.phase(pkg, InstallPhase.LOAD_PACKAGE, "registerReceiver compat")
        }
        installChannelStubs(lpparam)
        if (resolved.applyBuildAtPhase(InstallPhase.LOAD_PACKAGE)) {
            SpoofRuntime.applyChannelsAtPhase("loadPackage", resolved)
        }
        if (resolved.strategy.stealthInstallPhase == InstallPhase.LOAD_PACKAGE) {
            installStealth(lpparam, pkg, InstallPhase.LOAD_PACKAGE)
        }
        if (resolved.nativeInstallMode == NativeInstallMode.LOAD_PACKAGE) {
            ensureNativeEarly(lpparam, resolved)
        }
        ApplicationLifecycleScheduler.schedule(lpparam, resolved)
    }

    fun onApplicationAttach(
        lpparam: XC_LoadPackage.LoadPackageParam,
        app: Application,
        resolved: ResolvedChannelStrategy,
    ) {
        val pkg = lpparam.packageName
        TargetContextHolder.bind(app)
        if (resolved.applyBuildAtPhase(InstallPhase.APPLICATION_ATTACH)) {
            SpoofRuntime.applyChannelsAtPhase("attach", resolved)
        }
        if (resolved.strategy.stealthInstallPhase == InstallPhase.APPLICATION_ATTACH) {
            installStealth(lpparam, pkg, InstallPhase.APPLICATION_ATTACH)
        }
        if (resolved.nativeInstallMode == NativeInstallMode.APPLICATION_ATTACH) {
            ensureNative(lpparam, app, resolved)
        }
    }

    fun onApplicationCreate(
        lpparam: XC_LoadPackage.LoadPackageParam,
        app: Application,
        resolved: ResolvedChannelStrategy,
    ) {
        val pkg = lpparam.packageName
        if (resolved.applyBuildAtPhase(InstallPhase.APPLICATION_ON_CREATE)) {
            SpoofRuntime.applyChannelsAtPhase("onCreate", resolved)
        }
        if (resolved.strategy.stealthInstallPhase == InstallPhase.APPLICATION_ON_CREATE) {
            installStealth(lpparam, pkg, InstallPhase.APPLICATION_ON_CREATE)
        }
        if (resolved.strategy.registerReceiverCompat) {
            RegisterReceiverCompatHook.installIfNeeded()
            ChannelDiagLog.phase(pkg, InstallPhase.APPLICATION_ON_CREATE, "registerReceiver compat")
        }
        if (resolved.nativeInstallMode == NativeInstallMode.APPLICATION_ON_CREATE) {
            ensureNative(lpparam, app, resolved)
        }
        if (resolved.nativeInstallMode == NativeInstallMode.HOST_SHADOWHOOK_DEFERRED) {
            ensureNativeDeferred(pkg, resolved)
        }
    }

    private fun installChannelStubs(lpparam: XC_LoadPackage.LoadPackageParam) {
        OsBuildHook.install(lpparam)
        SystemPropertiesHook.install(lpparam)
        GetpropHook.install(lpparam)
        NativeBridge.syncFromGate(HookConfig.valuesForHook(), lpparam.packageName)
    }

    private fun installStealth(lpparam: XC_LoadPackage.LoadPackageParam, pkg: String, phase: InstallPhase) {
        if (stealthInstalled) return
        synchronized(this) {
            if (stealthInstalled) return
            HookFeatureConfig.refreshIfStale()
            FeatureStealthInstaller.install(lpparam)
            stealthInstalled = true
            ChannelDiagLog.phase(pkg, phase, "stealth installed")
        }
    }

    private fun ensureNativeDeferred(pkg: String, resolved: ResolvedChannelStrategy) {
        if (!resolved.nativeActive) return
        if (nativeReady) return
        if (HostShadowhookLoadGuard.tryInstallFromMaps("onCreate")) {
            nativeReady = true
        }
    }

    private fun ensureNativeEarly(
        lpparam: XC_LoadPackage.LoadPackageParam,
        resolved: ResolvedChannelStrategy,
    ) {
        val pkg = lpparam.packageName
        HookConfig.refreshHookCacheIfStale()
        if (!resolved.nativeActive) {
            NativeBridge.syncFromGate(HookConfig.valuesForHook(), pkg)
            ChannelDiagLog.native(pkg, "early inactive sync only")
            return
        }
        if (nativeReady) {
            NativeBridge.syncFromGate(HookConfig.valuesForHook(), pkg)
            return
        }
        synchronized(this) {
            if (nativeReady) {
                NativeBridge.syncFromGate(HookConfig.valuesForHook(), pkg)
                return
            }
            try {
                if (!NativeBridge.installEarly(lpparam)) {
                    ChannelDiagLog.native(pkg, "early failed")
                    return
                }
                NativeBridge.retryDeferredHooks(pkg)
                nativeReady = true
                ChannelDiagLog.native(pkg, "early ready mode=LOAD_PACKAGE")
            } catch (e: Throwable) {
                ChannelDiagLog.native(pkg, "early failed ${e.message}")
            }
        }
    }

    private fun ensureNative(
        lpparam: XC_LoadPackage.LoadPackageParam,
        app: Application,
        resolved: ResolvedChannelStrategy,
    ) {
        val pkg = lpparam.packageName
        HookConfig.refreshHookCacheIfStale()
        if (!resolved.nativeActive) {
            NativeBridge.syncFromGate(HookConfig.valuesForHook(), pkg)
            ChannelDiagLog.native(pkg, "inactive sync only")
            return
        }
        if (nativeReady) {
            NativeBridge.syncProperties(HookConfig.valuesForHook(), app)
            return
        }
        synchronized(this) {
            if (nativeReady) {
                NativeBridge.syncProperties(HookConfig.valuesForHook(), app)
                return
            }
            try {
                NativeBridge.install(lpparam, app)
                NativeBridge.retryDeferredHooks(pkg)
                nativeReady = true
                ChannelDiagLog.native(pkg, "ready mode=${resolved.nativeInstallMode}")
            } catch (e: Throwable) {
                ChannelDiagLog.native(pkg, "failed ${e.message}")
            }
        }
    }

    private fun ResolvedChannelStrategy.applyBuildAtPhase(phase: InstallPhase): Boolean =
        fourChannelActive && strategy.applyBuildAtPhase == phase
}
