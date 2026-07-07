package com.yumito.yumyhook.xposed.config

import com.yumito.yumyhook.model.HookFeatures
import com.yumito.yumyhook.xposed.runtime.HookReentryGuard
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
        val fileMtime = HookReentryGuard.runFileBypass {
            val file = SpoofConfigFile.hookSideFile()
            if (file.exists()) file.lastModified() else 0L
        }
        val prefsUpdatedAt = readPrefsUpdatedAt()
        if (fileMtime != cachedMtime || prefsUpdatedAt != cachedPrefsUpdatedAt) {
            return refresh()
        }
        return cached
    }

    fun refresh(): HookFeatures {
        cached = readFeaturesFromHookSide()
        cachedMtime = HookReentryGuard.runFileBypass {
            SpoofConfigFile.hookSideFile().takeIf { it.exists() }?.lastModified() ?: 0L
        }
        cachedPrefsUpdatedAt = readPrefsUpdatedAt()
        return cached
    }

    /**
     * Hook 进程读 features：优先 spoof_config.json 镜像（与 values 同写、world-readable），
     * 避免 XSP 读不到时回退 DEFAULT 导致按 App 关四通道失效。
     */
    private fun readFeaturesFromHookSide(): HookFeatures {
        val fileFeatures = readFeaturesFromConfigFile()
        val prefsFeatures = readFeaturesFromPrefs()
        return when {
            fileFeatures != null && prefsFeatures != null ->
                if (readConfigFileUpdatedAt() >= readPrefsUpdatedAt()) fileFeatures else prefsFeatures
            fileFeatures != null -> fileFeatures
            prefsFeatures != null -> prefsFeatures
            else -> HookFeatures.DEFAULT
        }
    }

    private fun readFeaturesFromConfigFile(): HookFeatures? {
        return HookReentryGuard.runFileBypass {
            val file = SpoofConfigFile.hookSideFile()
            if (!file.exists() || file.length() == 0L) return@runFileBypass null
            try {
                SpoofConfigFile.readHookFeatures()
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun readConfigFileUpdatedAt(): Long {
        return HookReentryGuard.runFileBypass {
            val file = SpoofConfigFile.hookSideFile()
            if (!file.exists()) return@runFileBypass 0L
            try {
                org.json.JSONObject(file.readText()).optLong("updatedAt", file.lastModified())
            } catch (_: Exception) {
                file.lastModified()
            }
        }
    }

    private fun readFeaturesFromPrefs(): HookFeatures? {
        return try {
            val prefs = XSharedPreferences(XposedConstants.MODULE_PACKAGE, XposedConstants.PREFS_NAME)
            prefs.makeWorldReadable()
            prefs.reload()
            val raw = prefs.getString(XposedConstants.PREF_FEATURES_JSON, null)
            if (!raw.isNullOrBlank()) {
                HookFeatures.fromJson(JSONObject(raw))
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
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
