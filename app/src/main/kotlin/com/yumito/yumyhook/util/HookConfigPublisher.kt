package com.yumito.yumyhook.util

import android.content.Context
import com.yumito.yumyhook.xposed.SpoofConfigFile
import com.yumito.yumyhook.xposed.XposedConstants
import java.io.File

/** 模块 UI 写配置后，确保目标进程能读到最新 spoof_config / XSharedPreferences。 */
object HookConfigPublisher {

    fun publish(context: Context) {
        SpoofConfigFile.publishReadable(context)
        makePrefsWorldReadable(context)
    }

    /**
     * 模块 App 进程无 XposedBridge 类；直接 chmod prefs xml，有框架时再反射 makeWorldReadable。
     */
    private fun makePrefsWorldReadable(context: Context) {
        chmodPrefsXml(context)
        try {
            val clazz = Class.forName("de.robv.android.xposed.XSharedPreferences")
            val prefs = clazz.getConstructor(String::class.java, String::class.java)
                .newInstance(XposedConstants.MODULE_PACKAGE, XposedConstants.PREFS_NAME)
            clazz.getMethod("makeWorldReadable").invoke(prefs)
            clazz.getMethod("reload").invoke(prefs)
        } catch (_: Throwable) {
        }
    }

    private fun chmodPrefsXml(context: Context) {
        try {
            val prefsFile = File(
                context.applicationInfo.dataDir,
                "shared_prefs/${XposedConstants.PREFS_NAME}.xml",
            )
            if (!prefsFile.exists()) return
            prefsFile.setReadable(true, false)
            prefsFile.parentFile?.setExecutable(true, false)
        } catch (_: Throwable) {
        }
    }
}
