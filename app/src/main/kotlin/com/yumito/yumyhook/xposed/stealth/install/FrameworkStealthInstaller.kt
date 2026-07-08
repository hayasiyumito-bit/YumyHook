package com.yumito.yumyhook.xposed.stealth.install

import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.policy.HookScope
import com.yumito.yumyhook.xposed.stealth.root.RootStealthHook
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * system_server（LSPosed 作用域 android）专用：仅装低风险 Hook。
 * 禁止 ProcMaps / Shell / File / Native / 四通道。
 * 延后到 SystemServer 启动完成后再装，避免早期 system_server 崩溃。
 */
object FrameworkStealthInstaller {

    private val hooksInstalled = AtomicBoolean(false)
    private val deferHooked = AtomicBoolean(false)

    @Volatile
    private var pendingLpparam: XC_LoadPackage.LoadPackageParam? = null

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!HookScope.isFrameworkHookTarget(lpparam.packageName)) return
        if (!deferHooked.compareAndSet(false, true)) return
        pendingLpparam = lpparam
        val deferred = scheduleAfterSystemReady(lpparam.classLoader)
        if (!deferred) {
            installHooks(lpparam, "immediate-fallback")
        }
    }

    private fun scheduleAfterSystemReady(classLoader: ClassLoader): Boolean {
        for (method in SYSTEM_READY_METHODS) {
            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.server.SystemServer",
                    classLoader,
                    method,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val lp = pendingLpparam
                            if (lp != null) {
                                installHooks(lp, "SystemServer.$method")
                            }
                        }
                    },
                )
                XposedBridge.log("${XposedConstants.TAG}: framework stealth deferred until SystemServer.$method")
                return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun installHooks(lpparam: XC_LoadPackage.LoadPackageParam, source: String) {
        if (!hooksInstalled.compareAndSet(false, true)) return
        try {
            val f = HookConfig.features()
            XposedBridge.log(
                "${XposedConstants.TAG}: framework stealth rev=${XposedConstants.HOOK_REV} " +
                    "src=$source fwRoot=${f.frameworkHideRoot} fwMagisk=${f.frameworkHideMagisk}",
            )
            if (f.frameworkHideRoot) {
                RootStealthHook.install(lpparam)
            }
            if (f.frameworkHideMagisk) {
                FrameworkPackageStealthHook.install(lpparam)
            }
            XposedBridge.log("${XposedConstants.TAG}: framework stealth installed")
        } catch (e: Throwable) {
            hooksInstalled.set(false)
            XposedBridge.log("${XposedConstants.TAG}: framework stealth failed: ${e.message}")
        }
    }

    private val SYSTEM_READY_METHODS = arrayOf("startOtherServices", "startBootstrapServices")
}
