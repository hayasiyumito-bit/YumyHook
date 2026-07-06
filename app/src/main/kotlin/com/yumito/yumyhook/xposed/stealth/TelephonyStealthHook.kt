package com.yumito.yumyhook.xposed.stealth

import android.content.ContentResolver
import com.yumito.yumyhook.xposed.config.HookConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** IDs / SimCard — TelephonyManager */
object TelephonyStealthHook {
    fun install(lpparam: XC_LoadPackage.LoadPackageParam, fullId: Boolean, simSim: Boolean) {
        if (!fullId && !simSim) return
        val values = HookConfig.valuesForHook()
        val hooks = mapOf(
            "getSimOperator" to values.idsFields["simOperator"],
            "getSimOperatorName" to values.idsFields["simOperatorName"],
            "getSimCountryIso" to values.idsFields["simCountryIso"],
            "getSubscriberId" to values.idsFields["imsi"],
            "getLine1Number" to values.idsFields["phoneNo"],
            "getDeviceId" to values.idsFields["imei"],
        )
        for ((method, spoof) in hooks) {
            if (spoof.isNullOrBlank()) continue
            try {
                XposedHelpers.findAndHookMethod(
                    "android.telephony.TelephonyManager",
                    lpparam.classLoader,
                    method,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = spoof
                        }
                    },
                )
            } catch (_: Throwable) {
            }
        }
        if (fullId) {
            val androidId = values.idsFields["androidId"]
            if (!androidId.isNullOrBlank()) {
                try {
                    XposedHelpers.findAndHookMethod(
                        "android.provider.Settings\$Secure",
                        null,
                        "getString",
                        ContentResolver::class.java,
                        String::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (param.args[1] == "android_id") {
                                    param.result = androidId
                                }
                            }
                        },
                    )
                } catch (_: Throwable) {
                }
            }
        }
    }
}
