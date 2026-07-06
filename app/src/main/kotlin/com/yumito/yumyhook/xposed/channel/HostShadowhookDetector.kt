package com.yumito.yumyhook.xposed.channel

import java.io.File

/**
 * 安装 YumyHook native 前读 `/proc/self/maps`：宿主已映射 `libshadowhook.so` 则跳过 JNI，
 * 避免与微信 / QQ Bugly / 钉钉等自带 shadowhook 双 init 崩溃。
 */
object HostShadowhookDetector {

    private const val SHADOWHOOK_LIB = "libshadowhook.so"
    private const val OUR_EXTRACT_DIR = "yumyhook_native"

    fun isHostPresent(): Boolean =
        readMapsLines().any(::isHostShadowhookLine)

    /** 宿主 crash / bugly 已映射（shadowhook 可能静态链进 crash 库，maps 无独立 libshadowhook.so）。 */
    fun isHostNativeReady(): Boolean =
        isHostPresent() || readMapsLines().any(::isHostCrashLibLine)

    private fun readMapsLines(): List<String> =
        runCatching { File("/proc/self/maps").readLines() }.getOrElse { emptyList() }

    /** 任意非 YumyHook 解压目录的 shadowhook 映射视为宿主占用。 */
    private fun isHostShadowhookLine(line: String): Boolean {
        if (!line.contains(SHADOWHOOK_LIB)) return false
        if (line.contains(OUR_EXTRACT_DIR)) return false
        return true
    }

    private fun isHostCrashLibLine(line: String): Boolean {
        val l = line.lowercase()
        if (l.contains(OUR_EXTRACT_DIR)) return false
        return l.contains("wechatcrash") ||
            l.contains("libbugly") ||
            l.contains("libgaea") ||
            l.contains("crashsdk")
    }
}
