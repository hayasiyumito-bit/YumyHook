package com.yumito.yumyhook.xposed.stealth.network
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.net.NetworkInterface
import java.util.Collections

/** Net.java NetworkInterface 枚举 — 减少局域网可见网卡 */
object LanScanStealthHook {
    fun install() {
        try {
            XposedHelpers.findAndHookMethod(
                NetworkInterface::class.java,
                null,
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
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: LanScanStealth skip: ${e.message}")
        }
    }
}
