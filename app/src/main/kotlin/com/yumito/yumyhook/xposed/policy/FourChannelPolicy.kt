package com.yumito.yumyhook.xposed.policy

import com.yumito.yumyhook.xposed.config.XposedConstants

import com.yumito.yumyhook.model.HookFeatures

/** 四通道（Build / SystemProperties / getprop / Native）按包名门控。 */
object FourChannelPolicy {

    fun isEnabledFor(packageName: String?, features: HookFeatures): Boolean {
        if (!features.spoofBuildProperties) return false
        val pkg = packageName.orEmpty()
        if (pkg.isBlank() || pkg == XposedConstants.MODULE_PACKAGE) return false
        return !features.disabledScopedFourChannel.contains(pkg)
    }
}
