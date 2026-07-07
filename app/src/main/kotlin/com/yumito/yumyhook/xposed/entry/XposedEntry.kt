package com.yumito.yumyhook.xposed.entry

import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.runtime.ModulePathHolder
import com.yumito.yumyhook.xposed.runtime.ModuleRuntimeState

import android.app.Application
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.yumito.yumyhook.xposed.channel.SystemHookInstaller
import com.yumito.yumyhook.xposed.policy.HookScope
import com.yumito.yumyhook.data.profile.HookProfilesStore
import com.yumito.yumyhook.ProjectAttribution

/** LSPosed 入口：Zygote 记录模块路径，目标进程安装系统层 Hook。 */
class XposedEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == XposedConstants.MODULE_PACKAGE) {
            ProjectAttribution.emitXposedAttribution(XposedBridge::log)
            XposedBridge.log("${XposedConstants.TAG}: 模块自身已加载")
            installModuleSelfHooks()
            return
        }

        if (lpparam.packageName == HookScope.LSPOSED_SYSTEM_SCOPE_PACKAGE) {
            XposedBridge.log(
                "${XposedConstants.TAG}: skip system scope (勾选 android 即可，system 会导致崩溃)",
            )
            return
        }

        if (!SystemHookInstaller.shouldHook(lpparam.packageName)) return

        XposedBridge.log("${XposedConstants.TAG}: 系统层 Hook → ${lpparam.packageName}")
        SystemHookInstaller.install(lpparam)
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        ModulePathHolder.moduleApkPath = startupParam.modulePath
        ProjectAttribution.emitXposedAttribution(XposedBridge::log)
        XposedBridge.log("${XposedConstants.TAG}: Zygote init, path=${startupParam.modulePath}")
    }

    private fun installModuleSelfHooks() {
        ModuleRuntimeState.markHooked()
        installPrefsCommitHook()
        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as Application
                        if (app.packageName != XposedConstants.MODULE_PACKAGE) return
                        ensureModuleDefaults(app)
                        markModuleRuntimeActive(app)
                        makeModulePrefsReadable()
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: 模块自身 Hook 失败: ${e.message}")
        }
    }

    /** 每次模块写 hook_config 后重新 makeWorldReadable，避免目标进程 XSP 读空。 */
    private fun installPrefsCommitHook() {
        try {
            val editorClass = XposedHelpers.findClass(
                "android.app.SharedPreferencesImpl\$EditorImpl",
                null,
            )
            XposedHelpers.findAndHookMethod(
                editorClass,
                "commit",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result != true) return
                        val outer = XposedHelpers.getObjectField(param.thisObject, "this$0")
                        val file = XposedHelpers.getObjectField(outer, "mFile") as java.io.File
                        if (!file.name.startsWith(XposedConstants.PREFS_NAME)) return
                        makeModulePrefsReadable()
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: prefs commit hook skip: ${e.message}")
        }
    }

    private fun ensureModuleDefaults(app: Application) {
        try {
            HookProfilesStore.loadDocument(app)
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: ensureModuleDefaults failed: ${e.message}")
        }
    }

    private fun markModuleRuntimeActive(app: Application) {
        ModuleRuntimeState.markHooked()
        app.getSharedPreferences(XposedConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(XposedConstants.PREF_RUNTIME_ACTIVE, true)
            .putBoolean(XposedConstants.PREF_LSPOSED_INJECTED, true)
            .putInt(XposedConstants.PREF_RUNTIME_VERSION, XposedBridge.getXposedVersion())
            .commit()
    }

    private fun makeModulePrefsReadable() {
        try {
            val prefs = de.robv.android.xposed.XSharedPreferences(
                XposedConstants.MODULE_PACKAGE,
                XposedConstants.PREFS_NAME,
            )
            prefs.makeWorldReadable()
            prefs.reload()
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: makeWorldReadable failed: ${e.message}")
        }
    }
}
