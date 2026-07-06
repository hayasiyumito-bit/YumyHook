package com.yumito.yumyhook.xposed.stealth.root
import android.content.ContentResolver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/** Settings.Global development_settings_enabled */
object DeveloperOptionsStealthHook {
    fun install() {
        val keys = setOf("development_settings_enabled", "adb_enabled")
        try {
            XposedHelpers.findAndHookMethod(
                "android.provider.Settings\$Global",
                null,
                "getInt",
                ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val name = param.args[1] as? String ?: return
                        if (name in keys) param.result = 0
                    }
                },
            )
        } catch (_: Throwable) {
        }
    }
}
