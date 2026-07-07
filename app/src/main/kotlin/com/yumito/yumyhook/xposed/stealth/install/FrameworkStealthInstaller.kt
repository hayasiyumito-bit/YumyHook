package com.yumito.yumyhook.xposed.stealth.install

import com.yumito.yumyhook.xposed.config.HookFeatureConfig
import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.stealth.hide.EnvStealthHook
import com.yumito.yumyhook.xposed.stealth.hide.ProcMapsStealthHook
import com.yumito.yumyhook.xposed.stealth.hide.SensitivePathStealthHook
import com.yumito.yumyhook.xposed.stealth.hide.XposedFingerprintStealthHook
import com.yumito.yumyhook.xposed.stealth.root.AdbStealthHook
import com.yumito.yumyhook.xposed.stealth.root.DebugStealthHook
import com.yumito.yumyhook.xposed.stealth.root.DeveloperOptionsStealthHook
import com.yumito.yumyhook.xposed.stealth.root.RootPropertyStealthHook
import com.yumito.yumyhook.xposed.stealth.root.ShellProbeStealthHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Android 系统框架（system_server）专用：仅反检测，不装四通道 / Native。
 */
object FrameworkStealthInstaller {

    @Volatile
    private var installed = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val f = HookFeatureConfig.refreshIfStale()
            XposedBridge.log(
                "${XposedConstants.TAG}: framework stealth rev=${XposedConstants.HOOK_REV} " +
                    "hideRoot=${f.hideRoot} hideLsposed=${f.hideLsposed}",
            )
            val stealthOn = f.hideRoot || f.hideLsposed
            if (stealthOn) {
                RootPropertyStealthHook.install()
                FrameworkPackageStealthHook.install(lpparam)
                SensitivePathStealthHook.install()
                ShellProbeStealthHook.install()
                EnvStealthHook.install()
                ProcMapsStealthHook.install()
            }
            if (f.hideLsposed) {
                XposedFingerprintStealthHook.install()
            }
            if (f.hideDeveloperOptions || f.hideRoot) {
                AdbStealthHook.install(lpparam)
            }
            if (f.hideDeveloperOptions) {
                DeveloperOptionsStealthHook.install()
            }
            DebugStealthHook.install(lpparam)
            installed = true
            XposedBridge.log("${XposedConstants.TAG}: framework stealth installed (no native/build)")
        }
    }
}
