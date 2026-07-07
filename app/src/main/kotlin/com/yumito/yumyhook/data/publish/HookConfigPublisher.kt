package com.yumito.yumyhook.data.publish

import android.content.Context
import com.yumito.yumyhook.data.lsposed.RootShell
import com.yumito.yumyhook.xposed.config.SpoofConfigFile
import com.yumito.yumyhook.xposed.config.XposedConstants
import java.io.File

/** 模块 UI 写配置后，确保目标进程能读到最新 spoof_config / XSharedPreferences。 */
object HookConfigPublisher {

    fun publish(context: Context) {
        SpoofConfigFile.publishReadable(context)
        mirrorViaRootIfNeeded(context)
        makePrefsWorldReadable(context)
    }

    /** 目标 App 无模块 UID 时，用 root 把配置推到 /data/local/tmp/yumyhook。 */
    private fun mirrorViaRootIfNeeded(context: Context) {
        val source = File(context.filesDir, SpoofConfigFile.FILE_NAME)
        if (!source.exists() || source.length() == 0L) return
        val mirror = SpoofConfigFile.publicMirrorFile()
        if (mirror.exists() && mirror.length() > 0L) return
        if (!RootShell.ensureRoot()) return
        val dir = mirror.parentFile?.absolutePath ?: return
        val cmd = "mkdir -p '$dir' && cp '${source.absolutePath}' '${mirror.absolutePath}' " +
            "&& chmod 644 '${mirror.absolutePath}' && chmod 755 '$dir'"
        val result = RootShell.exec(cmd)
        if (result.exitCode != 0) {
            android.util.Log.w(
                XposedConstants.TAG,
                "HookConfigPublisher.rootMirror failed: ${result.error.ifBlank { result.output }}",
            )
        }
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
