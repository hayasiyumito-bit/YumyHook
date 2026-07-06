package com.yumito.yumyhook.xposed.hook

import com.yumito.yumyhook.xposed.GetpropMerger
import com.yumito.yumyhook.xposed.HookConfig
import com.yumito.yumyhook.xposed.HookReentryGuard
import com.yumito.yumyhook.xposed.SystemPropertyMapper
import com.yumito.yumyhook.xposed.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/** 拦截 shell getprop；单 key 伪装，全量 getprop 合并真实输出。 */
object GetpropHook {

    fun install(@Suppress("UNUSED_PARAMETER") lpparam: XC_LoadPackage.LoadPackageParam) {
        val runtimeClass = Runtime::class.java
        val execSignatures: Array<Array<Any>> = arrayOf(
            arrayOf(String::class.java),
            arrayOf(String::class.java, Array<String>::class.java),
            arrayOf(String::class.java, Array<String>::class.java, File::class.java),
            arrayOf(Array<String>::class.java),
            arrayOf(Array<String>::class.java, Array<String>::class.java),
            arrayOf(Array<String>::class.java, Array<String>::class.java, File::class.java),
        )
        for (signature in execSignatures) {
            try {
                XposedHelpers.findAndHookMethod(
                    runtimeClass,
                    "exec",
                    *signature,
                    GetpropInterceptor(),
                )
            } catch (_: Throwable) {
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                ProcessBuilder::class.java,
                "start",
                GetpropProcessBuilderInterceptor(),
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: ProcessBuilder.start hook skip: ${e.message}")
        }
    }

    private fun shellCommand(output: String): Array<String> {
        val escaped = output.replace("'", "'\\''")
        return arrayOf("/system/bin/sh", "-c", "printf '%s' '$escaped'")
    }

    private fun spoofOutput(key: String?, values: com.yumito.yumyhook.xposed.HookSpoofValues): String {
        if (key.isNullOrBlank()) {
            val real = runRealGetprop()
            return GetpropMerger.merge(real, values)
        }
        return SystemPropertyMapper.mapProperty(key, values) ?: ""
    }

    private fun runRealGetprop(): String {
        return HookReentryGuard.runGetpropBypass {
            val process = ProcessBuilder("/system/bin/getprop")
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            text
        }
    }

    private class GetpropInterceptor : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (HookReentryGuard.isGetpropBypass()) return
            if (!HookReentryGuard.enter()) return
            try {
                if (!HookConfig.isEnabledForHook()) return
                val parsed = parseExecArgs(param.args) ?: return
                val key = parsed.second
                if (key != null && !SystemPropertyMapper.hasMapping(key)) return
                val values = HookConfig.refreshHookCacheIfStale()
                val output = spoofOutput(key, values)
                param.result = Runtime.getRuntime().exec(shellCommand(output))
            } finally {
                HookReentryGuard.exit()
            }
        }
    }

    private class GetpropProcessBuilderInterceptor : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (HookReentryGuard.isGetpropBypass()) return
            if (!HookReentryGuard.enter()) return
            try {
                if (!HookConfig.isEnabledForHook()) return
                val builder = param.thisObject as ProcessBuilder
                val parsed = parseCommandParts(builder.command()) ?: return
                val key = parsed.second
                if (key != null && !SystemPropertyMapper.hasMapping(key)) return
                val values = HookConfig.refreshHookCacheIfStale()
                val output = spoofOutput(key, values)
                builder.command(*shellCommand(output))
            } finally {
                HookReentryGuard.exit()
            }
        }
    }

    private fun parseExecArgs(args: Array<Any?>): Pair<Boolean, String?>? {
        return when (val first = args.firstOrNull()) {
            is String -> parseCommandText(first)
            is Array<*> -> parseCommandParts(first.map { it.toString() })
            else -> null
        }
    }

    private fun parseCommandParts(parts: List<String>): Pair<Boolean, String?>? {
        if (parts.isEmpty()) return null
        return parseCommandText(parts.joinToString(" "))
    }

    private fun parseCommandText(command: String): Pair<Boolean, String?>? {
        val trimmed = command.trim()
        if (!trimmed.contains("getprop")) return null
        val tokens = trimmed.split(Regex("\\s+"))
        val getpropIndex = tokens.indexOfFirst { it == "getprop" || it.endsWith("/getprop") }
        if (getpropIndex < 0) return true to null
        val key = tokens.getOrNull(getpropIndex + 1)?.takeIf { !it.startsWith("-") }
        return true to key
    }
}
