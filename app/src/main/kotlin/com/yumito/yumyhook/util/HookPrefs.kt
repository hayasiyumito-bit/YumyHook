package com.yumito.yumyhook.util

import android.content.Context
import com.yumito.yumyhook.xposed.HookSpoofValues

/** 主页读配置薄封装，委托 [HookProfilesStore]。 */
object HookPrefs {

    fun ensureDefaults(context: Context) {
        HookProfilesStore.loadDocument(context)
    }

    fun loadSpoofValues(context: Context): HookSpoofValues =
        HookProfilesStore.activeProfile(context).values

    fun loadFeatures(context: Context) =
        HookProfilesStore.activeProfile(context).features

    fun isHookEnabled(context: Context): Boolean =
        HookProfilesStore.isHookEnabled(context)

    fun randomizeSpoof(context: Context): String {
        val values = HookProfilesStore.randomizeActive(context)
        return values.fullParametersSummary(HookProfilesStore.activeProfile(context).features)
    }
}
