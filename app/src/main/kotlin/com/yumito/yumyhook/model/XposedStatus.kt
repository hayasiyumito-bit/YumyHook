package com.yumito.yumyhook.model

/** 主页展示的 Xposed / LSPosed 运行状态快照。 */
data class XposedStatus(
    val frameworkActive: Boolean,
    val frameworkVersion: Int,
    val lsposedActive: Boolean,
    val lsposedVersionLabel: String,
    val moduleEnabled: Boolean,
    val targetPackage: String,
    val hookEnabled: Boolean,
)
