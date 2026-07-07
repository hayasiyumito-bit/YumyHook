package com.yumito.yumyhook.xposed.stealth.hide

import com.yumito.yumyhook.xposed.config.HookFeatureConfig
import com.yumito.yumyhook.xposed.stealth.common.StealthConstants

/** 需从 PM 列表隐藏的包名（模块 + Magisk 管理器等）。 */
object StealthPackageFilter {

    fun hiddenPackages(): Set<String> {
        val out = StealthConstants.HIDDEN_PACKAGES.toMutableSet()
        if (HookFeatureConfig.refreshIfStale().hideRoot) {
            out.addAll(StealthConstants.HIDDEN_MAGISK_PACKAGES)
        }
        return out
    }

    fun isHidden(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return packageName in hiddenPackages()
    }
}
