package com.yumito.yumyhook.model

import com.yumito.yumyhook.xposed.config.HookSpoofValues
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class StoredProfile(
    val id: String,
    val name: String,
    val values: HookSpoofValues,
    val features: HookFeatures,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("profileLabel", values.profileLabel)
        put("buildFields", JSONObject(values.buildFields))
        put("idsFields", JSONObject(values.idsFields))
        put("updatedAt", values.updatedAt)
        put("features", features.toJson())
    }

    companion object {
        fun fromJson(obj: JSONObject): StoredProfile {
            val id = obj.optString("id", UUID.randomUUID().toString())
            val name = obj.optString("name", "默认")
            val profileLabel = obj.optString("profileLabel", name)
            val build = jsonToMap(obj.optJSONObject("buildFields"))
            val ids = jsonToMap(obj.optJSONObject("idsFields"))
            val updatedAt = obj.optLong("updatedAt", 0L)
            val values = HookSpoofValues(profileLabel, build, ids, updatedAt)
            val features = HookFeatures.fromJson(obj.optJSONObject("features")).copy(configName = name)
            return StoredProfile(id, name, values, features)
        }

        private fun jsonToMap(obj: JSONObject?): Map<String, String> {
            if (obj == null) return emptyMap()
            val map = linkedMapOf<String, String>()
            obj.keys().forEach { key -> map[key] = obj.optString(key, "") }
            return map.filterValues { it.isNotEmpty() }
        }
    }
}

data class ProfilesDocument(
    val activeProfileId: String,
    val hookEnabled: Boolean,
    val profiles: List<StoredProfile>,
) {
    fun activeProfile(): StoredProfile? = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()

    fun toJson(): JSONObject = JSONObject().apply {
        put("activeProfileId", activeProfileId)
        put("hookEnabled", hookEnabled)
        put("profiles", JSONArray().apply { profiles.forEach { put(it.toJson()) } })
    }

    companion object {
        fun default(): ProfilesDocument {
            val id = UUID.randomUUID().toString()
            val values = HookSpoofValues.DEFAULT
            val profile = StoredProfile(id, "默认", values, HookFeatures.DEFAULT.copy(configName = "默认"))
            return ProfilesDocument(id, hookEnabled = false, profiles = listOf(profile))
        }

        fun fromJson(text: String): ProfilesDocument? {
            return try {
                val root = JSONObject(text)
                val profiles = mutableListOf<StoredProfile>()
                root.optJSONArray("profiles")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        profiles += StoredProfile.fromJson(arr.getJSONObject(i))
                    }
                }
                if (profiles.isEmpty()) return null
                val activeId = root.optString("activeProfileId", profiles.first().id)
                ProfilesDocument(
                    activeProfileId = activeId,
                    hookEnabled = root.optBoolean("hookEnabled", false),
                    profiles = profiles,
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
