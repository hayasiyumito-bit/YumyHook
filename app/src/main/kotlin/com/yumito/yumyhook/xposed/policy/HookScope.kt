package com.yumito.yumyhook.xposed.policy

import com.yumito.yumyhook.xposed.config.XposedConstants

/**
 * Hook 对 LSPosed 作用域内所有 App 生效（除模块自身）。
 * [XposedConstants.RECOMMENDED_SCOPE_PACKAGES] 仅供 Manager 推荐勾选与文档示例，不限制 Hook 范围。
 * 实现禁止针对其中任一包写专用 Hook；com.android.device 仅用于验证采集通道。
 */
object HookScope {

    /** LSPosed「系统」作用域条目；注入非 system_server，Hook 会致系统崩溃，必须跳过。 */
    const val LSPOSED_SYSTEM_SCOPE_PACKAGE = "system"

    fun shouldHook(packageName: String): Boolean =
        packageName != XposedConstants.MODULE_PACKAGE &&
            packageName != LSPOSED_SYSTEM_SCOPE_PACKAGE

    /** 仅 android 包（system_server）允许装框架 stealth。 */
    fun isFrameworkHookTarget(packageName: String): Boolean =
        packageName == XposedConstants.FRAMEWORK_HOOK_PACKAGE

    /** UI / 作用域列表展示用（含 system 标签）。 */
    fun isFrameworkScopeLabel(packageName: String): Boolean =
        packageName in XposedConstants.FRAMEWORK_SCOPE_PACKAGES

    @Deprecated("Use isFrameworkHookTarget", ReplaceWith("isFrameworkHookTarget(packageName)"))
    fun isFrameworkProcess(packageName: String): Boolean = isFrameworkHookTarget(packageName)
}
