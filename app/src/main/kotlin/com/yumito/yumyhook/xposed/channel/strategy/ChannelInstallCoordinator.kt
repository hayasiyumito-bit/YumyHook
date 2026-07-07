package com.yumito.yumyhook.xposed.channel.strategy

import android.app.Application
import com.yumito.yumyhook.xposed.channel.NativeLoadGuard
import com.yumito.yumyhook.xposed.channel.HostShadowhookLoadGuard
import com.yumito.yumyhook.xposed.channel.NativeBridge
import com.yumito.yumyhook.xposed.channel.build.OsBuildHook
import com.yumito.yumyhook.xposed.channel.getprop.GetpropHook
import com.yumito.yumyhook.xposed.channel.systemproperty.SystemPropertiesHook
import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.config.HookFeatureConfig
import com.yumito.yumyhook.xposed.runtime.SpoofRuntime
import com.yumito.yumyhook.xposed.runtime.TargetContextHolder
import com.yumito.yumyhook.xposed.stealth.hide.NativeStealthBridge
import com.yumito.yumyhook.xposed.stealth.install.FeatureStealthInstaller
import com.yumito.yumyhook.xposed.stealth.install.RegisterReceiverCompatHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 按 [ResolvedChannelStrategy] 分阶段安装四通道与 Stealth。
 * handleLoadPackage 只装桩；是否生效仍由 Gate 运行时判定。
 */
object ChannelInstallCoordinator {

    @Volatile
    private var channelStubsInstalled = false

    @Volatile
    private var stealthInstalled = false

    @Volatile
    private var nativeReady = false

    fun onLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, resolved: ResolvedChannelStrategy) {
        val pkg = lpparam.packageName
        TargetContextHolder.packageName = pkg
        if (resolved.strategy.deferInstallUntilBindComplete) {
            IntegrityDelayedInstaller.schedule(lpparam)
            return
        }
        val dataDir = lpparam.appInfo.dataDir.orEmpty()
        if (resolved.nativeInstallMode == NativeInstallMode.HOST_SHADOWHOOK_DEFERRED && dataDir.isNotBlank()) {
            HostShadowhookLoadGuard.bind(pkg, dataDir, lpparam.classLoader)
            HostShadowhookLoadGuard.schedulePostBindInstall()
            ChannelDiagLog.native(pkg, "deferred until host shadowhook lib")
        }
        if (shouldInstallCompatAt(resolved, InstallPhase.LOAD_PACKAGE)) {
            RegisterReceiverCompatHook.installIfNeeded()
            ChannelDiagLog.phase(pkg, InstallPhase.LOAD_PACKAGE, "registerReceiver compat")
        }
        if (resolved.nativeActive && resolved.nativeInstallMode == NativeInstallMode.LOAD_PACKAGE) {
            NativeLoadGuard.install(lpparam)
        }
        ensureChannelStubs(lpparam, resolved, InstallPhase.LOAD_PACKAGE)
        if (resolved.applyBuildAtPhase(InstallPhase.LOAD_PACKAGE)) {
            SpoofRuntime.applyChannelsAtPhase("loadPackage", InstallPhase.LOAD_PACKAGE, resolved.fourChannelActive)
        }
        if (resolved.nativeInstallMode == NativeInstallMode.LOAD_PACKAGE) {
            ensureNativeEarly(lpparam, resolved)
        }
        if (resolved.strategy.stealthInstallPhase == InstallPhase.LOAD_PACKAGE) {
            finalizeNativeStealth(lpparam, resolved, InstallPhase.LOAD_PACKAGE)
            installStealth(lpparam, pkg, InstallPhase.LOAD_PACKAGE)
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
        if (!nativeReady && resolved.nativeActive && resolved.nativeInstallMode == NativeInstallMode.LOAD_PACKAGE) {
            if (!NativeBridge.retryInstallWithCaller(lpparam, app.javaClass)) {
                retryNativeEarlyIfNeeded(lpparam, resolved)
            } else {
                nativeReady = true
            }
        }
        ensureChannelStubs(lpparam, resolved, InstallPhase.APPLICATION_ATTACH)
        if (resolved.applyBuildAtPhase(InstallPhase.APPLICATION_ATTACH)) {
            SpoofRuntime.applyChannelsAtPhase("attach", InstallPhase.APPLICATION_ATTACH, resolved.fourChannelActive)
        }
        if (resolved.strategy.stealthInstallPhase == InstallPhase.APPLICATION_ATTACH) {
            installStealth(lpparam, pkg, InstallPhase.APPLICATION_ATTACH)
        }
        if (resolved.nativeInstallMode == NativeInstallMode.LOAD_PACKAGE) {
            retryNativeEarlyIfNeeded(lpparam, resolved)
        }
        if (resolved.nativeInstallMode == NativeInstallMode.APPLICATION_ATTACH) {
            ensureNative(lpparam, app, resolved)
        }
        finalizeNativeStealth(lpparam, resolved, InstallPhase.APPLICATION_ATTACH)
    }

    fun onDeferredWithoutApplication(
        lpparam: XC_LoadPackage.LoadPackageParam,
        resolved: ResolvedChannelStrategy,
    ) {
        ensureChannelStubs(lpparam, resolved, InstallPhase.APPLICATION_ON_CREATE)
        if (resolved.applyBuildAtPhase(InstallPhase.APPLICATION_ON_CREATE)) {
            SpoofRuntime.applyChannelsAtPhase(
                "integrityDelayed",
                InstallPhase.APPLICATION_ON_CREATE,
                resolved.fourChannelActive,
            )
        }
        if (resolved.strategy.stealthInstallPhase == InstallPhase.APPLICATION_ON_CREATE) {
            installStealth(lpparam, lpparam.packageName, InstallPhase.APPLICATION_ON_CREATE)
        }
        finalizeNativeStealth(lpparam, resolved, InstallPhase.APPLICATION_ON_CREATE)
    }

    fun onApplicationCreate(
        lpparam: XC_LoadPackage.LoadPackageParam,
        app: Application,
        resolved: ResolvedChannelStrategy,
    ) {
        val pkg = lpparam.packageName
        ensureChannelStubs(lpparam, resolved, InstallPhase.APPLICATION_ON_CREATE)
        if (resolved.applyBuildAtPhase(InstallPhase.APPLICATION_ON_CREATE)) {
            SpoofRuntime.applyChannelsAtPhase("onCreate", InstallPhase.APPLICATION_ON_CREATE, resolved.fourChannelActive)
        }
        if (resolved.strategy.stealthInstallPhase == InstallPhase.APPLICATION_ON_CREATE) {
            installStealth(lpparam, pkg, InstallPhase.APPLICATION_ON_CREATE)
        }
        if (shouldInstallCompatAt(resolved, InstallPhase.APPLICATION_ON_CREATE)) {
            RegisterReceiverCompatHook.installIfNeeded()
            ChannelDiagLog.phase(pkg, InstallPhase.APPLICATION_ON_CREATE, "registerReceiver compat")
        }
        if (resolved.nativeInstallMode == NativeInstallMode.APPLICATION_ON_CREATE) {
            ensureNative(lpparam, app, resolved)
        }
        if (resolved.nativeInstallMode == NativeInstallMode.LOAD_PACKAGE) {
            retryNativeEarlyIfNeeded(lpparam, resolved)
        }
        if (resolved.nativeInstallMode == NativeInstallMode.HOST_SHADOWHOOK_DEFERRED) {
            ensureNativeDeferred(pkg, resolved)
        }
        finalizeNativeStealth(lpparam, resolved, InstallPhase.APPLICATION_ON_CREATE)
    }

    fun ensureStealthInstalled(lpparam: XC_LoadPackage.LoadPackageParam, phase: InstallPhase) {
        installStealth(lpparam, lpparam.packageName, phase)
        finalizeNativeStealth(lpparam, StrategyResolver.resolve(lpparam.packageName, HookFeatureConfig.current()), phase)
    }

    private fun finalizeNativeStealth(
        lpparam: XC_LoadPackage.LoadPackageParam,
        resolved: ResolvedChannelStrategy,
        phase: InstallPhase,
    ) {
        if (!shouldInstallNativeStealthAt(resolved, phase)) return
        if (resolved.nativeInstallMode == NativeInstallMode.HOST_SHADOWHOOK_DEFERRED &&
            !NativeBridge.isHooksInstalled()
        ) {
            return
        }
        NativeStealthBridge.install(lpparam)
    }

    private fun shouldInstallNativeStealthAt(
        resolved: ResolvedChannelStrategy,
        phase: InstallPhase,
    ): Boolean = phase.ordinal >= resolved.strategy.stealthInstallPhase.ordinal

    private fun shouldInstallCompatAt(resolved: ResolvedChannelStrategy, phase: InstallPhase): Boolean {
        if (!resolved.strategy.registerReceiverCompat) return false
        if (resolved.strategy.channelStubInstallPhase == phase) return true
        if (resolved.strategy.stealthInstallPhase == phase &&
            resolved.strategy.channelStubInstallPhase != InstallPhase.LOAD_PACKAGE
        ) {
            return true
        }
        return false
    }

    private fun ensureChannelStubs(
        lpparam: XC_LoadPackage.LoadPackageParam,
        resolved: ResolvedChannelStrategy,
        phase: InstallPhase,
    ) {
        if (channelStubsInstalled) return
        if (resolved.strategy.channelStubInstallPhase != phase) return
        synchronized(this) {
            if (channelStubsInstalled) return
            installChannelStubs(lpparam)
            channelStubsInstalled = true
            ChannelDiagLog.phase(lpparam.packageName, phase, "channel stubs installed")
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

    private fun retryNativeEarlyIfNeeded(
        lpparam: XC_LoadPackage.LoadPackageParam,
        resolved: ResolvedChannelStrategy,
    ) {
        if (nativeReady || !resolved.nativeActive) return
        ensureNativeEarly(lpparam, resolved)
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
