package com.yumito.yumyhook.xposed.policy

import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.config.HookFeatureConfig
import com.yumito.yumyhook.xposed.runtime.TargetContextHolder

import com.yumito.yumyhook.model.HookFeatures

/**
 * 四通道运行时门控：配置变更后无需重装 Xposed 桩，回调内刷新 features 再判定。
 * 标准 Xposed API（[XposedHelpers] / [XC_MethodHook]）只覆盖 Java 三通道；Native 走 JNI。
 */
object FourChannelGate {

    fun currentFeatures(): HookFeatures = HookFeatureConfig.refreshIfStale()

    fun isActive(packageName: String? = null): Boolean {
        if (!HookConfig.isEnabledForHook()) return false
        val pkg = (packageName ?: TargetContextHolder.packageName)?.takeIf { it.isNotBlank() } ?: return false
        return FourChannelPolicy.isEnabledFor(pkg, currentFeatures())
    }
}
