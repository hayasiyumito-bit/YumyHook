package com.yumito.yumyhook.xposed.stealth.hide

import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.runtime.HookReentryGuard
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.io.FileNotFoundException

/**
 * 对 CheckEmu 等使用的 Hook 探测路径伪装「不存在」：
 * - File.exists / isFile / canRead（主要向量）
 * - FileInputStream / FileOutputStream / FileReader 构造（路径 I/O 向量）
 * java.io.* 属 BootClassLoader，ClassLoader 须为 null。
 */
object SensitivePathStealthHook {

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            hookFileStatMethods()
            hookConstructor("java.io.FileInputStream", String::class.java)
            hookConstructor("java.io.FileInputStream", File::class.java)
            hookConstructor("java.io.FileOutputStream", String::class.java)
            hookConstructor("java.io.FileReader", String::class.java)
            hookConstructor("java.io.FileReader", File::class.java)
            installed = true
        }
    }

    private fun hookFileStatMethods() {
        val methods = listOf("exists", "isFile", "canRead", "canExecute")
        for (name in methods) {
            try {
                XposedHelpers.findAndHookMethod(
                    File::class.java,
                    name,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (HookReentryGuard.isFileBypass()) return
                            val path = pathOf(param.thisObject as File)
                            if (SensitivePathFilter.isHidden(path) || ProcFsPaths.isDenied(path)) {
                                param.result = false
                            }
                        }
                    },
                )
            } catch (e: Throwable) {
                XposedBridge.log("${XposedConstants.TAG}: SensitivePath.$name skip: ${e.message}")
            }
        }
    }

    private fun hookConstructor(className: String, pathArg: Class<*>) {
        try {
            XposedHelpers.findAndHookConstructor(
                className,
                null,
                pathArg,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (HookReentryGuard.isFileBypass()) return
                        val path = when (val arg = param.args[0]) {
                            is String -> arg
                            is File -> arg.absolutePath
                            else -> return
                        }
                        if (ProcFsPaths.isDenied(path) || SensitivePathFilter.isHidden(path)) {
                            param.throwable = FileNotFoundException(path)
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: SensitivePath $className skip: ${e.message}")
        }
    }

    private fun pathOf(file: File): String {
        return try {
            XposedHelpers.getObjectField(file, "path") as? String ?: file.path
        } catch (_: Throwable) {
            file.path
        }
    }
}
