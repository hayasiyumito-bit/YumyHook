package com.yumito.yumyhook.xposed.stealth.hide

import com.yumito.yumyhook.xposed.runtime.HookReentryGuard
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 系统层：读取 /proc 敏感文件时重定向到过滤后的临时文件。
 * 覆盖 FileInputStream / FileReader / RandomAccessFile / Files.newInputStream。
 */
object ProcMapsStealthHook {

    fun install() {
        hookPathConstructor("java.io.FileInputStream", String::class.java)
        hookPathConstructor("java.io.FileReader", String::class.java)
        hookRandomAccessFile()
        hookFilesNewInputStream()
    }

    private fun hookPathConstructor(className: String, pathArg: Class<*>) {
        try {
            XposedHelpers.findAndHookConstructor(
                className,
                null,
                pathArg,
                ProcPathRedirectHook(),
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: ProcFsStealth $className skip: ${e.message}")
        }
    }

    private fun hookRandomAccessFile() {
        try {
            XposedHelpers.findAndHookConstructor(
                "java.io.RandomAccessFile",
                null,
                String::class.java,
                String::class.java,
                ProcPathRedirectHook(),
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: ProcFsStealth RandomAccessFile skip: ${e.message}")
        }
    }

    private fun hookFilesNewInputStream() {
        try {
            val pathClass = XposedHelpers.findClass("java.nio.file.Path", null)
            val openOptionsClass = XposedHelpers.findClass("[Ljava.nio.file.OpenOption;", null)
            XposedHelpers.findAndHookMethod(
                "java.nio.file.Files",
                null,
                "newInputStream",
                pathClass,
                openOptionsClass,
                ProcPathRedirectHook(pathArgIndex = 0),
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: ProcFsStealth Files.newInputStream skip: ${e.message}")
        }
    }

    private class ProcPathRedirectHook(
        private val pathArgIndex: Int = 0,
    ) : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (HookReentryGuard.isMapsBypass()) return
            val path = pathText(param.args.getOrNull(pathArgIndex)) ?: return
            if (!ProcFsPaths.isSensitive(path)) return
            val filteredPath = ProcFsRedirect.redirectPath(path) ?: return
            if (pathArgIndex == 0 && param.args[0] is String) {
                param.args[0] = filteredPath
                return
            }
            try {
                val pathClass = XposedHelpers.findClass("java.nio.file.Paths", null)
                param.args[pathArgIndex] = XposedHelpers.callStaticMethod(
                    pathClass,
                    "get",
                    filteredPath,
                    arrayOf<String>(),
                )
            } catch (e: Throwable) {
                XposedBridge.log("${XposedConstants.TAG}: ProcFsStealth path redirect failed: ${e.message}")
            }
        }

        private fun pathText(arg: Any?): String? {
            return when (arg) {
                is String -> arg
                null -> null
                else -> try {
                    XposedHelpers.callMethod(arg, "toString") as? String
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }
}
