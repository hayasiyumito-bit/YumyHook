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
        
        if (simSim) {
            hookTelephony(lpparam, "getSimOperator", "simOperator")
            hookTelephony(lpparam, "getSimOperatorName", "simOperatorName")
            hookTelephony(lpparam, "getSimCountryIso", "simCountryIso")
        }
        if (fullId) {
            hookTelephony(lpparam, "getSubscriberId", "imsi")
            hookTelephony(lpparam, "getLine1Number", "phoneNo")
            hookTelephony(lpparam, "getDeviceId", "imei")
            hookAndroidId()
        }
    }

    private fun hookTelephony(
        lpparam: XC_LoadPackage.LoadPackageParam,
        method: String,
        fieldKey: String,
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager",
                lpparam.classLoader,
                method,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val values = HookConfig.refreshHookCacheIfStale()
                        val spoof = values.idsFields[fieldKey]
                        if (!spoof.isNullOrBlank()) {
                            param.result = spoof
                        }
                    }
                },
            )
        } catch (_: Throwable) {
        }
    }

    private fun hookAndroidId() {
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
                            val values = HookConfig.refreshHookCacheIfStale()
                            val androidId = values.idsFields["androidId"]
                            if (!androidId.isNullOrBlank()) {
                                param.result = androidId
                            }
                        }
                    }
                },
            )
        } catch (_: Throwable) {
        }
    }
}
