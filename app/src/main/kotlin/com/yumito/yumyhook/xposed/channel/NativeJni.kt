package com.yumito.yumyhook.xposed.channel

/**
 * 纯 native 声明持有者：零外部依赖（无 Xposed API 引用），
 * 供宿主 ClassLoader 直接加载并绑定 JNI（[NativeJniHost] 反射调用）。
 * 任何 Xposed / 业务类引用都会导致宿主 CL 校验失败（compileOnly 类不可见）。
 * 改动方法名/签名须同步 native_bridge.cpp 的 JNI 导出符号。
 */
object NativeJni {

    @JvmStatic
    external fun nativeInstallPropertyHook(libcOnly: Boolean, cacheDir: String?): Boolean

    @JvmStatic
    external fun nativeRetryDeferredHooks(): Boolean

    @JvmStatic
    external fun nativeSetSpoofActive(active: Boolean)

    @JvmStatic
    external fun nativeProbeProperty(name: String): String

    @JvmStatic
    external fun nativeProbeLibcutilsProperty(name: String): String

    @JvmStatic
    external fun nativeHookStats(): String

    @JvmStatic
    external fun nativeUpdateProperties(keys: Array<String>, values: Array<String>)

    @JvmStatic
    external fun nativeInstallProcStealth(cacheDir: String): Boolean
}
