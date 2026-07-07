package com.yumito.yumyhook.xposed.stealth.root

import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.stealth.common.StealthConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** 伪装 ro.build.tags / ro.debuggable 等 Root / Magisk 探测用系统属性。 */
object RootPropertyStealthHook {

    private const val TARGET_CLASS = "android.os.SystemProperties"
    private val bootClassLoader: ClassLoader? = null

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            hookGetter("get", String::class.java)
            hookGetter("get", String::class.java, String::class.java)
            hookGetter("getInt", String::class.java, Integer.TYPE)
            hookGetter("getBoolean", String::class.java, java.lang.Boolean.TYPE)
            installed = true
        }
    }

    private fun hookGetter(name: String, vararg paramTypes: Any) {
        try {
            XposedHelpers.findAndHookMethod(
                TARGET_CLASS,
                bootClassLoader,
                name,
                *paramTypes,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        val spoofed = StealthConstants.ROOT_SPOOF_PROPERTIES[key] ?: return
                        param.result = when (name) {
                            "getInt" -> spoofed.toIntOrNull() ?: param.args[1]
                            "getBoolean" -> spoofed == "1" || spoofed.equals("true", ignoreCase = true)
                            else -> spoofed
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: RootProperty.$name skip: ${e.message}")
        }
    }
}
