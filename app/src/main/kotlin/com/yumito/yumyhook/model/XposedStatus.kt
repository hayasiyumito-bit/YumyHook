package com.yumito.yumyhook.model

/** 主页展示的 Xposed / LSPosed 运行状态快照。 */
data class XposedStatus(
    val frameworkActive: Boolean,
    val frameworkVersion: Int,
    val lsposedActive: Boolean,
    val lsposedVersionLabel: String,
    val moduleEnabled: Boolean,
    val scopedApps: List<ScopedAppEntry>,
    val hookEnabled: Boolean,
    val frameworkScopeEnabled: Boolean,
    val systemScopeEnabled: Boolean,
    val riskySystemScope: Boolean,
    val stealthNeedsFrameworkScope: Boolean,
)
