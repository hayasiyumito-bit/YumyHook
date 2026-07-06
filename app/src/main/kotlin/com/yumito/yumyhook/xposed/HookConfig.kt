package com.yumito.yumyhook.xposed

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

    @Volatile
    private var cachedEnabled: Boolean = false

    @Volatile
    private var cachedValues: HookSpoofValues = HookSpoofValues.DEFAULT

    @Volatile
    private var cachedConfigMtime: Long = 0L

    /** 配置变更时轻量刷新，避免 Hook 目标 App 业务类。 */
    fun refreshHookCacheIfStale(): HookSpoofValues {
        if (HookReentryGuard.isInside()) {
            return cachedValues
        }
        val file = SpoofConfigFile.hookSideFile()
        val mtime = if (file.exists()) file.lastModified() else 0L
        if (mtime != cachedConfigMtime) {
            return refreshHookCache()
        }
        return cachedValues
    }

    /** 仅在进程启动 / 配置变更时调用。 */
    fun refreshHookCache(): HookSpoofValues {
        if (HookReentryGuard.isInside()) {
            return cachedValues
        }
        val fromFile = SpoofConfigFile.readHookSide()
        val values = sanitize(fromFile ?: loadFromXSharedPrefs())
        cachedValues = values
        cachedEnabled = SpoofConfigFile.readHookEnabled()
            ?: readFreshPrefs().getBoolean(XposedConstants.PREF_KEY_ENABLED, false)
        HookFeatureConfig.applyFromJson(
            try {
                org.json.JSONObject(SpoofConfigFile.hookSideFile().takeIf { it.exists() }?.readText() ?: "{}")
                    .optJSONObject(SpoofConfigFile.KEY_FEATURES)
            } catch (_: Exception) {
                null
            },
        )
        cachedConfigMtime = SpoofConfigFile.hookSideFile().takeIf { it.exists() }?.lastModified() ?: 0L
        return cachedValues
    }

    fun isEnabledForHook(): Boolean = cachedEnabled

    fun valuesForHook(): HookSpoofValues = cachedValues

    /** 空/损坏 JSON 不得覆盖 Build 字段，否则目标 App 会看到真机值。 */
    fun sanitize(values: HookSpoofValues): HookSpoofValues {
        return if (values.buildFields.isEmpty()) HookSpoofValues.DEFAULT else values
    }

    private fun loadFromXSharedPrefs(): HookSpoofValues {
        val prefs = readFreshPrefs()
        val defaults = HookSpoofValues.DEFAULT
        val profile = prefs.getString(PREF_PROFILE, defaults.profileLabel) ?: defaults.profileLabel
        val buildJson = prefs.getString(PREF_BUILD_JSON, null)
        val idsJson = prefs.getString(PREF_IDS_JSON, null)
        val updatedAt = prefs.getLong(XposedConstants.PREF_UPDATED_AT, 0L)
        return if (!buildJson.isNullOrBlank() && !idsJson.isNullOrBlank()) {
            sanitize(HookSpoofValues.fromJson(buildJson, idsJson, profile, updatedAt))
        } else {
            defaults
        }
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
        val defaults = HookSpoofValues.DEFAULT
        val profile = prefs.getString(PREF_PROFILE, defaults.profileLabel) ?: defaults.profileLabel
        val buildJson = prefs.getString(PREF_BUILD_JSON, null)
        val idsJson = prefs.getString(PREF_IDS_JSON, null)
        val updatedAt = prefs.getLong(XposedConstants.PREF_UPDATED_AT, 0L)
        return if (!buildJson.isNullOrBlank() && !idsJson.isNullOrBlank()) {
            sanitize(HookSpoofValues.fromJson(buildJson, idsJson, profile, updatedAt))
        } else {
            defaults
        }
    }

    fun mapToJson(map: Map<String, String>): String {
        val json = JSONObject()
        map.forEach { (key, value) -> json.put(key, value) }
        return json.toString()
    }
}
