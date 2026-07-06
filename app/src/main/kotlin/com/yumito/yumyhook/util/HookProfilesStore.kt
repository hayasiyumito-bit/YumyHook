package com.yumito.yumyhook.util

import android.content.Context
import com.yumito.yumyhook.model.HookFeatures
import com.yumito.yumyhook.model.ProfilesDocument
import com.yumito.yumyhook.model.StoredProfile
import com.yumito.yumyhook.xposed.BuildSpoofGenerator
import com.yumito.yumyhook.xposed.HookConfig
import com.yumito.yumyhook.xposed.HookSpoofValues
import com.yumito.yumyhook.xposed.SpoofConfigFile
import com.yumito.yumyhook.xposed.XposedConstants
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 多配置 Tab 持久化 + 同步当前配置到 Hook 侧 JSON。 */
object HookProfilesStore {

    private const val FILE_NAME = "hook_profiles.json"

    fun loadDocument(context: Context): ProfilesDocument {
        migrateLegacyIfNeeded(context)
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            val doc = ProfilesDocument.default()
            saveDocument(context, doc)
            return doc
        }
        return ProfilesDocument.fromJson(file.readText()) ?: ProfilesDocument.default().also {
            saveDocument(context, it)
        }
    }

    fun saveDocument(context: Context, document: ProfilesDocument) {
        val file = File(context.filesDir, FILE_NAME)
        file.writeText(document.toJson().toString())
        syncActiveToHookSide(context, document)
        context.getSharedPreferences(XposedConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(XposedConstants.PREF_KEY_ENABLED, document.hookEnabled)
            .commit()
    }

    fun activeProfile(context: Context): StoredProfile {
        return loadDocument(context).activeProfile() ?: ProfilesDocument.default().profiles.first()
    }

    fun loadHookProfile(context: Context): HookProfile {
        val doc = loadDocument(context)
        val active = doc.activeProfile() ?: return HookProfile(HookSpoofValues.DEFAULT, HookFeatures.DEFAULT)
        return HookProfile(active.values, active.features, doc.hookEnabled)
    }

    fun updateActiveProfile(context: Context, profile: StoredProfile) {
        val doc = loadDocument(context)
        val updated = doc.profiles.map { if (it.id == profile.id) profile else it }
        saveDocument(context, doc.copy(profiles = updated))
    }

    fun setActiveProfileId(context: Context, profileId: String) {
        val doc = loadDocument(context)
        if (doc.profiles.none { it.id == profileId }) return
        saveDocument(context, doc.copy(activeProfileId = profileId))
    }

    fun setHookEnabled(context: Context, enabled: Boolean) {
        val doc = loadDocument(context)
        saveDocument(context, doc.copy(hookEnabled = enabled))
    }

    fun isHookEnabled(context: Context): Boolean = loadDocument(context).hookEnabled

    fun addProfile(context: Context, name: String): StoredProfile {
        val doc = loadDocument(context)
        val random = BuildSpoofGenerator.randomize().values
        val profile = StoredProfile(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "配置 ${doc.profiles.size + 1}" },
            values = random,
            features = HookFeatures.DEFAULT.copy(configName = name),
        )
        saveDocument(context, doc.copy(profiles = doc.profiles + profile, activeProfileId = profile.id))
        return profile
    }

    fun deleteProfile(context: Context, profileId: String): Boolean {
        val doc = loadDocument(context)
        if (doc.profiles.size <= 1) return false
        val remaining = doc.profiles.filterNot { it.id == profileId }
        if (remaining.size == doc.profiles.size) return false
        val nextActive = if (doc.activeProfileId == profileId) remaining.first().id else doc.activeProfileId
        saveDocument(context, doc.copy(profiles = remaining, activeProfileId = nextActive))
        return true
    }

    fun randomizeActive(context: Context): HookSpoofValues {
        val doc = loadDocument(context)
        val active = doc.activeProfile() ?: return HookSpoofValues.DEFAULT
        val random = BuildSpoofGenerator.randomize().values
        val updated = active.copy(values = random)
        updateActiveProfile(context, updated)
        return random
    }

    private fun syncActiveToHookSide(context: Context, document: ProfilesDocument) {
        val active = document.activeProfile() ?: return
        val stamped = active.values.copy(updatedAt = System.currentTimeMillis())
        context.getSharedPreferences(XposedConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("spoof_profile_label", stamped.profileLabel)
            .putString("spoof_build_json", HookConfig.mapToJson(stamped.buildFields))
            .putString("spoof_ids_json", HookConfig.mapToJson(stamped.idsFields))
            .putBoolean(XposedConstants.PREF_KEY_ENABLED, document.hookEnabled)
            .putLong(XposedConstants.PREF_UPDATED_AT, stamped.updatedAt)
            .commit()
        SpoofConfigFile.write(context, stamped, document.hookEnabled, active.features.normalized())
        HookConfigPublisher.publish(context)
    }

    private fun migrateLegacyIfNeeded(context: Context) {
        val newFile = File(context.filesDir, FILE_NAME)
        if (newFile.exists()) return
        val legacy = File(context.filesDir, SpoofConfigFile.FILE_NAME)
        if (!legacy.exists()) return
        try {
            val obj = JSONObject(legacy.readText())
            val values = HookConfig.readFromAppContext(context)
            val featuresJson = obj.optJSONObject(SpoofConfigFile.KEY_FEATURES)
            val features = if (featuresJson != null) {
                HookFeatures.fromJson(featuresJson)
            } else {
                HookFeatures.DEFAULT.copy(
                    spoofBuildProperties = true,
                    nativePropertyHook = true,
                    preventNativeCrash = false,
                ).normalized()
            }
            val hookEnabled = obj.optBoolean("hookEnabled", false)
            val id = UUID.randomUUID().toString()
            val profile = StoredProfile(id, features.configName, values, features)
            saveDocument(context, ProfilesDocument(id, hookEnabled, listOf(profile)))
        } catch (_: Exception) {
        }
    }
}
