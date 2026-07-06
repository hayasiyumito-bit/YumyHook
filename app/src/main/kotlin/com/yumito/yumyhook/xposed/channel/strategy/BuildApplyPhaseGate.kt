package com.yumito.yumyhook.xposed.channel.strategy

import com.yumito.yumyhook.xposed.channel.strategy.profiles.BuiltinAppProfiles

/** Build 字段写入最早允许的生命周期阶段（按内置档案）。 */
object BuildApplyPhaseGate {

    fun allows(packageName: String?, phase: InstallPhase): Boolean {
        val min = BuiltinAppProfiles.forPackage(packageName.orEmpty()).applyBuildAtPhase
        return phase.ordinal >= min.ordinal
    }
}
