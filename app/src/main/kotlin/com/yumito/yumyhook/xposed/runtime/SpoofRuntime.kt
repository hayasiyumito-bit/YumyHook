package com.yumito.yumyhook.xposed.runtime

import com.yumito.yumyhook.xposed.channel.NativeBridge
import com.yumito.yumyhook.xposed.channel.OsBuildPatcher
import com.yumito.yumyhook.xposed.channel.SystemPropertyMapper
import com.yumito.yumyhook.xposed.channel.strategy.BuildApplyPhaseGate
import com.yumito.yumyhook.xposed.channel.strategy.InstallPhase
import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.config.HookFeatureConfig
import com.yumito.yumyhook.xposed.config.HookSpoofValues
import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.policy.FourChannelGate

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
        applyChannelsAtPhase(reason, InstallPhase.LOAD_PACKAGE, FourChannelGate.isActive())
    }

    fun applyChannelsAtPhase(reason: String, phase: InstallPhase, fourChannelActive: Boolean) {
        if (!HookConfig.isEnabledForHook()) return
        if (!fourChannelActive) return
        if (!BuildApplyPhaseGate.allows(TargetContextHolder.packageName, phase)) return
        val values = HookConfig.valuesForHook()
        if (values.buildFields.isEmpty()) return

        OsBuildPatcher.apply(values)
        lastAppliedToken = values.revisionToken()
        NativeBridge.syncFromGate(values, TargetContextHolder.packageName)
        XposedBridge.log(
            "${XposedConstants.TAG}: channels $reason " +
                "MODEL=${values.getBuildField("MODEL")} props=${SystemPropertyMapper.mapProperty("ro.product.model", values)}",
        )
    }

    fun refreshAndApply(context: Context?, reason: String) {
        val values = HookConfig.sanitize(HookConfig.refreshHookCacheIfStale())
        NativeBridge.syncFromGate(values, context?.packageName ?: TargetContextHolder.packageName)
        reapplyIfRevisionChanged(values, reason, context)
    }

    /** 配置刷新后重打 Build / Native，无需杀目标进程。 */
    fun reapplyIfRevisionChanged(values: HookSpoofValues, reason: String, context: Context? = null) {
        val features = HookFeatureConfig.refreshIfStale()
        NativeBridge.syncFromGate(values, context?.packageName ?: TargetContextHolder.packageName)
        if (!HookConfig.isEnabledForHook()) return
        if (!FourChannelGate.isActive()) return
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
