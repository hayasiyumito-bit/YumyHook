package com.yumito.yumyhook.xposed

/**
 * Hook 对 LSPosed 作用域内所有 App 生效（除模块自身）。
 * [XposedConstants.RECOMMENDED_SCOPE_PACKAGES] 仅供 Manager 推荐勾选，不限制 Hook 范围。
 */
object HookScope {

    fun shouldHook(packageName: String): Boolean =
        packageName != XposedConstants.MODULE_PACKAGE
}
