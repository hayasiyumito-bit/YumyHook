package com.yumito.yumyhook.xposed.stealth.hide

import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** 对齐 Java File 与 Native access/stat 探测结果，避免 javaNativeMismatches。 */
object NativeApiStealthHook {

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            hookOsMethod("access", String::class.java, Integer.TYPE)
            hookOsMethod("faccessat", Integer.TYPE, String::class.java, Integer.TYPE, Integer.TYPE)
            hookOsMethod("stat", String::class.java)
            hookOsMethod("lstat", String::class.java)
            hookOsMethod("readlink", String::class.java)
            hookOsMethod("readlinkat", Integer.TYPE, String::class.java)
            installed = true
        }
    }

    private fun hookOsMethod(name: String, vararg params: Any) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.system.Os",
                null,
                name,
                *params,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val path = pathArgForOsMethod(name, param.args) ?: return
                        if (!SensitivePathStealthHook.isHidden(path)) return
                        param.throwable = android.system.ErrnoException(
                            name,
                            android.system.OsConstants.ENOENT,
                        )
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: Os.$name skip: ${e.message}")
        }
    }

    private fun pathArgForOsMethod(name: String, args: Array<Any?>): String? {
        return when (name) {
            "faccessat" -> args.getOrNull(1) as? String
            else -> args.firstOrNull() as? String
        }
    }
}
