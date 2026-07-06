package com.yumito.yumyhook.xposed.policy

import com.yumito.yumyhook.model.HookFeatures
import com.yumito.yumyhook.xposed.channel.strategy.profiles.BuiltinAppProfiles
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XposedBridge

/** Native 属性通道是否安装（四通道门控 + 用户按包开关）。 */
object NativeHookPolicy {

    fun shouldInstallNative(packageName: String?, features: HookFeatures): Boolean {
        if (!FourChannelPolicy.isEnabledFor(packageName, features)) return false
        if (!features.shouldInstallNative()) return false
        val pkg = packageName.orEmpty()
        if (pkg.isBlank()) return true
        if (!features.isNativeEnabledFor(pkg)) return false
        val strategy = BuiltinAppProfiles.forPackage(pkg)
        if (!strategy.nativeChannel) {
            XposedBridge.log(
                "${XposedConstants.TAG}: skip native JNI pkg=$pkg profile=${strategy.profileId} " +
                    "(profile disables native; Java 3-channel active)",
            )
            return false
        }
        return true
    }
}
