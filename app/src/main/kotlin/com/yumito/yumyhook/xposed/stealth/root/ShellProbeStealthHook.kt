package com.yumito.yumyhook.xposed.stealth.root
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
                if (!shouldInterceptArgv(argv) && !shouldIntercept(argv.joinToString(" "))) return
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
