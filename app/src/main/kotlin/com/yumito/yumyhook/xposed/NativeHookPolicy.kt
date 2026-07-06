package com.yumito.yumyhook.xposed

import com.yumito.yumyhook.model.HookFeatures
import de.robv.android.xposed.XposedBridge

/**
 * 部分 App（QQ Bugly 等）进程内已占用 shadowhook UNIQUE，再装 YumyHook JNI 会崩。
 * 对这些包仅保留 Java 三通道（Build / SystemProperties / getprop）。
 */
object NativeHookPolicy {

    /** 已知自带 shadowhook 1.x 的包，禁止加载 libyumyhook_native。 */
    private val SHADOWHOOK_CONFLICT_PACKAGES = setOf(
        XposedConstants.TARGET_PACKAGE_QQ,
        "com.tencent.tim", // TIM 同 SDK
    )

    fun shouldInstallNative(packageName: String?, features: HookFeatures): Boolean {
        if (!features.shouldInstallNative()) return false
        val pkg = packageName.orEmpty()
        if (pkg in SHADOWHOOK_CONFLICT_PACKAGES) {
            XposedBridge.log(
                "${XposedConstants.TAG}: skip native property hook for $pkg " +
                    "(shadowhook conflict with host app; Java channels still active)",
            )
            return false
        }
        return true
    }
}
