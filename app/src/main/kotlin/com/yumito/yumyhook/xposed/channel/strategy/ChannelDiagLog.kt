package com.yumito.yumyhook.xposed.channel.strategy

import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XposedBridge

/** 四通道诊断日志（用户 logcat 过滤 YumyHook + strategy）。 */
object ChannelDiagLog {

    fun strategy(pkg: String, resolved: ResolvedChannelStrategy) {
        XposedBridge.log(
            "${XposedConstants.TAG}: strategy rev=${XposedConstants.HOOK_REV} pkg=$pkg ${resolved.summary()}",
        )
    }

    fun phase(pkg: String, phase: InstallPhase, detail: String) {
        XposedBridge.log("${XposedConstants.TAG}: phase pkg=$pkg @$phase $detail")
    }

    fun skip(pkg: String, reason: String) {
        XposedBridge.log("${XposedConstants.TAG}: skip pkg=$pkg reason=$reason")
    }

    fun native(pkg: String, detail: String) {
        XposedBridge.log("${XposedConstants.TAG}: native pkg=$pkg $detail")
    }
}
