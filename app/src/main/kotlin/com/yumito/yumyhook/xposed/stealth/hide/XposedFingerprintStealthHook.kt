package com.yumito.yumyhook.xposed.stealth.hide

import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 隐藏 Xposed / LSPosed Java 指纹。
 * 仅洗栈帧；不 hook Class.forName / loadClass（会破坏 QQ 等 QRoute 反射）。
 */
object XposedFingerprintStealthHook {

    private val BLOCKED_CLASS_PREFIXES = listOf(
        "de.robv.android.xposed.",
        "org.lsposed.",
        "io.github.lsposed.",
        "com.elderdrivers.riru.",
        "com.elderdrivers.edxp.",
    )

    private val BLOCKED_EXACT_CLASSES = setOf(
        "de.robv.android.xposed.XposedBridge",
        "de.robv.android.xposed.XposedHelpers",
        "org.lsposed.lspd.impl.LSPosedBridge",
    )

    fun install() {
        hookStackTraces()
    }

    private fun hookStackTraces() {
        val sanitizer = StackTraceSanitizer()
        try {
            XposedHelpers.findAndHookMethod(
                Throwable::class.java,
                "getStackTrace",
                sanitizer,
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: XposedFingerprint throwable stack skip: ${e.message}")
        }
        try {
            XposedHelpers.findAndHookMethod(
                Thread::class.java,
                "getStackTrace",
                sanitizer,
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: XposedFingerprint thread stack skip: ${e.message}")
        }
    }

    internal fun isBlockedClassName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        if (name in BLOCKED_EXACT_CLASSES) return true
        return BLOCKED_CLASS_PREFIXES.any { prefix -> name.startsWith(prefix) }
    }

    internal fun sanitizeStackTrace(frames: Array<StackTraceElement>): Array<StackTraceElement> {
        return frames.filterNot { frame -> isBlockedClassName(frame.className) }
            .toTypedArray()
    }

    private class StackTraceSanitizer : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val frames = param.result as? Array<*> ?: return
            val typed = frames.filterIsInstance<StackTraceElement>().toTypedArray()
            param.result = sanitizeStackTrace(typed)
        }
    }
}
