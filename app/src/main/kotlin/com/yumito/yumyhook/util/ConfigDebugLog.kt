package com.yumito.yumyhook.util

import android.content.Context
import android.util.Log
import com.yumito.yumyhook.xposed.XposedConstants

/** 配置页调试日志：开关开启后打印所有 UI 操作到 logcat。 */
object ConfigDebugLog {

    private const val TAG = "YumyHookConfig"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(XposedConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(XposedConstants.PREF_DEBUG_UI_LOG, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(XposedConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(XposedConstants.PREF_DEBUG_UI_LOG, enabled)
            .apply()
        if (enabled) {
            Log.d(TAG, "debug log ON")
        }
    }

    fun log(context: Context, message: String) {
        if (!isEnabled(context)) return
        Log.d(TAG, message)
    }
}
