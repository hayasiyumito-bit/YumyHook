package com.yumito.yumyhook.xposed.channel.strategy

import android.app.Application
import com.yumito.yumyhook.xposed.channel.NativeBridge
import com.yumito.yumyhook.xposed.channel.NativeLibLoader
import com.yumito.yumyhook.xposed.channel.build.OsBuildHook
import com.yumito.yumyhook.xposed.channel.getprop.GetpropHook
import com.yumito.yumyhook.xposed.channel.systemproperty.SystemPropertiesHook
import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.runtime.SpoofRuntime
import com.yumito.yumyhook.xposed.runtime.TargetContextHolder
import com.yumito.yumyhook.xposed.stealth.hide.NativeStealthBridge
import com.yumito.yumyhook.xposed.stealth.install.FeatureStealthInstaller
import com.yumito.yumyhook.xposed.stealth.install.RegisterReceiverCompatHook
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** 分阶段安装四通道与 Stealth； handleLoadPackage 只装桩。 */
object ChannelInstallCoordinator {

    @Volatile private var channelStubsInstalled = false
    @Volatile private var stealthInstalled = false
    @Volatile private var nativeReady = false
    @Volatile private var integrityStarted = false

    private fun log(pkg: String, msg: String) = XposedBridge.log("${XposedConstants.TAG}: $pkg $msg")

    fun onLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, resolved: ResolvedChannelStrategy) {
        val pkg = lpparam.packageName
        TargetContextHolder.packageName = pkg
        if (resolved.strategy.deferInstallUntilBindComplete) { scheduleIntegrity(lpparam); return }
        
        if (resolved.nativeActive) NativeBridge.installLoadWatcher(lpparam)
        if (shouldCompat(resolved, InstallPhase.LOAD_PACKAGE)) RegisterReceiverCompatHook.installIfNeeded()
        
        ensureChannelStubs(lpparam, resolved, InstallPhase.LOAD_PACKAGE)
        
        if (resolved.applyBuild(InstallPhase.LOAD_PACKAGE)) {
            SpoofRuntime.applyChannelsAtPhase("LP", InstallPhase.LOAD_PACKAGE, true)
        }
        
        if (resolved.nativeInstallMode == NativeInstallMode.LOAD_PACKAGE) {
            ensureNativeEarly(lpparam, resolved)
        }
        
        if (resolved.strategy.stealthInstallPhase == InstallPhase.LOAD_PACKAGE) {
            finalizeStealth(lpparam, resolved, InstallPhase.LOAD_PACKAGE)
            installStealth(lpparam, pkg, InstallPhase.LOAD_PACKAGE)
        }
        scheduleLifecycle(lpparam, resolved)
    }

    fun onApplicationAttach(lpparam: XC_LoadPackage.LoadPackageParam, app: Application, resolved: ResolvedChannelStrategy) {
        val pkg = lpparam.packageName
        TargetContextHolder.bind(app)
        
        if (!nativeReady && resolved.nativeActive) {
            if (NativeBridge.retryInstallWithCaller(lpparam, app.javaClass)) nativeReady = true
        }
        
        ensureChannelStubs(lpparam, resolved, InstallPhase.APPLICATION_ATTACH)
        
        if (resolved.applyBuild(InstallPhase.APPLICATION_ATTACH)) {
            SpoofRuntime.applyChannelsAtPhase("attach", InstallPhase.APPLICATION_ATTACH, true)
        }
        
        if (resolved.strategy.stealthInstallPhase == InstallPhase.APPLICATION_ATTACH) {
            installStealth(lpparam, pkg, InstallPhase.APPLICATION_ATTACH)
        }
        
        if (!nativeReady && resolved.nativeInstallMode == NativeInstallMode.APPLICATION_ATTACH) {
            ensureNative(lpparam, app, resolved)
        }
        finalizeStealth(lpparam, resolved, InstallPhase.APPLICATION_ATTACH)
    }

    fun onApplicationCreate(lpparam: XC_LoadPackage.LoadPackageParam, app: Application, resolved: ResolvedChannelStrategy) {
        val pkg = lpparam.packageName
        ensureChannelStubs(lpparam, resolved, InstallPhase.APPLICATION_ON_CREATE)
        
        if (resolved.applyBuild(InstallPhase.APPLICATION_ON_CREATE)) {
            SpoofRuntime.applyChannelsAtPhase("onCreate", InstallPhase.APPLICATION_ON_CREATE, true)
        }
        
        if (resolved.strategy.stealthInstallPhase == InstallPhase.APPLICATION_ON_CREATE) {
            installStealth(lpparam, pkg, InstallPhase.APPLICATION_ON_CREATE)
        }
        
        if (shouldCompat(resolved, InstallPhase.APPLICATION_ON_CREATE)) RegisterReceiverCompatHook.installIfNeeded()
        
        if (!nativeReady && resolved.nativeActive) {
            if (resolved.nativeInstallMode == NativeInstallMode.APPLICATION_ON_CREATE) {
                ensureNative(lpparam, app, resolved)
            } else if (NativeLibLoader.isHostNativeReady() || resolved.nativeInstallMode == NativeInstallMode.LOAD_PACKAGE) {
                if (NativeBridge.retryInstallWithCaller(lpparam, app.javaClass)) nativeReady = true
            }
        }
        finalizeStealth(lpparam, resolved, InstallPhase.APPLICATION_ON_CREATE)
    }

    private fun scheduleLifecycle(lpparam: XC_LoadPackage.LoadPackageParam, initial: ResolvedChannelStrategy) {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val pkg = lpparam.packageName
                HookConfig.refreshIfStale()
                val res = StrategyResolver.resolve(pkg, HookConfig.features())
                val phase = if (p.method.name == "attach") InstallPhase.APPLICATION_ATTACH else InstallPhase.APPLICATION_ON_CREATE
                if (res.strategy.stealthInstallPhase == phase) installStealth(lpparam, pkg, phase)
            }
            override fun afterHookedMethod(p: MethodHookParam) {
                val app = p.thisObject as Application
                val pkg = lpparam.packageName
                HookConfig.refreshIfStale()
                val res = StrategyResolver.resolve(pkg, HookConfig.features())
                if (p.method.name == "attach") onApplicationAttach(lpparam, app, res) else onApplicationCreate(lpparam, app, res)
            }
        }
        try {
            XposedHelpers.findAndHookMethod(Application::class.java, "attach", android.content.Context::class.java, hook)
            XposedHelpers.findAndHookMethod(Application::class.java, "onCreate", hook)
        } catch (_: Throwable) {}
    }

    private fun scheduleIntegrity(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (integrityStarted) return
        synchronized(this) {
            if (integrityStarted) return
            integrityStarted = true
            Thread({
                try {
                    repeat(150) { if (NativeLibLoader.isIntegrityLibMapped()) { Thread.sleep(150); return@repeat } else Thread.sleep(20) }
                    Thread.sleep(300)
                    repeat(200) { currentApp()?.let { app -> onApplicationCreate(lpparam, app, StrategyResolver.resolve(lpparam.packageName, HookConfig.features())); return@repeat } ?: Thread.sleep(50) }
                } catch (_: Throwable) {}
            }, "YH-integrity").start()
        }
    }

    private fun currentApp(): Application? = try {
        val at = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread")
        XposedHelpers.getObjectField(at, "mInitialApplication") as? Application
    } catch (_: Throwable) { null }

    private fun finalizeStealth(lpparam: XC_LoadPackage.LoadPackageParam, res: ResolvedChannelStrategy, phase: InstallPhase) {
        if (phase.ordinal < res.strategy.stealthInstallPhase.ordinal) return
        if (res.nativeInstallMode == NativeInstallMode.HOST_SHADOWHOOK_DEFERRED && !NativeBridge.isHooksInstalled()) return
        NativeStealthBridge.install(lpparam)
    }

    private fun shouldCompat(res: ResolvedChannelStrategy, phase: InstallPhase): Boolean = res.strategy.registerReceiverCompat && (res.strategy.channelStubInstallPhase == phase || (res.strategy.stealthInstallPhase == phase && res.strategy.channelStubInstallPhase != InstallPhase.LOAD_PACKAGE))

    private fun ensureChannelStubs(lpparam: XC_LoadPackage.LoadPackageParam, res: ResolvedChannelStrategy, phase: InstallPhase) {
        if (channelStubsInstalled || res.strategy.channelStubInstallPhase != phase) return
        synchronized(this) {
            if (channelStubsInstalled) return
            OsBuildHook.install(lpparam); SystemPropertiesHook.install(lpparam); GetpropHook.install(lpparam)
            NativeBridge.syncFromGate(HookConfig.valuesForHook(), lpparam.packageName)
            channelStubsInstalled = true; log(lpparam.packageName, "stubs @$phase")
        }
    }

    private fun installStealth(lpparam: XC_LoadPackage.LoadPackageParam, pkg: String, phase: InstallPhase) {
        if (stealthInstalled) return
        synchronized(this) {
            if (stealthInstalled) return
            HookConfig.refreshIfStale()
            FeatureStealthInstaller.install(lpparam)
            stealthInstalled = true; log(pkg, "stealth @$phase")
        }
    }

    private fun retryNativeEarlyIfNeeded(lpparam: XC_LoadPackage.LoadPackageParam, res: ResolvedChannelStrategy) { if (!nativeReady && res.nativeActive) ensureNativeEarly(lpparam, res) }

    private fun ensureNativeEarly(lpparam: XC_LoadPackage.LoadPackageParam, res: ResolvedChannelStrategy) {
        val pkg = lpparam.packageName
        if (!res.nativeActive) { NativeBridge.syncFromGate(HookConfig.valuesForHook(), pkg); return }
        if (nativeReady) return
        synchronized(this) { if (!nativeReady && NativeBridge.installEarly(lpparam)) { NativeBridge.retryDeferredHooks(pkg); nativeReady = true; log(pkg, "native early ok") } }
    }

    private fun ensureNative(lpparam: XC_LoadPackage.LoadPackageParam, app: Application, res: ResolvedChannelStrategy) {
        val pkg = lpparam.packageName
        if (!res.nativeActive) { NativeBridge.syncFromGate(HookConfig.valuesForHook(), pkg); return }
        if (nativeReady) { NativeBridge.syncProperties(HookConfig.valuesForHook(), app); return }
        synchronized(this) { if (!nativeReady) { NativeBridge.install(lpparam, app); NativeBridge.retryDeferredHooks(pkg); nativeReady = true; log(pkg, "native ok mode=${res.nativeInstallMode}") } }
    }

    private fun ResolvedChannelStrategy.applyBuild(phase: InstallPhase): Boolean = fourChannelActive && strategy.applyBuildAtPhase == phase
}
