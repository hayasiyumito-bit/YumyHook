package com.yumito.yumyhook.xposed.channel.strategy

/** 四通道 / Stealth 安装生命周期阶段。 */
enum class InstallPhase {
    LOAD_PACKAGE,
    APPLICATION_ATTACH,
    APPLICATION_ON_CREATE,
}

/** Native JNI 安装时机；DISABLED 表示不装。 */
enum class NativeInstallMode {
    /** handleLoadPackage 尽早装 */
    LOAD_PACKAGE,
    /** 等宿主 shadowhook / crash 库加载后再装（仅 libyumyhook_native） */
    HOST_SHADOWHOOK_DEFERRED,
    DISABLED,
    APPLICATION_ATTACH,
    APPLICATION_ON_CREATE,
}
