package com.yumito.yumyhook.xposed.config

import android.content.Context
import android.util.Log
import com.yumito.yumyhook.model.HookFeatures
import com.yumito.yumyhook.xposed.channel.NativeBridge
import com.yumito.yumyhook.xposed.runtime.HookReentryGuard
import com.yumito.yumyhook.xposed.runtime.SpoofRuntime
import com.yumito.yumyhook.xposed.runtime.TargetContextHolder
import de.robv.android.xposed.XSharedPreferences
import org.json.JSONObject
import java.io.File

/** Hook 侧配置与功能开关管理；统一读取 spoof_config.json 与 XSharedPreferences。 */
object HookConfig {

    private const val FILE_NAME = "spoof_config.json"
    @Volatile private var cachedValues = HookSpoofValues.DEFAULT
    @Volatile private var cachedFeatures = HookFeatures.DEFAULT
    @Volatile private var cachedEnabled = false
    @Volatile private var cachedMtime = 0L
    @Volatile private var cachedPrefsUpdatedAt = 0L

    fun isEnabledForHook(): Boolean = cachedEnabled
    fun valuesForHook(): HookSpoofValues = cachedValues
    fun features(): HookFeatures = cachedFeatures

    fun refreshHookCacheIfStale(): HookSpoofValues = refreshIfStale()
    fun refreshIfStale(): HookSpoofValues {
        val file = hookFile()
        val mtime = try { if (file.exists()) file.lastModified() else 0L } catch (_: Throwable) { 0L }
        val prefsAt = try { 
            XSharedPreferences(XposedConstants.MODULE_PACKAGE, XposedConstants.PREFS_NAME)
                .apply { reload() }
                .getLong(XposedConstants.PREF_UPDATED_AT, 0L) 
        } catch (_: Throwable) { 0L }
        
        if (mtime != cachedMtime || prefsAt != cachedPrefsUpdatedAt) return refresh()
        return cachedValues
    }

    fun refreshHookCache(): HookSpoofValues = refresh()
    fun refresh(packageName: String? = null): HookSpoofValues {
        val file = hookFile()
        val json = if (try { file.exists() } catch (_: Throwable) { false }) {
            runCatching { HookReentryGuard.runFileBypass { JSONObject(file.readText()) } }.getOrNull()
        } else null
        
        val prefs = XSharedPreferences(XposedConstants.MODULE_PACKAGE, XposedConstants.PREFS_NAME).apply { reload() }
        
        cachedEnabled = json?.optBoolean("hookEnabled") ?: prefs.getBoolean(XposedConstants.PREF_KEY_ENABLED, false)
        cachedPrefsUpdatedAt = prefs.getLong(XposedConstants.PREF_UPDATED_AT, 0L)
        cachedMtime = try { if (file.exists()) file.lastModified() else 0L } catch (_: Throwable) { 0L }

        val fromFile = json?.let { obj ->
            val build = toMap(obj.optJSONObject("buildFields"))
            if (build.isEmpty()) null 
            else HookSpoofValues(
                obj.optString("profileLabel", "default"),
                build,
                toMap(obj.optJSONObject("idsFields")),
                toMap(obj.optJSONObject("locationFields")),
                obj.optLong("updatedAt")
            )
        }
        
        val buildJson = prefs.getString("spoof_build_json", null)
        val fromPrefs = if (buildJson != null) {
            HookSpoofValues.fromJson(
                buildJson,
                prefs.getString("spoof_ids_json", "{}") ?: "{}",
                prefs.getString("spoof_profile_label", "default") ?: "default",
                cachedPrefsUpdatedAt,
                prefs.getString("spoof_location_json", "{}") ?: "{}"
            )
        } else null

        cachedValues = when {
            fromFile != null && fromFile.updatedAt >= (fromPrefs?.updatedAt ?: 0) -> fromFile
            fromPrefs != null -> fromPrefs
            fromFile != null -> fromFile
            else -> HookSpoofValues.DEFAULT
        }.let { sanitize(it) }
        
        cachedFeatures = json?.optJSONObject("features")?.let { HookFeatures.fromJson(it) }
            ?: prefs.getString(XposedConstants.PREF_FEATURES_JSON, null)?.let { runCatching { HookFeatures.fromJson(JSONObject(it)) }.getOrNull() }
            ?: HookFeatures.DEFAULT
        
        val pkg = packageName ?: TargetContextHolder.packageName
        if (!pkg.isNullOrBlank()) {
            NativeBridge.syncFromGate(cachedValues, pkg)
        }
        SpoofRuntime.reapplyIfRevisionChanged(cachedValues, "refresh")
        return cachedValues
    }

    fun sanitize(v: HookSpoofValues): HookSpoofValues = if (v.buildFields.isEmpty()) HookSpoofValues.DEFAULT else v

    private fun toMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        obj.keys().forEach { k -> 
            val v = obj.optString(k)
            if (v.isNotEmpty()) map[k] = v
        }
        return map
    }

    fun hookFile(): File {
        val mirror = File("/data/local/tmp/yumyhook/$FILE_NAME")
        if (try { mirror.exists() && mirror.length() > 0 } catch (_: Throwable) { false }) return mirror
        return File("/data/data/${XposedConstants.MODULE_PACKAGE}/files/$FILE_NAME").let { 
            if (try { it.exists() } catch (_: Throwable) { false }) it 
            else File("/data/user/0/${XposedConstants.MODULE_PACKAGE}/files/$FILE_NAME") 
        }
    }

    fun mapToJson(map: Map<String, String>): String = JSONObject().apply { map.forEach { (k, v) -> put(k, v) } }.toString()

    fun publish(context: Context, values: HookSpoofValues, enabled: Boolean, features: HookFeatures) {
        val json = JSONObject().apply {
            put("profileLabel", values.profileLabel)
            put("buildFields", JSONObject(values.buildFields))
            put("idsFields", JSONObject(values.idsFields))
            put("locationFields", JSONObject(values.locationFields))
            put("hookEnabled", enabled)
            put("updatedAt", values.updatedAt)
            put("features", features.toJson())
        }
        val file = File(context.filesDir, FILE_NAME)
        file.writeText(json.toString())
        try { file.setReadable(true, false) } catch (_: Throwable) {}
        try {
            val mirror = File("/data/local/tmp/yumyhook/$FILE_NAME")
            mirror.parentFile?.apply { mkdirs(); setReadable(true, false); setExecutable(true, false) }
            file.copyTo(mirror, true)
            mirror.setReadable(true, false)
        } catch (e: Exception) { Log.w("YH-CONFIG", "mirror fail: ${e.message}") }
    }

    fun readFromAppContext(context: Context): HookSpoofValues = readModule(context)
    fun readModule(context: Context): HookSpoofValues {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return HookSpoofValues.DEFAULT
        return runCatching { 
            val obj = JSONObject(file.readText())
            HookSpoofValues(
                obj.optString("profileLabel", "default"), 
                toMap(obj.optJSONObject("buildFields")), 
                toMap(obj.optJSONObject("idsFields")), 
                toMap(obj.optJSONObject("locationFields")), 
                obj.optLong("updatedAt")
            )
        }.getOrDefault(HookSpoofValues.DEFAULT)
    }

    fun readModuleEnabled(context: Context): Boolean {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return context.getSharedPreferences(XposedConstants.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(XposedConstants.PREF_KEY_ENABLED, false)
        return runCatching { JSONObject(file.readText()).optBoolean("hookEnabled", false) }.getOrDefault(false)
    }
}
