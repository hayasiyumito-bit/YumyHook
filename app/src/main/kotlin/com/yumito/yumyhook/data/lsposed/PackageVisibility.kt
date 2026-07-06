package com.yumito.yumyhook.data.lsposed

import android.content.Context
import android.content.pm.PackageManager

/** Android 11+ 包可见性 + Root 安装检测。 */
object PackageVisibility {

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        if (isInstalledViaPackageManager(context, packageName)) return true
        if (!RootShell.isAvailable()) return false
        return RootShell.exec("pm path $packageName").exitCode == 0
    }

    private fun isInstalledViaPackageManager(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
