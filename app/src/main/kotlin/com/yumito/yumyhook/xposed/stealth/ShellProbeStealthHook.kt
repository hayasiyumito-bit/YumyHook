package com.yumito.yumyhook.xposed.stealth

import com.yumito.yumyhook.xposed.runtime.HookReentryGuard
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File

/**
 * 拦截 CheckEmu.buildRootIndicators 中的 `Cmd.exe("which su")` 等 shell 探测。
 * Cmd.exe 使用 Runtime.exec(String[]{"/bin/sh","-c",cmd})。
 */
object ShellProbeStealthHook {

    fun install() {
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

    private fun shouldIntercept(command: String?): Boolean {
        if (command.isNullOrBlank()) return false
        val sub = ShellProbeFilter.extractShellSubcommand(command)
        return ShellProbeFilter.shouldSanitize(sub) || ShellProbeFilter.shouldSanitize(command)
    }

    private class ShellProbeInterceptor : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (HookReentryGuard.isShellProbeBypass()) return
            if (!HookReentryGuard.enter()) return
            try {
                val command = commandText(param.args) ?: return
                if (!shouldIntercept(command)) return
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
                val command = ShellProbeFilter.parseShellCommand(builder.command()) ?: return
                if (!shouldIntercept(command)) return
                builder.command("/system/bin/sh", "-c", ":")
            } finally {
                HookReentryGuard.exit()
            }
        }
    }

    private fun commandText(args: Array<Any?>): String? {
        return when (val first = args.firstOrNull()) {
            is String -> first
            is Array<*> -> ShellProbeFilter.parseShellCommand(first.map { it.toString() })
            else -> null
        }
    }
}
