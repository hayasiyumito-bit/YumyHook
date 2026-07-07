package com.yumito.yumyhook.xposed.channel.build

import com.yumito.yumyhook.xposed.channel.systemproperty.SystemPropertyMapper
import com.yumito.yumyhook.xposed.config.HookSpoofValues
import com.yumito.yumyhook.xposed.config.XposedConstants

import android.content.Context
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** 将伪装值写入 android.os.Build / Build.VERSION 静态字段。 */
object OsBuildPatcher {

    private val STRING_FIELDS = listOf(
        "MODEL", "BRAND", "MANUFACTURER", "DEVICE", "PRODUCT", "FINGERPRINT",
        "HARDWARE", "BOARD", "BOOTLOADER", "DISPLAY", "HOST", "ID", "TAGS",
        "TYPE", "USER", "RADIO", "CPU_ABI", "CPU_ABI2", "SERIAL",
    )

    private val ARRAY_FIELDS = listOf(
        "SUPPORTED_ABIS", "SUPPORTED_32_BIT_ABIS", "SUPPORTED_64_BIT_ABIS",
    )

    fun apply(values: HookSpoofValues) {
        try {
            val buildClass = XposedHelpers.findClass("android.os.Build", null)
            for (field in STRING_FIELDS) {
                val value = values.getBuildField(field) ?: continue
                try {
                    XposedHelpers.setStaticObjectField(buildClass, field, value)
                } catch (e: Throwable) {
                    XposedBridge.log("${XposedConstants.TAG}: Build.$field skip: ${e.message}")
                }
            }
            for (field in ARRAY_FIELDS) {
                val raw = values.getBuildField(field) ?: continue
                val array = SystemPropertyMapper.parseStringArray(raw)
                if (array.isEmpty()) continue
                try {
                    XposedHelpers.setStaticObjectField(buildClass, field, array)
                } catch (e: Throwable) {
                    XposedBridge.log("${XposedConstants.TAG}: Build.$field array skip: ${e.message}")
                }
            }
            values.getBuildField("TIME")?.toLongOrNull()?.let { time ->
                try {
                    XposedHelpers.setStaticLongField(buildClass, "TIME", time)
                } catch (_: Throwable) {
                }
            }

            val versionClass = XposedHelpers.findClass("android.os.Build\$VERSION", null)
            values.getBuildField("SDK_INT")?.toIntOrNull()?.let {
                try {
                    XposedHelpers.setStaticIntField(versionClass, "SDK_INT", it)
                } catch (_: Throwable) {
                }
            }
            for (field in listOf("RELEASE", "INCREMENTAL", "SECURITY_PATCH", "CODENAME", "BASE_OS")) {
                val value = values.getBuildField(field) ?: continue
                try {
                    XposedHelpers.setStaticObjectField(versionClass, field, value)
                } catch (_: Throwable) {
                }
            }
            values.getBuildField("PREVIEW_SDK_INT")?.toIntOrNull()?.let {
                try {
                    XposedHelpers.setStaticIntField(versionClass, "PREVIEW_SDK_INT", it)
                } catch (_: Throwable) {
                }
            }
            values.getBuildField("RESOURCES_SDK_INT")?.toIntOrNull()?.let {
                try {
                    XposedHelpers.setStaticIntField(versionClass, "RESOURCES_SDK_INT", it)
                } catch (_: Throwable) {
                }
            }
            XposedBridge.log(
                "${XposedConstants.TAG}: android.os.Build patched MODEL=${values.getBuildField("MODEL")}"
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: OsBuildPatcher failed: ${e.message}")
        }
    }
}
