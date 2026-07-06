package com.yumito.yumyhook.xposed

import com.yumito.yumyhook.model.HookFeatures
import org.json.JSONObject

/** Hook 进程内功能开关缓存（读 spoof_config.json features 段）。 */
object HookFeatureConfig {

    @Volatile
    private var cached: HookFeatures = HookFeatures.DEFAULT

    @Volatile
    private var cachedMtime: Long = 0L

    fun current(): HookFeatures = cached

    fun refreshIfStale(): HookFeatures {
        if (HookReentryGuard.isInside()) return cached
        val file = SpoofConfigFile.hookSideFile()
        val mtime = if (file.exists()) file.lastModified() else 0L
        if (mtime != cachedMtime) {
            return refresh()
        }
        return cached
    }

    fun refresh(): HookFeatures {
        if (HookReentryGuard.isInside()) return cached
        cached = SpoofConfigFile.readHookFeatures()
        cachedMtime = SpoofConfigFile.hookSideFile().takeIf { it.exists() }?.lastModified() ?: 0L
        return cached
    }

    fun applyFromJson(obj: JSONObject?) {
        cached = HookFeatures.fromJson(obj)
    }
}
