package com.yumito.yumyhook.util

import android.content.Context
import com.yumito.yumyhook.xposed.XposedConstants

/** 读取 LSPosed 中为模块勾选的作用域 App 列表。 */
object LsposedScopeReader {

    fun readScopedPackages(context: Context, modulePackage: String = XposedConstants.MODULE_PACKAGE): List<String> {
        LsposedConfigReader.readModuleState(context, modulePackage)
            ?.scopedPackages
            ?.filter { it != modulePackage && it != "lspd" }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return fallbackInstalled(context)
    }

    private fun fallbackInstalled(context: Context): List<String> {
        return XposedConstants.RECOMMENDED_SCOPE_PACKAGES.filter { pkg ->
            PackageVisibility.isPackageInstalled(context, pkg)
        }
    }
}
