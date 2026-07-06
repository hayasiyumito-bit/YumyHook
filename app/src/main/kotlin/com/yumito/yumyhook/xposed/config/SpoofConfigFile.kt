package com.yumito.yumyhook.xposed.config

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 跨进程伪装配置 JSON。
 * Hook 侧只用 [hookSideFile] 直读路径，禁止 createPackageContext（会触发 SystemProperties 死循环）。
 */
object SpoofConfigFile {

    const val FILE_NAME = "spoof_config.json"

    private const val KEY_PROFILE = "profileLabel"
    private const val KEY_BUILD = "buildFields"
    private const val KEY_IDS = "idsFields"
    private const val KEY_ENABLED = "hookEnabled"
    private const val KEY_UPDATED_AT = "updatedAt"
    const val KEY_FEATURES = "features"

    fun hookSideFile(): File {
        val mirror = publicMirrorFile()
        if (mirror.exists() && mirror.length() > 0L) return mirror
        val candidates = listOf(
            "/data/data/${XposedConstants.MODULE_PACKAGE}/files/$FILE_NAME",
            "/data/user/0/${XposedConstants.MODULE_PACKAGE}/files/$FILE_NAME",
        )
        for (path in candidates) {
            val file = File(path)
            if (file.exists()) return file
        }
        return File(candidates.first())
    }

    fun publicMirrorFile(): File = File("/data/local/tmp/yumyhook/$FILE_NAME")

    /** UI 写入后：chmod 私有文件 + 镜像到 /data/local/tmp 供目标进程直读。 */
    fun publishReadable(context: Context) {
        val target = File(context.filesDir, FILE_NAME)
        chmodWorldReadable(target)
        mirrorToPublic(target)
    }

    private fun mirrorToPublic(source: File) {
        if (!source.exists() || source.length() == 0L) return
        try {
            val dir = publicMirrorFile().parentFile ?: return
            if (!dir.exists()) dir.mkdirs()
            chmodWorldReadable(dir)
            source.copyTo(publicMirrorFile(), overwrite = true)
            chmodWorldReadable(publicMirrorFile())
        } catch (e: Exception) {
            Log.w(XposedConstants.TAG, "SpoofConfigFile.mirror failed: ${e.message}")
        }
    }

    private fun chmodWorldReadable(file: File) {
        try {
            file.setReadable(true, false)
            file.setWritable(true, true)
        } catch (_: Throwable) {
        }
    }

    fun write(context: Context, values: HookSpoofValues, enabled: Boolean = true, features: com.yumito.yumyhook.model.HookFeatures = com.yumito.yumyhook.model.HookFeatures.DEFAULT) {
        val updatedAt = if (values.updatedAt > 0L) values.updatedAt else System.currentTimeMillis()
        val stamped = values.copy(updatedAt = updatedAt)
        val json = JSONObject().apply {
            put(KEY_PROFILE, stamped.profileLabel)
            put(KEY_BUILD, JSONObject(stamped.buildFields))
            put(KEY_IDS, JSONObject(stamped.idsFields))
            put(KEY_ENABLED, enabled)
            put(KEY_UPDATED_AT, updatedAt)
            put(KEY_FEATURES, features.toJson())
        }
        val dir = context.filesDir
        val target = File(dir, FILE_NAME)
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(target)) {
            target.writeText(json.toString())
            tmp.delete()
        }
        try {
            target.setReadable(true, false)
        } catch (_: Throwable) {
        }
    }

    /** 模块 UI 进程读本地文件。 */
    fun readModule(context: Context): HookSpoofValues? = readFromFile(File(context.filesDir, FILE_NAME))

    /** Hook 目标进程读模块 files，无 Context、无 createPackageContext。 */
    fun readHookSide(): HookSpoofValues? = readFromFile(hookSideFile())

    fun readHookEnabled(): Boolean? {
        val file = hookSideFile()
        if (!file.exists()) return null
        return try {
            JSONObject(file.readText()).optBoolean(KEY_ENABLED, false)
        } catch (_: Exception) {
            null
        }
    }

    fun readHookFeatures(): com.yumito.yumyhook.model.HookFeatures {
        val file = hookSideFile()
        if (!file.exists()) return com.yumito.yumyhook.model.HookFeatures.DEFAULT
        return try {
            com.yumito.yumyhook.model.HookFeatures.fromJson(JSONObject(file.readText()).optJSONObject(KEY_FEATURES))
        } catch (_: Exception) {
            com.yumito.yumyhook.model.HookFeatures.DEFAULT
        }
    }

    private fun readFromFile(file: File): HookSpoofValues? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val obj = JSONObject(file.readText())
            val profile = obj.optString(KEY_PROFILE, HookSpoofValues.DEFAULT.profileLabel)
            val build = jsonObjectToMap(obj.optJSONObject(KEY_BUILD))
            val ids = jsonObjectToMap(obj.optJSONObject(KEY_IDS))
            val updatedAt = obj.optLong(KEY_UPDATED_AT, 0L)
            HookConfig.sanitize(HookSpoofValues(profile, build, ids, updatedAt))
        } catch (e: Exception) {
            Log.w(XposedConstants.TAG, "SpoofConfigFile.read failed: ${e.message}")
            null
        }
    }

    private fun jsonObjectToMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = linkedMapOf<String, String>()
        obj.keys().forEach { key ->
            map[key] = obj.optString(key, "")
        }
        return map.filterValues { it.isNotEmpty() }
    }
}
