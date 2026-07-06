package com.yumito.yumyhook.feature.config

import android.content.Context
import android.util.Log
import com.yumito.yumyhook.xposed.config.XposedConstants

/** 配置页调试日志：仅记录功能开关切换与分区保存。 */
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
            Log.d(TAG, "调试日志已开启")
        }
    }

    fun logFeatureToggle(context: Context, title: String, key: String, enabled: Boolean) {
        if (!isEnabled(context)) return
        Log.d(TAG, "功能开关 [$title] ($key) = ${if (enabled) "开" else "关"}")
    }

    fun logSave(context: Context, section: String, fields: Map<String, String>) {
        if (!isEnabled(context)) return
        if (fields.isEmpty()) {
            Log.d(TAG, "保存$section：无字段")
            return
        }
        Log.d(TAG, "保存$section：")
        fields.forEach { (k, v) -> Log.d(TAG, "  $k=$v") }
    }
}
