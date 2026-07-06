package com.yumito.yumyhook.xposed.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.yumito.yumyhook.xposed.HookConfig
import com.yumito.yumyhook.xposed.HookReentryGuard
import com.yumito.yumyhook.xposed.SystemPropertyMapper
import com.yumito.yumyhook.xposed.XposedConstants

/** Hook android.os.SystemProperties.get* — 仅用内存缓存，防重入。 */
object SystemPropertiesHook {

    private const val TARGET_CLASS = "android.os.SystemProperties"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        installGetter(lpparam, "get", String::class.java)
        installGetter(lpparam, "get", String::class.java, String::class.java)
        installGetter(lpparam, "getInt", String::class.java, Integer.TYPE)
        installGetter(lpparam, "getLong", String::class.java, java.lang.Long.TYPE)
        installGetter(lpparam, "getBoolean", String::class.java, java.lang.Boolean.TYPE)
    }

    private fun installGetter(lpparam: XC_LoadPackage.LoadPackageParam, name: String, vararg paramTypes: Any) {
        try {
            XposedHelpers.findAndHookMethod(
                TARGET_CLASS,
                lpparam.classLoader,
                name,
                *paramTypes,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        if (!SystemPropertyMapper.hasMapping(key)) return
                        if (!HookReentryGuard.enter()) return
                        try {
                            if (!HookConfig.isEnabledForHook()) return
                            val values = HookConfig.refreshHookCacheIfStale()
                            val spoofed = SystemPropertyMapper.mapProperty(key, values)
                                ?: return
                            param.result = when (name) {
                                "getInt" -> spoofed.toIntOrNull() ?: param.args[1]
                                "getLong" -> spoofed.toLongOrNull() ?: param.args[1]
                                "getBoolean" -> spoofed.equals("true", ignoreCase = true) ||
                                    spoofed == "1"
                                else -> spoofed
                            }
                        } finally {
                            HookReentryGuard.exit()
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: SystemProperties.$name hook skip: ${e.message}")
        }
    }
}
