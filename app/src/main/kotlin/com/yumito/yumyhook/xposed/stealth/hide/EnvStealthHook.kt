package com.yumito.yumyhook.xposed.stealth.hide

import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** 隐藏 LD_PRELOAD / LD_LIBRARY_PATH 等环境变量中的 Hook 痕迹。 */
object EnvStealthHook {

    private val WATCHED_ENV_KEYS = setOf(
        "LD_PRELOAD",
        "LD_LIBRARY_PATH",
        "CLASSPATH",
    )

    fun install() {
        try {
            XposedHelpers.findAndHookMethod(
                System::class.java,
                "getenv",
                String::class.java,
                EnvSanitizer(),
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: EnvStealth getenv skip: ${e.message}")
        }
        try {
            XposedHelpers.findAndHookMethod(
                System::class.java,
                "getenv",
                EnvMapSanitizer(),
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: EnvStealth getenv map skip: ${e.message}")
        }
    }

    internal fun sanitizeEnvValue(key: String?, value: String?): String? {
        if (value.isNullOrEmpty()) return value
        if (key != null && key.uppercase() !in WATCHED_ENV_KEYS) return value
        val lower = value.lowercase()
        val markers = listOf("xposed", "lsposed", "frida", "magisk", "riru", "zygisk", "shadowhook", "yumyhook")
        return if (markers.any { lower.contains(it) }) null else value
    }

    private class EnvSanitizer : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val key = param.args[0] as? String
            param.result = sanitizeEnvValue(key, param.result as? String)
        }
    }

    private class EnvMapSanitizer : XC_MethodHook() {
        @Suppress("UNCHECKED_CAST")
        override fun afterHookedMethod(param: MethodHookParam) {
            val map = param.result as? MutableMap<String, String> ?: return
            for (key in map.keys.toList()) {
                val sanitized = sanitizeEnvValue(key, map[key])
                if (sanitized == null) {
                    map.remove(key)
                } else {
                    map[key] = sanitized
                }
            }
        }
    }
}
