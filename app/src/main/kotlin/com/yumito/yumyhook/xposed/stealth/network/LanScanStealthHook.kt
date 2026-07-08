package com.yumito.yumyhook.xposed.stealth.network
import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.net.NetworkInterface
import java.util.Collections

/** Net.java NetworkInterface 枚举 — 减少局域网可见网卡 + 伪装 MAC 地址 */
object LanScanStealthHook {
    fun install() {
        try {
            XposedHelpers.findAndHookMethod(
                NetworkInterface::class.java,
                "getNetworkInterfaces",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val en = param.result as? java.util.Enumeration<*> ?: return
                        val kept = mutableListOf<NetworkInterface>()
                        while (en.hasMoreElements()) {
                            val ni = en.nextElement() as? NetworkInterface ?: continue
                            val name = ni.name ?: ""
                            if (name.startsWith("lo") || name.startsWith("wlan") || name.startsWith("rmnet")) {
                                kept.add(ni)
                            }
                        }
                        param.result = Collections.enumeration(kept)
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                NetworkInterface::class.java,
                "getHardwareAddress",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val ni = param.thisObject as NetworkInterface
                        if (ni.name?.startsWith("wlan") == true) {
                            val values = HookConfig.refreshHookCacheIfStale()
                            val mac = values.idsFields["mac"] ?: "02:00:00:00:00:00"
                            param.result = macToBytes(mac)
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: LanScanStealth skip: ${e.message}")
        }
    }

    private fun macToBytes(mac: String): ByteArray {
        return try {
            val parts = mac.split(":")
            val bytes = ByteArray(6)
            for (i in 0 until 6) {
                bytes[i] = parts[i].toInt(16).toByte()
            }
            bytes
        } catch (_: Throwable) {
            ByteArray(6)
        }
    }
}
