package com.yumito.yumyhook.xposed

import com.yumito.yumyhook.model.HookFeatures
import de.robv.android.xposed.XSharedPreferences
import org.json.JSONObject

/** Hook 进程内功能开关缓存（读 spoof_config.json features 段）。 */
object HookFeatureConfig {

    @Volatile
    private var cached: HookFeatures = HookFeatures.DEFAULT

    @Volatile
    private var cachedMtime: Long = 0L

    @Volatile
    private var cachedPrefsUpdatedAt: Long = 0L

    fun current(): HookFeatures = cached

    fun refreshIfStale(): HookFeatures {
        if (HookReentryGuard.isInside()) return cached
        val file = SpoofConfigFile.hookSideFile()
        val fileMtime = if (file.exists()) file.lastModified() else 0L
        val prefsUpdatedAt = readPrefsUpdatedAt()
        if (fileMtime != cachedMtime || prefsUpdatedAt != cachedPrefsUpdatedAt) {
            return refresh()
        }
        return cached
    }

    fun refresh(): HookFeatures {
        if (HookReentryGuard.isInside()) return cached
        cached = SpoofConfigFile.readHookFeatures()
        cachedMtime = SpoofConfigFile.hookSideFile().takeIf { it.exists() }?.lastModified() ?: 0L
        cachedPrefsUpdatedAt = readPrefsUpdatedAt()
        return cached
    }

    fun applyFromJson(obj: JSONObject?) {
        cached = HookFeatures.fromJson(obj)
    }

    fun syncRevision(fileMtime: Long, prefsUpdatedAt: Long) {
        cachedMtime = fileMtime
        cachedPrefsUpdatedAt = prefsUpdatedAt
    }

    private fun readPrefsUpdatedAt(): Long {
        return try {
            XSharedPreferences(XposedConstants.MODULE_PACKAGE, XposedConstants.PREFS_NAME)
                .apply { reload() }
                .getLong(XposedConstants.PREF_UPDATED_AT, 0L)
        } catch (_: Throwable) {
            0L
        }
    }
}
