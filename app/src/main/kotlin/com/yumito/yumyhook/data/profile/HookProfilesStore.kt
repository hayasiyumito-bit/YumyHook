package com.yumito.yumyhook.data.profile

import android.content.Context
import com.yumito.yumyhook.data.publish.HookConfigPublisher
import com.yumito.yumyhook.feature.session.HookSessionController
import com.yumito.yumyhook.model.HookFeatures
import com.yumito.yumyhook.model.ProfilesDocument
import com.yumito.yumyhook.model.StoredProfile
import com.yumito.yumyhook.xposed.channel.build.BuildSpoofGenerator
import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.config.HookSpoofValues
import com.yumito.yumyhook.xposed.config.SpoofConfigFile
import com.yumito.yumyhook.xposed.config.XposedConstants
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 配置持久化唯一入口：多 Tab 档案 + 同步 Hook 侧 JSON/XSP。 */
object HookProfilesStore {

    private const val FILE_NAME = "hook_profiles.json"

    fun ensureDefaults(context: Context) {
        loadDocument(context)
    }

    fun load(context: Context): HookProfile = loadHookProfile(context)

    fun loadSpoofValues(context: Context): HookSpoofValues = activeProfile(context).values

    fun loadFeatures(context: Context): HookFeatures = activeProfile(context).features

    fun randomizeSpoof(context: Context): String {
        val values = randomizeActive(context)
        return values.fullParametersSummary(activeProfile(context).features)
    }

    fun save(context: Context, profile: HookProfile) {
        val doc = loadDocument(context)
        val active = doc.activeProfile() ?: return
        val updated = active.copy(values = profile.values, features = profile.features)
        saveDocument(
            context,
            doc.copy(
                hookEnabled = profile.hookEnabled,
                profiles = doc.profiles.map { if (it.id == updated.id) updated else it },
            ),
        )
        restartScopedTargetsIfHookActive(context)
    }

    fun setScopedFourChannel(context: Context, packageName: String, enabled: Boolean): Boolean {
        if (packageName.isBlank()) return false
        val profile = activeProfile(context)
        val features = profile.features.withScopedFourChannel(packageName, enabled)
        updateActiveProfile(context, profile.copy(features = features), restartPackages = listOf(packageName))
        return true
    }

    fun setScopedNative(context: Context, packageName: String, enabled: Boolean): Boolean {
        if (packageName.isBlank()) return false
        val profile = activeProfile(context)
        val features = profile.features.withScopedNative(packageName, enabled)
        updateActiveProfile(context, profile.copy(features = features), restartPackages = listOf(packageName))
        return true
    }

    fun setFeature(context: Context, key: String, enabled: Boolean): Boolean {
        if (!HookFeatures.isImplemented(key)) return false
        val profile = activeProfile(context)
        val features = profile.features.withToggle(key, enabled).normalized()
        val values = if (key == "spoofLocation" && enabled) {
            ensureLocationDefaults(profile.values)
        } else {
            profile.values
        }
        updateActiveProfile(context, profile.copy(features = features, values = values), restartAllScoped = true)
        return true
    }

    fun saveBuildFields(context: Context, fields: Map<String, String>) {
        val profile = activeProfile(context)
        val build = profile.values.buildFields.toMutableMap()
        fields.forEach { (k, v) ->
            if (v.isBlank()) build.remove(k) else build[k] = v
        }
        updateActiveProfile(
            context,
            profile.copy(values = profile.values.copy(buildFields = build)),
            restartAllScoped = true,
        )
    }

    fun saveLocationFields(context: Context, fields: Map<String, String>) {
        val profile = activeProfile(context)
        val location = profile.values.locationFields.toMutableMap()
        fields.forEach { (k, v) ->
            if (v.isBlank()) location.remove(k) else location[k] = v
        }
        val merged = com.yumito.yumyhook.xposed.channel.LocationSpoofGenerator.fieldsOrDefault(location)
        updateActiveProfile(
            context,
            profile.copy(values = profile.values.copy(locationFields = merged)),
            restartAllScoped = true,
        )
    }

    fun randomizeLocation(context: Context): Map<String, String> {
        val fields = com.yumito.yumyhook.xposed.channel.LocationSpoofGenerator.randomize()
        saveLocationFields(context, fields)
        return fields
    }

    fun saveIdsFields(context: Context, fields: Map<String, String>) {
        val profile = activeProfile(context)
        val ids = profile.values.idsFields.toMutableMap()
        fields.forEach { (k, v) ->
            if (v.isBlank()) ids.remove(k) else ids[k] = v
        }
        updateActiveProfile(
            context,
            profile.copy(values = profile.values.copy(idsFields = ids)),
            restartAllScoped = true,
        )
    }

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
        var values = active.values
        if (active.features.spoofLocation) {
            val ensured = ensureLocationDefaults(values)
            if (ensured != values) {
                values = ensured
                updateActiveProfile(context, active.copy(values = values))
            }
        }
        return HookProfile(values, active.features, doc.hookEnabled)
    }

    fun updateActiveProfile(
        context: Context,
        profile: StoredProfile,
        restartAllScoped: Boolean = false,
        restartPackages: Collection<String>? = null,
    ) {
        val doc = loadDocument(context)
        val updated = doc.profiles.map { if (it.id == profile.id) profile else it }
        saveDocument(context, doc.copy(profiles = updated))
        when {
            restartPackages != null -> restartScopedTargetsIfHookActive(context, restartPackages)
            restartAllScoped -> restartScopedTargetsIfHookActive(context)
        }
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
            .putString("spoof_location_json", HookConfig.mapToJson(stamped.locationFields))
            .putBoolean(XposedConstants.PREF_KEY_ENABLED, document.hookEnabled)
            .putLong(XposedConstants.PREF_UPDATED_AT, stamped.updatedAt)
            .putString(XposedConstants.PREF_FEATURES_JSON, active.features.toJson().toString())
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

    private fun ensureLocationDefaults(values: HookSpoofValues): HookSpoofValues {
        val loc = values.locationFields
        if (!loc["latitude"].isNullOrBlank() && !loc["longitude"].isNullOrBlank()) return values
        return values.copy(
            locationFields = com.yumito.yumyhook.xposed.channel.LocationSpoofGenerator.defaultFields(),
        )
    }

    /** Hook 已开时 Root 强停目标 App，使开关/参数变更立即生效。 */
    private fun restartScopedTargetsIfHookActive(
        context: Context,
        onlyPackages: Collection<String>? = null,
    ) {
        if (!isHookEnabled(context)) return
        HookSessionController.notifyConfigChanged(context, onlyPackages)
    }
}
