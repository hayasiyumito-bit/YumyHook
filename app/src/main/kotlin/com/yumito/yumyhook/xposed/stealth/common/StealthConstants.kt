package com.yumito.yumyhook.xposed.stealth.common
import com.yumito.yumyhook.xposed.config.XposedConstants

/** 系统层反检测常量（不绑定任何目标 App 类名）。 */
object StealthConstants {

    val HIDDEN_PACKAGES: Set<String> = setOf(
        XposedConstants.MODULE_PACKAGE,
    )

    val PROC_MAPS_FILTER_KEYWORDS: List<String> = listOf(
        "frida",
        "xposed",
        "lsposed",
        "lspatch",
        "substrate",
        "yumyhook",
        "yumyhook_native",
        "libyumyhook",
        "yumito",
        "lspd",
        "liblspd",
        "edxposed",
        "riru",
        "libriru",
        "zygisk",
        "shadowhook",
        "bytehook",
        "whale",
        "sandhook",
        "epic",
        "pine",
        "dobby",
    )

    const val PROC_SELF_MAPS = "/proc/self/maps"
    const val PROC_SELF_STATUS = "/proc/self/status"

    /** CheckEmu HOOK_FRAMEWORK_FILES + SU_PATHS + Magisk — File.exists() 探测。 */
    val HIDDEN_PROBE_PATHS: Set<String> = setOf(
        "/data/local/tmp/frida-server",
        "/data/local/tmp/re.frida.server",
        "/data/local/tmp/frida-gadget.so",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sbin/su",
        "/system_ext/bin/su",
        "/data/local/su",
        "/data/local/bin/su",
        "/data/local/xbin/su",
        "/data/adb/magisk",
        "/sbin/.magisk",
        "/data/adb/lspd",
        "/data/adb/modules/zygisk_lsposed",
        "/data/adb/modules/riru_lsposed",
        "/data/misc/riru",
    )

    const val SETTINGS_ADB_ENABLED = "adb_enabled"
}
