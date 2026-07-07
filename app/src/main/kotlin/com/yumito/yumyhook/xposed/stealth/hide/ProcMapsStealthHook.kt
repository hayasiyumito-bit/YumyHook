package com.yumito.yumyhook.xposed.stealth.hide

import com.yumito.yumyhook.xposed.runtime.HookReentryGuard
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.io.FileNotFoundException

/**
 * 系统层：读取 /proc 敏感文件时重定向到过滤后的临时文件。
 */
object ProcMapsStealthHook {

    fun install() {
        hookPathConstructor("java.io.FileInputStream", String::class.java)
        hookPathConstructor("java.io.FileInputStream", File::class.java)
        hookPathConstructor("java.io.FileReader", String::class.java)
        hookPathConstructor("java.io.FileReader", File::class.java)
        hookRandomAccessFile()
        hookFilesIo()
    }

    private fun hookPathConstructor(className: String, pathArg: Class<*>) {
        try {
            XposedHelpers.findAndHookConstructor(
                className,
                null,
                pathArg,
                ProcPathRedirectHook(pathArg),
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
                ProcPathRedirectHook(String::class.java),
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: ProcFsStealth RandomAccessFile skip: ${e.message}")
        }
    }

    private fun hookFilesIo() {
        val pathClass = runCatching { XposedHelpers.findClass("java.nio.file.Path", null) }.getOrNull() ?: return
        val openOptionsClass = runCatching {
            XposedHelpers.findClass("[Ljava.nio.file.OpenOption;", null)
        }.getOrNull() ?: return
        val redirect = ProcPathRedirectHook(pathClass, pathArgIndex = 0)
        try {
            XposedHelpers.findAndHookMethod(
                "java.nio.file.Files",
                null,
                "newInputStream",
                pathClass,
                openOptionsClass,
                redirect,
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: ProcFsStealth Files.newInputStream skip: ${e.message}")
        }
        try {
            XposedHelpers.findAndHookMethod(
                "java.nio.file.Files",
                null,
                "readAllLines",
                pathClass,
                redirect,
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: ProcFsStealth Files.readAllLines skip: ${e.message}")
        }
        try {
            XposedHelpers.findAndHookMethod(
                "java.nio.file.Files",
                null,
                "newBufferedReader",
                pathClass,
                redirect,
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: ProcFsStealth Files.newBufferedReader skip: ${e.message}")
        }
        val charsetClass = runCatching { XposedHelpers.findClass("java.nio.charset.Charset", null) }.getOrNull()
        if (charsetClass != null) {
            try {
                XposedHelpers.findAndHookMethod(
                    "java.nio.file.Files",
                    null,
                    "readAllLines",
                    pathClass,
                    charsetClass,
                    redirect,
                )
            } catch (e: Throwable) {
                XposedBridge.log("${XposedConstants.TAG}: ProcFsStealth Files.readAllLines+charset skip: ${e.message}")
            }
            try {
                XposedHelpers.findAndHookMethod(
                    "java.nio.file.Files",
                    null,
                    "readString",
                    pathClass,
                    charsetClass,
                    redirect,
                )
            } catch (e: Throwable) {
                XposedBridge.log("${XposedConstants.TAG}: ProcFsStealth Files.readString skip: ${e.message}")
            }
        }
    }

    private class ProcPathRedirectHook(
        private val pathArg: Class<*>,
        private val pathArgIndex: Int = 0,
    ) : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (HookReentryGuard.isMapsBypass()) return
            val path = pathText(param.args.getOrNull(pathArgIndex)) ?: return
            if (ProcFsPaths.isDenied(path)) {
                param.throwable = FileNotFoundException(path)
                return
            }
            if (!ProcFsPaths.isSensitive(path)) return
            val filteredPath = ProcFsRedirect.redirectPath(path) ?: return
            if (pathArg == String::class.java) {
                param.args[pathArgIndex] = filteredPath
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
                is File -> arg.absolutePath
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
