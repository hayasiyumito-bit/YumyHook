package com.yumito.yumyhook.xposed.channel.getprop

import com.yumito.yumyhook.xposed.channel.systemproperty.SystemPropertyMapper
import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.config.HookFeatureConfig
import com.yumito.yumyhook.xposed.config.HookSpoofValues
import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.policy.FourChannelGate
import com.yumito.yumyhook.xposed.runtime.HookReentryGuard
import com.yumito.yumyhook.xposed.stealth.common.StealthConstants
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

    private fun spoofOutput(key: String?, values: HookSpoofValues): String {
        if (key.isNullOrBlank()) {
            return GetpropMerger.merge(runRealGetprop(), values)
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

    private fun applySpoofedGetprop(key: String?, apply: (Array<String>) -> Unit): Boolean {
        if (key != null && HookFeatureConfig.current().hideRoot) {
            StealthConstants.ROOT_SPOOF_PROPERTIES[key]?.let { spoofed ->
                apply(shellCommand(spoofed))
                return true
            }
        }
        if (key != null && !SystemPropertyMapper.hasMapping(key)) return false
        val values = HookConfig.refreshHookCacheIfStale()
        apply(shellCommand(spoofOutput(key, values)))
        return true
    }

    private class GetpropInterceptor : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (HookReentryGuard.isGetpropBypass()) return
            if (!HookReentryGuard.enter()) return
            try {
                if (!FourChannelGate.isActive()) return
                val parsed = parseExecArgs(param.args) ?: return
                if (!applySpoofedGetprop(parsed.second) { param.result = Runtime.getRuntime().exec(it) }) return
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
                if (!FourChannelGate.isActive()) return
                val builder = param.thisObject as ProcessBuilder
                val parsed = parseCommandParts(builder.command()) ?: return
                if (!applySpoofedGetprop(parsed.second) { builder.command(*it) }) return
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
