package com.yumito.yumyhook.xposed.stealth.root

import com.yumito.yumyhook.xposed.channel.getprop.GetpropCommandParser
import com.yumito.yumyhook.xposed.channel.getprop.GetpropMerger
import com.yumito.yumyhook.xposed.channel.systemproperty.SystemPropertyMapper
import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.config.HookFeatureConfig
import com.yumito.yumyhook.xposed.policy.FourChannelGate
import com.yumito.yumyhook.xposed.runtime.HookReentryGuard
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File

/**
 * 拦截 Root / Magisk shell 探测（Runtime.exec / ProcessBuilder）。
 */
object ShellProbeStealthHook {

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installExecHooks()
            installed = true
        }
    }

    private fun installExecHooks() {
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
                    ShellProbeInterceptor(),
                )
            } catch (_: Throwable) {
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                ProcessBuilder::class.java,
                "start",
                ShellProbeProcessBuilderInterceptor(),
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: ShellProbe ProcessBuilder skip: ${e.message}")
        }
    }

    private fun emptyStdoutProcess(): Process {
        return HookReentryGuard.runShellProbeBypass {
            Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", ":"))
        }
    }

    private fun sanitizedProcess(command: String, argv: List<String>): Process? {
        val joined = if (argv.isNotEmpty()) argv.joinToString(" ") else command
        return when {
            ShellOutputFilter.isDfCommand(joined) -> {
                val raw = HookReentryGuard.runShellProbeBypass {
                    Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", joined))
                        .inputStream.bufferedReader().readText()
                }
                ShellOutputFilter.processWithStdout(ShellOutputFilter.filterDfOutput(raw))
            }
            ShellOutputFilter.isPsCommand(joined) -> {
                val raw = HookReentryGuard.runShellProbeBypass {
                    Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", joined))
                        .inputStream.bufferedReader().readText()
                }
                ShellOutputFilter.processWithStdout(ShellOutputFilter.filterPsOutput(raw))
            }
            ShellOutputFilter.isGetpropCommand(joined) -> {
                if (FourChannelGate.isActive()) {
                    spoofGetpropOutput(joined)
                } else {
                    val raw = HookReentryGuard.runShellProbeBypass {
                        Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", joined))
                            .inputStream.bufferedReader().readText()
                    }
                    ShellOutputFilter.processWithStdout(ShellOutputFilter.filterGetpropOutput(raw))
                }
            }
            else -> null
        }
    }

    private fun spoofGetpropOutput(command: String): Process {
        val key = GetpropCommandParser.parseKey(command)
        val values = HookConfig.refreshHookCacheIfStale()
        val hideRoot = HookFeatureConfig.current().hideRoot
        val output = if (key == null) {
            val raw = HookReentryGuard.runGetpropBypass {
                Runtime.getRuntime().exec(arrayOf("/system/bin/getprop"))
                    .inputStream.bufferedReader().readText()
            }
            GetpropMerger.merge(raw, values)
        } else {
            SystemPropertyMapper.resolveChannelValue(key, values, hideRoot).orEmpty()
        }
        return ShellOutputFilter.processWithStdout(output)
    }

    private fun shouldIntercept(command: String?): Boolean {
        if (command.isNullOrBlank()) return false
        val sub = ShellProbeFilter.extractShellSubcommand(command)
        return ShellProbeFilter.shouldSanitize(sub) || ShellProbeFilter.shouldSanitize(command)
    }

    private fun shouldInterceptArgv(parts: List<String>): Boolean {
        return ShellProbeFilter.shouldSanitizeArgv(parts)
    }

    private class ShellProbeInterceptor : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (HookReentryGuard.isShellProbeBypass()) return
            if (!HookReentryGuard.enter()) return
            try {
                val argv = argvText(param.args) ?: return
                val joined = argv.joinToString(" ")
                sanitizedProcess(joined, argv)?.let {
                    param.result = it
                    return
                }
                if (FourChannelGate.isActive() && GetpropCommandParser.isGetpropCommand(joined)) return
                if (!shouldInterceptArgv(argv) && !shouldIntercept(joined)) return
                param.result = emptyStdoutProcess()
            } finally {
                HookReentryGuard.exit()
            }
        }
    }

    private class ShellProbeProcessBuilderInterceptor : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (HookReentryGuard.isShellProbeBypass()) return
            if (!HookReentryGuard.enter()) return
            try {
                val builder = param.thisObject as ProcessBuilder
                val parts = builder.command()
                val joined = parts.joinToString(" ")
                sanitizedProcess(joined, parts)?.let {
                    param.result = it
                    return
                }
                if (!shouldInterceptArgv(parts)) return
                builder.command("/system/bin/sh", "-c", ":")
            } finally {
                HookReentryGuard.exit()
            }
        }
    }

    private fun argvText(args: Array<Any?>): List<String>? {
        return when (val first = args.firstOrNull()) {
            is String -> listOf(first)
            is Array<*> -> first.map { it.toString() }
            else -> null
        }
    }
}
