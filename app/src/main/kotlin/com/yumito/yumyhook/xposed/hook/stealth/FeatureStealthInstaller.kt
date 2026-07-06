package com.yumito.yumyhook.xposed.hook.stealth

import com.yumito.yumyhook.xposed.HookFeatureConfig
import com.yumito.yumyhook.xposed.XposedConstants
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** 按功能开关安装反检测 / 隐私 Hook */
object FeatureStealthInstaller {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val f = HookFeatureConfig.refreshIfStale()
        XposedBridge.log("${XposedConstants.TAG}: feature stealth rev=${XposedConstants.HOOK_REV} pkg=${lpparam.packageName}")

        if (f.hideLsposed) {
            ProcMapsStealthHook.install()
            SensitivePathStealthHook.install()
            PackageHideStealthHook.install(lpparam)
        }
        if (f.hideDeveloperOptions || f.hideRoot) {
            AdbStealthHook.install(lpparam)
        }
        if (f.hideDeveloperOptions) {
            DeveloperOptionsStealthHook.install()
        }
        DebugStealthHook.install(lpparam)
        if (f.hideRoot) {
            ShellProbeStealthHook.install()
        }
        if (f.hideVpn) VpnStealthHook.install()
        if (f.spoofInstallSourcePlay) InstallSourceStealthHook.install(lpparam)
        if (f.hideAirplaneMode) AirplaneModeStealthHook.install()
        if (f.hideProxy) ProxyStealthHook.install()
        if (f.hideWifiNetworks || f.spoofWifiInfo) {
            WifiStealthHook.install(lpparam, f.hideWifiNetworks, f.spoofWifiInfo)
        }
        if (f.hideBluetooth) BluetoothStealthHook.install(lpparam)
        if (f.blockLanScan) LanScanStealthHook.install()
        if (f.spoofUptime) UptimeStealthHook.install()
        if (f.spoofBrowserFingerprint) WebSettingsStealthHook.install(lpparam)
        if (f.spoofAppIdentity) AppIdentityStealthHook.install(lpparam)
        if (f.spoofFullDeviceId || f.simSimulation) {
            TelephonyStealthHook.install(lpparam, f.spoofFullDeviceId, f.simSimulation)
        }
    }
}
