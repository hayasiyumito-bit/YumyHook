package com.yumito.yumyhook.xposed.stealth.root

import android.content.ContentResolver
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.runtime.HookReentryGuard
import com.yumito.yumyhook.xposed.stealth.common.StealthConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** 综合 Root / Adb / Debug / 开发者选项伪装。 */
object RootStealthHook {

    @Volatile
    private var installed = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            hookSettings(lpparam)
            hookDebug(lpparam)
            hookBuild(lpparam)
            installed = true
        }
    }

    private fun hookSettings(lpparam: XC_LoadPackage.LoadPackageParam) {
        val keys = setOf(StealthConstants.SETTINGS_ADB_ENABLED, "development_settings_enabled")
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.getOrNull(1) in keys) param.result = 0
            }
        }
        listOf("android.provider.Settings\$Secure", "android.provider.Settings\$Global").forEach { clazz ->
            try {
                XposedHelpers.findAndHookMethod(clazz, null, "getInt", ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType, hook)
                XposedHelpers.findAndHookMethod(clazz, null, "getInt", ContentResolver::class.java, String::class.java, hook)
            } catch (_: Throwable) {}
        }
    }

    private fun hookDebug(lpparam: XC_LoadPackage.LoadPackageParam) {
        val stripFlags = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.result as? ApplicationInfo)?.let { it.flags = it.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv() }
            }
        }
        listOf("android.app.ContextImpl", "android.app.ApplicationPackageManager").forEach { clazz ->
            try {
                XposedHelpers.findAndHookMethod(clazz, null, "getApplicationInfo", stripFlags)
                XposedHelpers.findAndHookMethod(clazz, null, "getApplicationInfo", String::class.java, Int::class.javaPrimitiveType, stripFlags)
            } catch (_: Throwable) {}
        }
        try {
            XposedHelpers.findAndHookMethod(Debug::class.java, "isDebuggerConnected", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = false }
            })
        } catch (_: Throwable) {}
    }

    private fun hookBuild(lpparam: XC_LoadPackage.LoadPackageParam) {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val key = param.args[0] as? String ?: return
                if (key == "ro.build.tags") param.result = "release-keys"
                if (key == "ro.debuggable") param.result = "0"
                if (key == "ro.secure") param.result = "1"
            }
        }
        try {
            XposedHelpers.findAndHookMethod("android.os.SystemProperties", null, "get", String::class.java, hook)
            XposedHelpers.findAndHookMethod("android.os.SystemProperties", null, "get", String::class.java, String::class.java, hook)
        } catch (_: Throwable) {}

        if (Build.TAGS.contains("test-keys")) {
            try { XposedHelpers.setStaticObjectField(Build::class.java, "TAGS", Build.TAGS.replace("test-keys", "release-keys")) } catch (_: Throwable) {}
        }
        if (Build.TYPE.contains("debug")) {
            try { XposedHelpers.setStaticObjectField(Build::class.java, "TYPE", "user") } catch (_: Throwable) {}
        }
    }
}
