package com.yumito.yumyhook.util

import android.content.Context
import com.yumito.yumyhook.model.XposedStatus
import com.yumito.yumyhook.xposed.ModuleRuntimeState
import com.yumito.yumyhook.xposed.XposedConstants

/**
 * 检测 Xposed / LSPosed 状态。
 * 优先：进程注入标记 > Root 读 lspd DB > ContentProvider（先 resolve 避免 log 刷屏）。
 */
object XposedStatusChecker {

    fun check(context: Context, useRoot: Boolean = false): XposedStatus {
        HookPrefs.ensureDefaults(context)
        val prefs = context.getSharedPreferences(XposedConstants.PREFS_NAME, Context.MODE_PRIVATE)

        val hookedInProcess = isHookedInCurrentProcess()
        val bridgeVersion = getXposedBridgeVersion()
        val lspdPresent = LsposedConfigReader.isFrameworkPresent(useRoot)
        val managerInstalled = LsposedConfigReader.isManagerInstalled(context)
        val runtimeActive = prefs.getBoolean(XposedConstants.PREF_RUNTIME_ACTIVE, false)
        val cachedModuleEnabled = prefs.getBoolean(XposedConstants.PREF_MODULE_ENABLED_CACHE, false)
        val cachedFrameworkActive = prefs.getBoolean(XposedConstants.PREF_FRAMEWORK_ACTIVE_CACHE, false)

        val frameworkVersion = when {
            bridgeVersion > 0 -> bridgeVersion
            hookedInProcess -> prefs.getInt(XposedConstants.PREF_RUNTIME_VERSION, XposedConstants.XPOSED_API_VERSION)
            lspdPresent -> XposedConstants.XPOSED_API_VERSION
            isLsPosedBridgeLoaded() -> XposedConstants.XPOSED_API_VERSION
            cachedFrameworkActive && !useRoot -> XposedConstants.XPOSED_API_VERSION
            else -> 0
        }

        val rootState = LsposedConfigReader.readModuleState(context, useRoot = useRoot)
        val providerEnabled = LsposedConfigReader.queryModuleEnabledViaProvider(context, context.packageName)
        val persistedInjected = prefs.getBoolean(XposedConstants.PREF_LSPOSED_INJECTED, false)

        val moduleEnabled = when {
            hookedInProcess -> true
            providerEnabled == true -> true
            rootState?.enabled == true -> true
            runtimeActive -> true
            persistedInjected -> true
            cachedModuleEnabled && !useRoot -> true
            providerEnabled == false -> false
            rootState?.enabled == false -> false
            else -> false
        }

        val frameworkActive = frameworkVersion > 0 || lspdPresent || managerInstalled ||
            (cachedFrameworkActive && !useRoot)

        syncRuntimePrefs(context, hookedInProcess, frameworkVersion, moduleEnabled, frameworkActive, useRoot)
        val scopeHint = buildScopeHint(rootState?.scopedPackages.orEmpty())

        return XposedStatus(
            frameworkActive = frameworkActive,
            frameworkVersion = frameworkVersion,
            moduleEnabled = moduleEnabled,
            targetPackage = scopeHint,
            hookEnabled = HookPrefs.isHookEnabled(context),
        )
    }

    private fun buildScopeHint(scopedPackages: List<String>): String {
        if (scopedPackages.isEmpty()) return XposedConstants.HOOK_SCOPE_HINT
        val preview = scopedPackages.take(4).joinToString()
        val suffix = if (scopedPackages.size > 4) " 等${scopedPackages.size}个" else ""
        return "LSPosed 作用域：$preview$suffix"
    }

    private fun isHookedInCurrentProcess(): Boolean {
        if (ModuleRuntimeState.isHookedThisProcess()) return true
        return getXposedBridgeVersion() > 0
    }

    private fun syncRuntimePrefs(
        context: Context,
        hookedInProcess: Boolean,
        frameworkVersion: Int,
        moduleEnabled: Boolean,
        frameworkActive: Boolean,
        useRoot: Boolean,
    ) {
        val editor = context.getSharedPreferences(XposedConstants.PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (hookedInProcess) {
            editor.putBoolean(XposedConstants.PREF_LSPOSED_INJECTED, true)
            editor.putBoolean(XposedConstants.PREF_RUNTIME_ACTIVE, true)
            editor.putInt(
                XposedConstants.PREF_RUNTIME_VERSION,
                if (frameworkVersion > 0) frameworkVersion else XposedConstants.XPOSED_API_VERSION,
            )
        } else if (moduleEnabled) {
            editor.putBoolean(XposedConstants.PREF_RUNTIME_ACTIVE, true)
        }
        if (useRoot) {
            editor.putBoolean(XposedConstants.PREF_MODULE_ENABLED_CACHE, moduleEnabled)
            editor.putBoolean(XposedConstants.PREF_FRAMEWORK_ACTIVE_CACHE, frameworkActive)
        }
        editor.apply()
    }

    private fun getXposedBridgeVersion(): Int {
        for (loader in classLoaders()) {
            try {
                val bridge = Class.forName("de.robv.android.xposed.XposedBridge", false, loader)
                val version = bridge.getDeclaredMethod("getXposedVersion").invoke(null) as? Int
                if (version != null && version > 0) return version
            } catch (_: Throwable) {
            }
        }
        return 0
    }

    private fun classLoaders(): Sequence<ClassLoader> = sequenceOf(
        ClassLoader.getSystemClassLoader(),
        ClassLoader.getSystemClassLoader()?.parent,
        Thread.currentThread().contextClassLoader,
        XposedStatusChecker::class.java.classLoader,
    ).filterNotNull().distinct()

    private fun isLsPosedBridgeLoaded(): Boolean {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java, String::class.java)
            val bridge = get.invoke(null, "ro.dalvik.vm.native.bridge", "") as String
            bridge.contains("lspd", ignoreCase = true) ||
                bridge.contains("xposed", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }
}
