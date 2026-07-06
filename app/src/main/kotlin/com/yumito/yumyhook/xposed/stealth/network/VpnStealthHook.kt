package com.yumito.yumyhook.xposed.stealth.network
import android.net.NetworkCapabilities
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** HackChecker.isVPN — 隐藏 VPN 传输层 */
object VpnStealthHook {
    fun install() {
        try {
            XposedHelpers.findAndHookMethod(
                NetworkCapabilities::class.java,
                "hasTransport",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val transport = param.args[0] as Int
                        if (transport == NetworkCapabilities.TRANSPORT_VPN) {
                            param.result = false
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: VpnStealth skip: ${e.message}")
        }
    }
}
