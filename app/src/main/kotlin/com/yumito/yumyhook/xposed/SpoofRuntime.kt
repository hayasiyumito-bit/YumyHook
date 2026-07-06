package com.yumito.yumyhook.xposed

import android.content.Context
import de.robv.android.xposed.XposedBridge

/** 进程内 apply 伪装 Build 字段；配置通过系统层 Hook 刷新，不依赖目标 App 业务类。 */
object SpoofRuntime {

    @Volatile
    private var lastAppliedToken: String = ""

    /**
     * handleLoadPackage 尽早对齐四通道（Build + JNI 属性表）。
     * ContentProvider 探测常早于 Application.onCreate，不能等 lifecycle。
     */
    fun applyChannelsEarly(reason: String) {
        val features = HookFeatureConfig.current()
        if (!HookConfig.isEnabledForHook()) return
        if (!features.spoofBuildProperties) return
        val values = HookConfig.valuesForHook()
        if (values.buildFields.isEmpty()) return

        OsBuildPatcher.apply(values)
        lastAppliedToken = values.revisionToken()
        if (NativeHookPolicy.shouldInstallNative(TargetContextHolder.packageName, features)) {
            NativeBridge.syncProperties(values, null)
        }
        XposedBridge.log(
            "${XposedConstants.TAG}: channels early ($reason) " +
                "MODEL=${values.getBuildField("MODEL")} props=${SystemPropertyMapper.mapProperty("ro.product.model", values)}",
        )
    }

    fun refreshAndApply(context: Context?, reason: String) {
        reapplyIfRevisionChanged(HookConfig.sanitize(HookConfig.refreshHookCacheIfStale()), reason, context)
    }

    /** 配置刷新后重打 Build / Native，无需杀目标进程。 */
    fun reapplyIfRevisionChanged(values: HookSpoofValues, reason: String, context: Context? = null) {
        val features = HookFeatureConfig.refreshIfStale()
        if (NativeHookPolicy.shouldInstallNative(TargetContextHolder.packageName, features)) {
            NativeBridge.syncProperties(values, context)
        }
        if (!HookConfig.isEnabledForHook()) return
        if (!features.shouldSpoofBuild()) return
        if (values.buildFields.isEmpty()) return
        val token = values.revisionToken()
        if (token == lastAppliedToken) return
        OsBuildPatcher.apply(values)
        lastAppliedToken = token
        XposedBridge.log(
            "${XposedConstants.TAG}: spoof applied ($reason) " +
                "profile=${values.profileLabel} MODEL=${values.getBuildField("MODEL")} at=${values.updatedAt}",
        )
    }
}
