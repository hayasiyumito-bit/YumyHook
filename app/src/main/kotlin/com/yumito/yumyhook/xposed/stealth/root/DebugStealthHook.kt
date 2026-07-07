package com.yumito.yumyhook.xposed.stealth.root
import android.content.pm.ApplicationInfo
import android.os.Debug
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * HackChecker.isDebug / collectDetectionReasons：
 * - ApplicationInfo.FLAG_DEBUGGABLE 清除
 * - Debug.isDebuggerConnected → false
 */
object DebugStealthHook {

    private val bootClassLoader: ClassLoader? = null
    private const val FLAG_DEBUGGABLE = ApplicationInfo.FLAG_DEBUGGABLE

    fun install(@Suppress("UNUSED_PARAMETER") lpparam: XC_LoadPackage.LoadPackageParam) {
        hookGetApplicationInfo("android.app.ContextImpl")
        hookGetApplicationInfo("android.app.ApplicationPackageManager")
        try {
            XposedHelpers.findAndHookMethod(
                Debug::class.java,
                "isDebuggerConnected",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = false
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: DebugStealth isDebuggerConnected skip: ${e.message}")
        }
        try {
            XposedHelpers.findAndHookMethod(
                Debug::class.java,
                "waitingForDebugger",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = false
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: DebugStealth waitingForDebugger skip: ${e.message}")
        }
    }

    private fun hookGetApplicationInfo(className: String) {
        val stripFlags = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val info = param.result as? ApplicationInfo ?: return
                info.flags = info.flags and FLAG_DEBUGGABLE.inv()
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                className,
                bootClassLoader,
                "getApplicationInfo",
                stripFlags,
            )
        } catch (_: Throwable) {
            try {
                XposedHelpers.findAndHookMethod(
                    className,
                    bootClassLoader,
                    "getApplicationInfo",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    stripFlags,
                )
            } catch (e: Throwable) {
                XposedBridge.log("${XposedConstants.TAG}: DebugStealth $className skip: ${e.message}")
            }
        }
    }
}
