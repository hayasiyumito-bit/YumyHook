package com.yumito.yumyhook.xposed.stealth.common
import com.yumito.yumyhook.xposed.config.XposedConstants

/** 系统层反检测常量（不绑定任何目标 App 类名）。 */
object StealthConstants {

    val HIDDEN_PACKAGES: Set<String> = setOf(
        XposedConstants.MODULE_PACKAGE,
    )

    val HIDDEN_MAGISK_PACKAGES: Set<String> = setOf(
        "com.topjohnwu.magisk",
        "com.topjohnwu.magisk.debug",
        "io.github.huskydg.magisk",
        "io.github.vvb2060.magisk",
        "me.weishu.kernelsu",
        "com.omarea.vtools",
    )

    val ROOT_SPOOF_PROPERTIES: Map<String, String> = mapOf(
        "ro.build.tags" to "release-keys",
        "ro.build.type" to "user",
        "ro.debuggable" to "0",
        "ro.secure" to "1",
        "ro.adb.secure" to "1",
        "ro.boot.verifiedbootstate" to "green",
        "ro.boot.vbmeta.device_state" to "locked",
        "ro.boot.flash.locked" to "1",
        "ro.boot.veritymode" to "enforcing",
        "init.svc.adbd" to "stopped",
        "service.adb.root" to "0",
        "persist.sys.root_access" to "0",
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
        "magisk",
        "kernelsu",
        "supersu",
        "busybox",
    )

    const val PROC_SELF_MAPS = "/proc/self/maps"
    const val PROC_SELF_STATUS = "/proc/self/status"
    const val PROC_SELF_MOUNTINFO = "/proc/self/mountinfo"
    const val PROC_SELF_MOUNTS = "/proc/self/mounts"

    val HIDDEN_PROBE_PREFIXES: List<String> = listOf(
        "/data/adb/modules/",
        "/data/adb/magisk/",
        "/data/misc/lsposed",
        "/data/misc/lspd",
        "/data/user/0/org.lsposed.manager",
        "/data/user_de/0/org.lsposed.manager",
        "/data/user/0/com.topjohnwu.magisk",
        "/data/user_de/0/com.topjohnwu.magisk",
    )

    val HIDDEN_ROOT_PREFIXES: List<String> = listOf(
        "/sbin/.magisk",
        "/dev/.magisk",
        "/cache/magisk",
        "/data/adb/",
    )

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
        "/vendor/bin/su",
        "/product/bin/su",
        "/data/local/su",
        "/data/local/bin/su",
        "/data/local/xbin/su",
        "/data/local/tmp/su",
        "/sbin/magisk",
        "/sbin/magisk64",
        "/sbin/magiskhide",
        "/sbin/magiskpolicy",
        "/data/adb/magisk",
        "/data/adb/magisk.db",
        "/data/adb/magisk.img",
        "/data/adb/magisk_simple",
        "/sbin/.magisk",
        "/dev/.magisk",
        "/cache/magisk",
        "/data/adb/lspd",
        "/data/adb/modules/zygisk_lsposed",
        "/data/adb/modules/riru_lsposed",
        "/data/misc/riru",
        "/system/framework/XposedBridge.jar",
        "/system/bin/app_process32_xposed",
        "/system/bin/app_process64_xposed",
        "/system/bin/busybox",
        "/system/xbin/busybox",
        "/system/bin/k-su",
        "/system/xbin/daemonsu",
        "/system/etc/init.d/99SuperSUDaemon",
        "/system/etc/.installed_su_daemon",
        "/system/etc/.has_su_daemon",
        "/system/usr/we-need-root/su-backup",
    )

    const val SETTINGS_ADB_ENABLED = "adb_enabled"
}
