package com.yumito.yumyhook.xposed.policy

import com.yumito.yumyhook.xposed.config.XposedConstants

/**
 * Hook 对 LSPosed 作用域内所有 App 生效（除模块自身）。
 * [XposedConstants.RECOMMENDED_SCOPE_PACKAGES] 仅供 Manager 推荐勾选与文档示例，不限制 Hook 范围。
 * 实现禁止针对其中任一包写专用 Hook；com.android.device 仅用于验证采集通道。
 */
object HookScope {

    fun shouldHook(packageName: String): Boolean =
        packageName != XposedConstants.MODULE_PACKAGE
}
