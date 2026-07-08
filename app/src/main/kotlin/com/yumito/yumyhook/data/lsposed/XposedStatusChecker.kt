package com.yumito.yumyhook.data.lsposed

import android.content.Context
import com.yumito.yumyhook.data.profile.HookProfilesStore
import com.yumito.yumyhook.model.ScopedAppEntry
import com.yumito.yumyhook.model.XposedStatus
import com.yumito.yumyhook.xposed.runtime.ModuleRuntimeState
import com.yumito.yumyhook.xposed.config.XposedConstants

/**
 * 检测 Xposed / LSPosed 状态。
 * 优先：进程注入标记 > Root 读 lspd DB > ContentProvider（先 resolve 避免 log 刷屏）。
 */
object XposedStatusChecker {

    fun check(context: Context, useRoot: Boolean = false): XposedStatus {
        HookProfilesStore.ensureDefaults(context)
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

        val lsposedActive = lspdPresent || managerInstalled || isLsPosedBridgeLoaded()
        val lsposedVersionLabel = LsposedConfigReader.resolveFrameworkVersion(context, useRoot)
            ?: if (lsposedActive) "已加载" else "未检测到"

        syncRuntimePrefs(context, hookedInProcess, frameworkVersion, moduleEnabled, frameworkActive, useRoot)
        val scopedPackages = LsposedConfigReader.readScopedPackages(context, useRoot = useRoot)
        val scopedApps = buildScopedApps(context, scopedPackages)
        val frameworkScopeEnabled = LsposedConfigReader.isFrameworkScoped(scopedPackages)
        val systemScopeEnabled = LsposedConfigReader.hasSystemScopeEntry(scopedPackages)
        val riskySystemScope = LsposedConfigReader.hasSystemScopeEntry(scopedPackages)
        val features = HookProfilesStore.loadFeatures(context)
        val stealthNeedsFrameworkScope = features.frameworkHideRoot ||
            features.frameworkHideMagisk ||
            features.hideRoot ||
            features.hideLsposed

        return XposedStatus(
            frameworkActive = frameworkActive,
            frameworkVersion = frameworkVersion,
            lsposedActive = lsposedActive,
            lsposedVersionLabel = lsposedVersionLabel,
            moduleEnabled = moduleEnabled,
            scopedApps = scopedApps,
            hookEnabled = HookProfilesStore.isHookEnabled(context),
            frameworkScopeEnabled = frameworkScopeEnabled,
            systemScopeEnabled = systemScopeEnabled,
            riskySystemScope = riskySystemScope,
            stealthNeedsFrameworkScope = stealthNeedsFrameworkScope,
        )
    }

    private fun buildScopedApps(context: Context, scopedPackages: List<String>): List<ScopedAppEntry> {
        if (scopedPackages.isEmpty()) return emptyList()
        return scopedPackages
            .filter { it !in XposedConstants.FRAMEWORK_SCOPE_PACKAGES }
            .distinct()
            .sorted()
            .map { pkg ->
                ScopedAppEntry(
                    label = LsposedConfigReader.resolveScopeLabel(context, pkg),
                    packageName = pkg,
                )
            }
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
