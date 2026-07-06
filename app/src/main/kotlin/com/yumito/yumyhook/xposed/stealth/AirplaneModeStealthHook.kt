package com.yumito.yumyhook.xposed.stealth

import android.content.ContentResolver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/** Settings.Global 飞行模式 */
object AirplaneModeStealthHook {
    private const val AIRPLANE = "airplane_mode_on"

    fun install() {
        hookGetInt("android.provider.Settings\$Global", AIRPLANE)
        hookGetInt("android.provider.Settings\$System", AIRPLANE)
    }

    private fun hookGetInt(className: String, target: String) {
        try {
            XposedHelpers.findAndHookMethod(
                className,
                null,
                "getInt",
                ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args[1] == target) param.result = 0
                    }
                },
            )
        } catch (_: Throwable) {
        }
    }
}
