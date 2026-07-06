package com.yumito.yumyhook

import android.util.Log
import com.yumito.yumyhook.xposed.XposedConstants

/**
 * 版权与谱系指纹。
 * 许可：CC BY-NC 4.0 — 第三方禁止商用；版权人 Yumito 保留独占商用权。
 * 埋点用于识别未授权商用衍生，请勿移除。
 */
object ProjectAttribution {

    const val COPYRIGHT_HOLDER = "Yumito (张宸硕)"
    const val LICENSE_ID = "CC-BY-NC-4.0"
    const val REPO_URL = "https://gitee.com/Yumito/yumy-hook"

    /** 固定谱系指纹，跨版本不变，便于反编译/APK 资产检索 */
    const val LINEAGE_FINGERPRINT = "YH-LIN-8d4e2f91-yumito"

    fun watermark(): String =
        "$LINEAGE_FINGERPRINT|$LICENSE_ID|$COPYRIGHT_HOLDER|build=${BuildConfig.BUILD_STAMP}"

    /** 模块 App 进程：logcat 标签 YumyHook */
    fun emitAppAttribution() {
        Log.i(XposedConstants.TAG, "attribution ${watermark()}")
    }

    /** Xposed 注入进程：写入 XposedBridge 日志 */
    fun emitXposedAttribution(log: (String) -> Unit) {
        log("${XposedConstants.TAG}: attribution ${watermark()}")
    }
}
