package com.yumito.yumyhook.xposed.channel.strategy.profiles

import com.yumito.yumyhook.xposed.config.XposedConstants

/**
 * 已知自带 shadowhook 的 App（HOST_SHADOWHOOK_DEFERRED 策略）。
 * 其它 App 仍靠 [com.yumito.yumyhook.xposed.channel.HostShadowhookDetector] maps 检测。
 */
object ShadowhookKnownApps {

    val PACKAGES: Set<String> = setOf(
        XposedConstants.TARGET_PACKAGE_WECHAT,
        XposedConstants.TARGET_PACKAGE_QQ,
        XposedConstants.TARGET_PACKAGE_TIM,
        XposedConstants.TARGET_PACKAGE_DINGTALK,
        XposedConstants.TARGET_PACKAGE_ALIPAY,
    )

    fun isKnown(packageName: String): Boolean = packageName in PACKAGES
}
