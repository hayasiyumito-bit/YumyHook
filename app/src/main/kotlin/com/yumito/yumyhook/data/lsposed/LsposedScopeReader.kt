package com.yumito.yumyhook.data.lsposed

import android.content.Context
import com.yumito.yumyhook.xposed.config.XposedConstants

/** 读取 LSPosed 中为模块勾选的作用域 App 列表。 */
object LsposedScopeReader {

    fun readScopedPackages(context: Context, modulePackage: String = XposedConstants.MODULE_PACKAGE): List<String> {
        if (RootShell.isAvailable()) {
            val fromLspd = LsposedConfigReader.readScopedPackages(context, modulePackage, useRoot = true)
            if (fromLspd.isNotEmpty()) return fromLspd
        }
        return fallbackInstalled(context)
    }

    private fun fallbackInstalled(context: Context): List<String> {
        return XposedConstants.RECOMMENDED_SCOPE_PACKAGES.filter { pkg ->
            PackageVisibility.isPackageInstalled(context, pkg)
        }
    }
}
