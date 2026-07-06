package com.yumito.yumyhook.xposed.stealth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * API33+：为未带 RECEIVER_EXPORTED / NOT_EXPORTED 的 registerReceiver 补 NOT_EXPORTED。
 * B 站 BLKV 等在 attachBaseContext 走旧 API 时会崩。
 */
object RegisterReceiverCompatHook {

    private const val RECEIVER_NOT_EXPORTED = 0x4

    @Volatile
    private var installed = false

    fun installIfNeeded() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                installed = true
                return
            }
            hookTwoArg()
            hookFourArg()
            installed = true
            XposedBridge.log("${XposedConstants.TAG}: registerReceiver compat installed")
        }
    }

    private fun hookTwoArg() {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ContextImpl",
                null,
                "registerReceiver",
                BroadcastReceiver::class.java,
                IntentFilter::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = XposedHelpers.callMethod(
                            param.thisObject,
                            "registerReceiver",
                            param.args[0],
                            param.args[1],
                            RECEIVER_NOT_EXPORTED,
                        )
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: registerReceiver 2-arg skip: ${e.message}")
        }
    }

    private fun hookFourArg() {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ContextImpl",
                null,
                "registerReceiver",
                BroadcastReceiver::class.java,
                IntentFilter::class.java,
                String::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = XposedHelpers.callMethod(
                            param.thisObject,
                            "registerReceiver",
                            param.args[0],
                            param.args[1],
                            param.args[2],
                            param.args[3],
                            RECEIVER_NOT_EXPORTED,
                        )
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: registerReceiver 4-arg skip: ${e.message}")
        }
    }
}
