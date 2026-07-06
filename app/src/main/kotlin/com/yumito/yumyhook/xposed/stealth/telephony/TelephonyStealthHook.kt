package com.yumito.yumyhook.xposed.stealth.telephony
import android.content.ContentResolver
import com.yumito.yumyhook.xposed.config.HookConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** TelephonyManager / Settings — SIM 与设备标识分开门控。 */
object TelephonyStealthHook {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam, fullId: Boolean, simSim: Boolean) {
        if (!fullId && !simSim) return
        val values = HookConfig.valuesForHook()
        val simHooks = mapOf(
            "getSimOperator" to values.idsFields["simOperator"],
            "getSimOperatorName" to values.idsFields["simOperatorName"],
            "getSimCountryIso" to values.idsFields["simCountryIso"],
        )
        val idHooks = mapOf(
            "getSubscriberId" to values.idsFields["imsi"],
            "getLine1Number" to values.idsFields["phoneNo"],
            "getDeviceId" to values.idsFields["imei"],
        )
        if (simSim) {
            hookTelephony(lpparam, simHooks)
        }
        if (fullId) {
            hookTelephony(lpparam, idHooks)
            hookAndroidId(values.idsFields["androidId"])
        }
    }

    private fun hookTelephony(
        lpparam: XC_LoadPackage.LoadPackageParam,
        hooks: Map<String, String?>,
    ) {
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
    }

    private fun hookAndroidId(androidId: String?) {
        if (androidId.isNullOrBlank()) return
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
