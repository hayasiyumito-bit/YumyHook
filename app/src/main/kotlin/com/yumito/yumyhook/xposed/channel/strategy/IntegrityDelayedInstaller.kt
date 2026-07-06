package com.yumito.yumyhook.xposed.channel.strategy

import android.app.Application
import com.yumito.yumyhook.xposed.channel.HostShadowhookDetector
import com.yumito.yumyhook.xposed.config.HookFeatureConfig
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * libpairipcore JNI_OnLoad 期间扫描 Xposed 桩 → SIGSEGV。
 * LOAD_PACKAGE **零 hook**；后台等完整性库映射完成后再装 YumyHook。
 */
object IntegrityDelayedInstaller {

    @Volatile
    private var started = false

    fun schedule(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            Thread({ runInstall(lpparam) }, "YumyHook-integrity-delay").start()
            XposedBridge.log(
                "${XposedConstants.TAG}: integrity delayed (zero hooks) pkg=${lpparam.packageName}",
            )
        }
    }

    private fun runInstall(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            waitForIntegrityLib()
            val app = waitForApplication()
            val resolved = StrategyResolver.resolve(
                lpparam.packageName,
                HookFeatureConfig.refreshIfStale(),
            )
            if (app != null) {
                ChannelInstallCoordinator.onApplicationCreate(lpparam, app, resolved)
            } else {
                ChannelInstallCoordinator.onDeferredWithoutApplication(lpparam, resolved)
            }
            XposedBridge.log(
                "${XposedConstants.TAG}: integrity delayed install done pkg=${lpparam.packageName}",
            )
        } catch (e: Throwable) {
            XposedBridge.log(
                "${XposedConstants.TAG}: integrity delayed install fail: ${e.message}",
            )
        }
    }

    private fun waitForIntegrityLib() {
        repeat(150) {
            if (HostShadowhookDetector.isIntegrityLibMapped()) {
                Thread.sleep(150)
                return
            }
            Thread.sleep(20)
        }
        Thread.sleep(300)
    }

    private fun waitForApplication(): Application? {
        repeat(200) {
            currentApplication()?.let { return it }
            Thread.sleep(50)
        }
        return currentApplication()
    }

    private fun currentApplication(): Application? {
        return try {
            val thread = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentActivityThread",
            )
            XposedHelpers.getObjectField(thread, "mInitialApplication") as? Application
        } catch (_: Throwable) {
            null
        }
    }
}
