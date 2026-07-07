package com.yumito.yumyhook.data.lsposed

import android.content.Context
import android.content.pm.PackageManager
import com.yumito.yumyhook.xposed.config.XposedConstants

/** 作用域包名 → 显示名；无 label 时退回包名。 */
object ScopeLabelResolver {

    fun label(context: Context, packageName: String): String {
        if (packageName.isBlank()) return packageName
        if (packageName in XposedConstants.FRAMEWORK_SCOPE_PACKAGES) {
            return XposedConstants.FRAMEWORK_SCOPE_LABEL
        }
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            val resolved = pm.getApplicationLabel(info).toString().trim()
            resolved.ifBlank { packageName }
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        } catch (_: Exception) {
            packageName
        }
    }

    fun labels(context: Context, packages: List<String>): List<String> {
        return packages.map { label(context, it) }
    }
}
