package com.yumito.yumyhook.xposed.stealth.wifi
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** Net.java Wi-Fi 扫描 / 连接信息 */
object WifiStealthHook {
    fun install(lpparam: XC_LoadPackage.LoadPackageParam, hideNetworks: Boolean, spoofInfo: Boolean) {
        if (hideNetworks) {
            try {
                XposedHelpers.findAndHookMethod(
                    "android.net.wifi.WifiManager",
                    lpparam.classLoader,
                    "getScanResults",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = emptyList<Any>()
                        }
                    },
                )
            } catch (e: Throwable) {
                XposedBridge.log("${XposedConstants.TAG}: getScanResults skip: ${e.message}")
            }
        }
        if (spoofInfo) {
            try {
                XposedHelpers.findAndHookMethod(
                    "android.net.wifi.WifiManager",
                    lpparam.classLoader,
                    "getConnectionInfo",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val info = param.result ?: return
                            try {
                                XposedHelpers.setObjectField(info, "mSSID", "AndroidWifi")
                                XposedHelpers.setObjectField(info, "mBSSID", "02:00:00:00:00:00")
                            } catch (_: Throwable) {
                            }
                        }
                    },
                )
            } catch (e: Throwable) {
                XposedBridge.log("${XposedConstants.TAG}: getConnectionInfo skip: ${e.message}")
            }
        }
    }
}
