package com.yumito.yumyhook.xposed.hook

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.yumito.yumyhook.xposed.HookConfig
import com.yumito.yumyhook.xposed.HookFeatureConfig
import com.yumito.yumyhook.xposed.NativeHookPolicy
import com.yumito.yumyhook.xposed.HookScope
import com.yumito.yumyhook.xposed.TargetContextHolder
import com.yumito.yumyhook.xposed.XposedConstants
import com.yumito.yumyhook.xposed.hook.stealth.DeferredStealthInstaller
import com.yumito.yumyhook.xposed.hook.stealth.FeatureStealthInstaller

/** 系统层 Hook 统一安装入口（不 Hook 任何目标 App 业务类）。 */
object SystemHookInstaller {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        TargetContextHolder.packageName = lpparam.packageName
        HookConfig.refreshHookCache()
        HookFeatureConfig.refresh()
        val features = HookFeatureConfig.current()
        val nativeForPkg = NativeHookPolicy.shouldInstallNative(lpparam.packageName, features)
        XposedBridge.log(
            "${XposedConstants.TAG}: hooks installed rev=${XposedConstants.HOOK_REV} pkg=${lpparam.packageName} native=$nativeForPkg",
        )
        if (features.spoofBuildProperties) {
            OsBuildHook.install(lpparam)
            SystemPropertiesHook.install(lpparam)
            GetpropHook.install(lpparam)
        }
        FeatureStealthInstaller.install(lpparam)
        if (nativeForPkg) {
            DeferredStealthInstaller.schedule(lpparam)
        }
    }

    fun shouldHook(packageName: String): Boolean = HookScope.shouldHook(packageName)
}
