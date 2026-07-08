package com.yumito.yumyhook.xposed.channel.systemproperty

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.policy.FourChannelGate
import com.yumito.yumyhook.xposed.runtime.HookReentryGuard
import com.yumito.yumyhook.xposed.config.XposedConstants

/** Hook android.os.SystemProperties.get* — 仅用内存缓存，防重入。 */
object SystemPropertiesHook {

    private const val TARGET_CLASS = "android.os.SystemProperties"

    @Volatile
    private var hostPackage: String = ""

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        hostPackage = lpparam.packageName
        val signatures = listOf(
            arrayOf<Any>("get", String::class.java),
            arrayOf<Any>("get", String::class.java, String::class.java),
            arrayOf<Any>("getInt", String::class.java, Integer.TYPE),
            arrayOf<Any>("getLong", String::class.java, java.lang.Long.TYPE),
            arrayOf<Any>("getBoolean", String::class.java, java.lang.Boolean.TYPE),
        )
        val bootOk = hookSignatures(null, signatures, "boot")
        val appOk = hookSignatures(lpparam.classLoader, signatures, "app")
        if (!bootOk && !appOk) {
            XposedBridge.log("${XposedConstants.TAG}: SystemProperties hook failed pkg=$hostPackage")
        }
    }

    private fun hookSignatures(classLoader: ClassLoader?, signatures: List<Array<Any>>, tag: String): Boolean {
        var ok = 0
        for (params in signatures) {
            val name = params[0] as String
            val types = params.drop(1).toTypedArray()
            if (installGetter(classLoader, name, *types)) ok++
        }
        if (ok > 0) {
            XposedBridge.log("${XposedConstants.TAG}: SystemProperties hooked via $tag loader ($ok/${signatures.size})")
        }
        return ok > 0
    }

    private fun installGetter(classLoader: ClassLoader?, name: String, vararg paramTypes: Any): Boolean {
        return try {
            XposedHelpers.findAndHookMethod(
                TARGET_CLASS,
                classLoader,
                name,
                *paramTypes,
                object : XC_MethodHook(PRIORITY_LOWEST) {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        if (!SystemPropertyMapper.hasMapping(key)) return
                        if (!HookReentryGuard.enter()) return
                        try {
                            if (!FourChannelGate.isActive(hostPackage)) return
                            val values = HookConfig.refreshHookCacheIfStale()
                            val spoofed = SystemPropertyMapper.resolveChannelValue(
                                key,
                                values,
                                HookConfig.features().hideRoot,
                            ) ?: return
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
            true
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: SystemProperties.$name hook skip ($classLoader): ${e.message}")
            false
        }
    }
}
