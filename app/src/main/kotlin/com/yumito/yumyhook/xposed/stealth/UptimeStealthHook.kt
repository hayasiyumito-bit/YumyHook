package com.yumito.yumyhook.xposed.stealth

import android.os.SystemClock
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** 实验性运行时间伪装 */
object UptimeStealthHook {
    private const val OFFSET_MS = 86_400_000L * 3

    fun install() {
        val offsetHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val orig = param.result as? Long ?: return
                param.result = orig + OFFSET_MS
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                SystemClock::class.java,
                "elapsedRealtime",
                offsetHook,
            )
            XposedHelpers.findAndHookMethod(
                SystemClock::class.java,
                "uptimeMillis",
                offsetHook,
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: UptimeStealth skip: ${e.message}")
        }
    }
}
