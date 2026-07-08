package com.yumito.yumyhook.xposed.channel

import java.io.File

/**
 * 安装 YumyHook native 前读 `/proc/self/maps`：宿主已映射 `libshadowhook.so` 则跳过 JNI，
 * 避免与微信 / QQ Bugly / 钉钉等自带 shadowhook 双 init 崩溃。
 *
 * maps 仅 [ensureProbed] 快照一次；proc stealth hook 装后禁止再读（会重入 native open/fgets）。
 */
object HostShadowhookDetector {

    private const val SHADOWHOOK_LIB = "libshadowhook.so"
    private const val OUR_EXTRACT_DIR = "yumyhook_native"

    @Volatile
    private var probed = false

    private var mapsLines: List<String> = emptyList()

    @Volatile
    private var procStealthActive = false

    /** proc open/fgets hook 装后禁止 live 读 maps（会 SIGSEGV）。 */
    fun markProcStealthActive() {
        procStealthActive = true
    }

    /** 须在 native proc stealth 安装前调用（[NativeLibLoader.ensureLoaded] 入口）。 */
    fun ensureProbed() {
        if (probed) return
        synchronized(this) {
            if (probed) return
            mapsLines = if (procStealthActive) {
                emptyList()
            } else {
                runCatching { File("/proc/self/maps").readLines() }.getOrElse { emptyList() }
            }
            probed = true
        }
    }

    fun isHostPresent(): Boolean {
        ensureProbed()
        return mapsLines.any(::isHostShadowhookLine)
    }

    /** 宿主 crash / bugly 已映射（shadowhook 可能静态链进 crash 库，maps 无独立 libshadowhook.so）。 */
    fun isHostNativeReady(): Boolean {
        ensureProbed()
        return isHostPresent() || mapsLines.any(::isHostCrashLibLine)
    }

    fun isIntegrityLibMapped(): Boolean {
        ensureProbed()
        return mapsLines.any { it.lowercase().contains("pairipcore") }
    }

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
            l.contains("crashsdk") ||
            l.contains("pairipcore") ||
            l.contains("rmonitor") ||
            l.contains("qimei") ||
            l.contains("marsxlog") ||
            l.contains("libqsec") ||
            l.contains("libmsfboot") ||
            l.contains("libtencentloc")
    }
}
