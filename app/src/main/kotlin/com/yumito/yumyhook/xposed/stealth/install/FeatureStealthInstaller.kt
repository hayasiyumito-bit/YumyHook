package com.yumito.yumyhook.xposed.stealth.install

import com.yumito.yumyhook.xposed.config.HookFeatureConfig
import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.stealth.airplane.AirplaneModeStealthHook
import com.yumito.yumyhook.xposed.stealth.bluetooth.BluetoothStealthHook
import com.yumito.yumyhook.xposed.stealth.device.UptimeStealthHook
import com.yumito.yumyhook.xposed.stealth.device.WebSettingsStealthHook
import com.yumito.yumyhook.xposed.stealth.hide.EnvStealthHook
import com.yumito.yumyhook.xposed.stealth.hide.NativeStealthBridge
import com.yumito.yumyhook.xposed.stealth.hide.PackageHideStealthHook
import com.yumito.yumyhook.xposed.stealth.hide.ProcMapsStealthHook
import com.yumito.yumyhook.xposed.stealth.hide.SensitivePathStealthHook
import com.yumito.yumyhook.xposed.stealth.hide.XposedFingerprintStealthHook
import com.yumito.yumyhook.xposed.stealth.identity.AppIdentityStealthHook
import com.yumito.yumyhook.xposed.stealth.identity.InstallSourceStealthHook
import com.yumito.yumyhook.xposed.stealth.location.LocationStealthHook
import com.yumito.yumyhook.xposed.stealth.network.LanScanStealthHook
import com.yumito.yumyhook.xposed.stealth.network.ProxyStealthHook
import com.yumito.yumyhook.xposed.stealth.network.VpnStealthHook
import com.yumito.yumyhook.xposed.stealth.root.AdbStealthHook
import com.yumito.yumyhook.xposed.stealth.root.DebugStealthHook
import com.yumito.yumyhook.xposed.stealth.root.DeveloperOptionsStealthHook
import com.yumito.yumyhook.xposed.stealth.root.RootBuildStealthHook
import com.yumito.yumyhook.xposed.stealth.root.RootPropertyStealthHook
import com.yumito.yumyhook.xposed.stealth.root.ShellProbeStealthHook
import com.yumito.yumyhook.xposed.stealth.telephony.TelephonyStealthHook
import com.yumito.yumyhook.xposed.stealth.wifi.WifiStealthHook
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
            EnvStealthHook.install()
            PackageHideStealthHook.install(lpparam)
            XposedFingerprintStealthHook.install()
            ShellProbeStealthHook.install()
        }
        if (f.hideRoot) {
            SensitivePathStealthHook.install()
            RootPropertyStealthHook.install()
            RootBuildStealthHook.install()
            PackageHideStealthHook.install(lpparam)
            ShellProbeStealthHook.install()
            NativeStealthBridge.install(lpparam)
        }
        if (f.hideDeveloperOptions || f.hideRoot) {
            AdbStealthHook.install(lpparam)
        }
        if (f.hideDeveloperOptions) {
            DeveloperOptionsStealthHook.install()
        }
        DebugStealthHook.install(lpparam)
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
        if (f.spoofLocation) LocationStealthHook.install(lpparam)
        if (f.spoofFullDeviceId || f.simSimulation) {
            TelephonyStealthHook.install(lpparam, f.spoofFullDeviceId, f.simSimulation)
        }
    }
}
