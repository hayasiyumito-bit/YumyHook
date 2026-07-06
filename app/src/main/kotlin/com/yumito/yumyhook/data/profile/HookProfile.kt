package com.yumito.yumyhook.data.profile

import com.yumito.yumyhook.model.HookFeatures
import com.yumito.yumyhook.xposed.config.HookSpoofValues

/** 当前激活配置快照（值 + 功能开关 + 总开关）。 */
data class HookProfile(
    val values: HookSpoofValues,
    val features: HookFeatures,
    val hookEnabled: Boolean = false,
)
