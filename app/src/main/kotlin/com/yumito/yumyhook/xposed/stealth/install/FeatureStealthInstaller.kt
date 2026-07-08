package com.yumito.yumyhook.xposed.stealth.install

import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.stealth.airplane.AirplaneModeStealthHook
import com.yumito.yumyhook.xposed.stealth.bluetooth.BluetoothStealthHook
import com.yumito.yumyhook.xposed.stealth.device.UptimeStealthHook
import com.yumito.yumyhook.xposed.stealth.device.WebSettingsStealthHook
import com.yumito.yumyhook.xposed.stealth.hide.EnvStealthHook
import com.yumito.yumyhook.xposed.stealth.hide.NativeStealthBridge
import com.yumito.yumyhook.xposed.stealth.hide.NativeApiStealthHook
import com.yumito.yumyhook.xposed.stealth.hide.PackageHideStealthHook
import com.yumito.yumyhook.xposed.stealth.hide.ProcFsStealthHook
import com.yumito.yumyhook.xposed.stealth.hide.SensitivePathStealthHook
import com.yumito.yumyhook.xposed.stealth.hide.XposedFingerprintStealthHook
import com.yumito.yumyhook.xposed.stealth.identity.AppIdentityStealthHook
import com.yumito.yumyhook.xposed.stealth.identity.InstallSourceStealthHook
import com.yumito.yumyhook.xposed.stealth.location.LocationStealthHook
import com.yumito.yumyhook.xposed.stealth.network.LanScanStealthHook
import com.yumito.yumyhook.xposed.stealth.network.ProxyStealthHook
import com.yumito.yumyhook.xposed.stealth.network.VpnStealthHook
import com.yumito.yumyhook.xposed.stealth.root.ShellStealthHook
import com.yumito.yumyhook.xposed.stealth.root.RootStealthHook
import com.yumito.yumyhook.xposed.stealth.telephony.TelephonyStealthHook
import com.yumito.yumyhook.xposed.stealth.wifi.WifiStealthHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** 按功能开关安装反检测 / 隐私 Hook */
object FeatureStealthInstaller {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val f = HookConfig.refreshIfStale().let { HookConfig.features() }
        XposedBridge.log("${XposedConstants.TAG}: feature stealth rev=${XposedConstants.HOOK_REV} pkg=${lpparam.packageName}")

        if (f.hideLsposed) {
            ProcFsStealthHook.install()
            SensitivePathStealthHook.install()
            NativeApiStealthHook.install()
            EnvStealthHook.install()
            PackageHideStealthHook.install(lpparam)
            XposedFingerprintStealthHook.install()
            ShellStealthHook.install()
        }
        if (f.hideRoot) {
            SensitivePathStealthHook.install()
            ProcFsStealthHook.install()
            RootStealthHook.install(lpparam)
            ShellStealthHook.install()
        }
        NativeStealthBridge.install(lpparam)
        if (f.hideDeveloperOptions || f.hideRoot) {
            RootStealthHook.install(lpparam)
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
        if (f.spoofLocation) LocationStealthHook.install(lpparam)
        if (f.spoofFullDeviceId || f.simSimulation) {
            TelephonyStealthHook.install(lpparam, f.spoofFullDeviceId, f.simSimulation)
        }
    }
}
