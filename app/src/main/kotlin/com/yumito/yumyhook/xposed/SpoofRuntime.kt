package com.yumito.yumyhook.xposed

import android.content.Context
import de.robv.android.xposed.XposedBridge

/** 进程内 apply 伪装 Build 字段；配置通过系统层 Hook 刷新，不依赖目标 App 类。 */
object SpoofRuntime {

    @Volatile
    private var lastAppliedToken: String = ""

    fun refreshAndApply(context: Context?, reason: String) {
        val features = HookFeatureConfig.refreshIfStale()
        val values = HookConfig.sanitize(HookConfig.refreshHookCacheIfStale())
        if (NativeHookPolicy.shouldInstallNative(TargetContextHolder.packageName, features)) {
            NativeBridge.syncProperties(values, context)
        }
        if (!HookConfig.isEnabledForHook()) return
        if (!features.shouldSpoofBuild()) return
        if (values.buildFields.isEmpty()) return
        val token = values.revisionToken()
        if (token == lastAppliedToken) {
            return
        }
        OsBuildPatcher.apply(values)
        lastAppliedToken = token
        XposedBridge.log(
            "${XposedConstants.TAG}: spoof applied ($reason) " +
                "profile=${values.profileLabel} MODEL=${values.getBuildField("MODEL")} at=${values.updatedAt}"
        )
    }
}
