package com.yumito.yumyhook.xposed.config

import com.yumito.yumyhook.xposed.channel.NativeBridge
import com.yumito.yumyhook.xposed.runtime.SpoofRuntime
import com.yumito.yumyhook.xposed.runtime.TargetContextHolder

import android.content.Context
import de.robv.android.xposed.XSharedPreferences
import org.json.JSONObject

/**
 * Hook 侧配置：安全点 [refreshHookCache] 刷新内存缓存；
 * SystemProperties / getprop Hook 内只读 [valuesForHook]，禁止 Context / createPackageContext。
 */
object HookConfig {

    private const val PREF_PROFILE = "spoof_profile_label"
    private const val PREF_BUILD_JSON = "spoof_build_json"
    private const val PREF_IDS_JSON = "spoof_ids_json"
    private const val PREF_LOCATION_JSON = "spoof_location_json"

    @Volatile
    private var cachedEnabled: Boolean = false

    @Volatile
    private var cachedValues: HookSpoofValues = HookSpoofValues.DEFAULT

    @Volatile
    private var cachedConfigMtime: Long = 0L

    @Volatile
    private var cachedPrefsUpdatedAt: Long = 0L

    /** 配置变更时轻量刷新（mtime + spoof_updated_at 双信号）。 */
    fun refreshHookCacheIfStale(): HookSpoofValues {
        val prefs = peekFreshPrefs()
        val prefsUpdatedAt = prefs.getLong(XposedConstants.PREF_UPDATED_AT, 0L)
        val file = SpoofConfigFile.hookSideFile()
        val fileMtime = if (file.exists()) file.lastModified() else 0L
        if (prefsUpdatedAt != cachedPrefsUpdatedAt || fileMtime != cachedConfigMtime) {
            return refreshHookCache()
        }
        return cachedValues
    }

    /** 进程启动 / 检测到配置变更时调用。 */
    fun refreshHookCache(): HookSpoofValues {
        val prefs = readFreshPrefs()
        val prefsUpdatedAt = prefs.getLong(XposedConstants.PREF_UPDATED_AT, 0L)
        val fromFile = SpoofConfigFile.readHookSide()
        val fromPrefs = loadFromPrefs(prefs)
        val values = sanitize(
            when {
                fromFile != null && fromFile.updatedAt >= fromPrefs.updatedAt -> fromFile
                fromPrefs.buildFields.isNotEmpty() -> fromPrefs
                fromFile != null -> fromFile
                else -> HookSpoofValues.DEFAULT
            },
        )
        cachedValues = values
        cachedPrefsUpdatedAt = prefsUpdatedAt
        cachedEnabled = SpoofConfigFile.readHookEnabled()
            ?: prefs.getBoolean(XposedConstants.PREF_KEY_ENABLED, false)
        HookFeatureConfig.refresh()
        cachedConfigMtime = SpoofConfigFile.hookSideFile().takeIf { it.exists() }?.lastModified() ?: 0L
        HookFeatureConfig.syncRevision(cachedConfigMtime, cachedPrefsUpdatedAt)
        NativeBridge.syncFromGate(cachedValues, TargetContextHolder.packageName)
        SpoofRuntime.reapplyIfRevisionChanged(values, "config-refresh")
        return cachedValues
    }

    fun isEnabledForHook(): Boolean = cachedEnabled

    fun valuesForHook(): HookSpoofValues = cachedValues

    /** 空/损坏 JSON 不得覆盖 Build 字段，否则目标 App 会看到真机值。 */
    fun sanitize(values: HookSpoofValues): HookSpoofValues {
        return if (values.buildFields.isEmpty()) HookSpoofValues.DEFAULT else values
    }

    private fun loadFromPrefs(prefs: android.content.SharedPreferences): HookSpoofValues {
        val defaults = HookSpoofValues.DEFAULT
        val profile = prefs.getString(PREF_PROFILE, defaults.profileLabel) ?: defaults.profileLabel
        val buildJson = prefs.getString(PREF_BUILD_JSON, null)
        val idsJson = prefs.getString(PREF_IDS_JSON, null)
        val locationJson = prefs.getString(PREF_LOCATION_JSON, "{}")
        val updatedAt = prefs.getLong(XposedConstants.PREF_UPDATED_AT, 0L)
        return if (!buildJson.isNullOrBlank() && !idsJson.isNullOrBlank()) {
            sanitize(
                HookSpoofValues.fromJson(
                    buildJson,
                    idsJson,
                    profile,
                    updatedAt,
                    locationJson.orEmpty(),
                ),
            )
        } else {
            defaults
        }
    }

    private fun peekFreshPrefs(): android.content.SharedPreferences {
        val prefs = XSharedPreferences(XposedConstants.MODULE_PACKAGE, XposedConstants.PREFS_NAME)
        prefs.reload()
        return prefs
    }

    private fun readFreshPrefs(): android.content.SharedPreferences {
        val prefs = XSharedPreferences(XposedConstants.MODULE_PACKAGE, XposedConstants.PREFS_NAME)
        prefs.makeWorldReadable()
        prefs.reload()
        return prefs
    }

    /** 模块 App UI 读取（有自身 Context，安全）。 */
    fun readFromAppContext(context: Context): HookSpoofValues {
        SpoofConfigFile.readModule(context)?.let { return it }
        val prefs = context.getSharedPreferences(XposedConstants.PREFS_NAME, Context.MODE_PRIVATE)
        return loadFromPrefs(prefs)
    }

    fun mapToJson(map: Map<String, String>): String {
        val json = JSONObject()
        map.forEach { (key, value) -> json.put(key, value) }
        return json.toString()
    }
}
