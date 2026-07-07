package com.yumito.yumyhook.xposed.config

object XposedConstants {
    const val MODULE_PACKAGE = "com.yumito.yumyhook"
    const val TARGET_PACKAGE_DEVICE = "com.android.device"
    const val TARGET_PACKAGE_QQ = "com.tencent.mobileqq"
    const val TARGET_PACKAGE_WECHAT = "com.tencent.mm"
    const val TARGET_PACKAGE_DINGTALK = "com.alibaba.android.rimet"
    const val TARGET_PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"
    const val TARGET_PACKAGE_TIM = "com.tencent.tim"
    const val TARGET_PACKAGE_BILIBILI = "tv.danmaku.bili"
    const val TARGET_PACKAGE_TWITTER = "com.twitter.android"
    const val TARGET_PACKAGE_XYNER_TOOLS = "cn.xyner.tools"

    /** LSPosed 推荐作用域（与 res/values/arrays.xml、META-INF/xposed/scope.list 保持一致） */
    val RECOMMENDED_SCOPE_PACKAGES: List<String> = listOf(
        TARGET_PACKAGE_DEVICE,
        TARGET_PACKAGE_QQ,
        TARGET_PACKAGE_WECHAT,
        TARGET_PACKAGE_DINGTALK,
        TARGET_PACKAGE_BILIBILI,
        TARGET_PACKAGE_XYNER_TOOLS,
    )

    val HOOK_SCOPE_HINT: String = "LSPosed 作用域内所有 App"
    const val TAG = "YumyHook"
    const val XPOSED_API_VERSION = 82

    const val PREFS_NAME = "hook_config"
    const val PREF_KEY_ENABLED = "hook_enabled"
    const val PREF_RUNTIME_ACTIVE = "xposed_runtime_active"
    const val PREF_LSPOSED_INJECTED = "lsposed_injected"
    const val PREF_RUNTIME_VERSION = "xposed_runtime_version"
    const val PREF_UPDATED_AT = "spoof_updated_at"
    const val PREF_DEBUG_UI_LOG = "debug_ui_log"
    const val PREF_MODULE_ENABLED_CACHE = "lsposed_module_enabled_cache"
    const val PREF_FRAMEWORK_ACTIVE_CACHE = "lsposed_framework_active_cache"

    const val PREF_FEATURES_JSON = "spoof_features_json"

    /** 递增；logcat 搜此值确认 LSPosed 已加载新 dex */
    const val HOOK_REV = 55
}
