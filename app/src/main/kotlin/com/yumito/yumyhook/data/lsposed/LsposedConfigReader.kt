package com.yumito.yumyhook.data.lsposed

import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.yumito.yumyhook.xposed.config.XposedConstants
import java.io.File

/** 通过 Root 复制并读取 LSPosed SQLite 配置（Provider 在 Android 15+ 常不可用）。 */
object LsposedConfigReader {

    private const val LSPOSED_DB_PATH = "/data/adb/lspd/config/modules_config.db"
    private const val CACHE_DB_NAME = "lspd_modules_config.db"

    data class ModuleState(
        val enabled: Boolean,
        val scopedPackages: List<String>,
    )

    private data class ModulesTableSchema(
        val packageColumn: String,
        val enabledColumn: String?,
        val idColumn: String?,
    )

    private data class ScopeTableSchema(
        val appPackageColumn: String,
        val modulePackageColumn: String?,
        val moduleIdColumn: String?,
    )

    private data class LsposedDbSchema(
        val modules: ModulesTableSchema,
        val scope: ScopeTableSchema?,
    )

    @Volatile
    private var cachedSchema: LsposedDbSchema? = null

    @Volatile
    private var cachedSchemaDbMtime: Long = 0L

    private fun invalidateSchemaCache() {
        cachedSchema = null
        cachedSchemaDbMtime = 0L
    }

    fun readModuleState(context: Context, modulePackage: String = XposedConstants.MODULE_PACKAGE, useRoot: Boolean = true): ModuleState? {
        if (!useRoot) return null
        val dbFile = copyDbToCache(context) ?: return null
        return readModuleStateFromFile(dbFile, modulePackage)
    }

    /** 合并 DB 拷贝 + root sqlite 多策略，读取模块作用域内 App 包名。 */
    fun readScopedPackages(
        context: Context,
        modulePackage: String = XposedConstants.MODULE_PACKAGE,
        useRoot: Boolean = true,
    ): List<String> {
        if (!useRoot) return emptyList()
        val merged = linkedSetOf<String>()
        readModuleState(context, modulePackage, useRoot = true)?.scopedPackages?.forEach(merged::add)
        queryScopeViaRootSqlite(modulePackage).forEach(merged::add)
        return merged
            .filter { it.isNotBlank() && it != modulePackage && it != "lspd" }
            .toList()
    }

    fun isFrameworkPresent(useRoot: Boolean = false): Boolean {
        if (useRoot && RootShell.ensureRoot()) {
            if (RootShell.exec("test -f $LSPOSED_DB_PATH").exitCode == 0) return true
            val ps = RootShell.exec("pidof lspd")
            if (ps.exitCode == 0 && ps.output.isNotBlank()) return true
        }
        return false
    }

    fun isManagerInstalled(context: Context): Boolean {
        val packages = listOf(
            "org.lsposed.manager",
            "io.github.lsposed.manager",
        )
        val pm = context.packageManager
        return packages.any { pkg ->
            PackageVisibility.isPackageInstalled(context, pkg) ||
                try {
                    pm.getPackageInfo(pkg, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
        }
    }

    /** LSPosed Manager 版本号；无 Manager 时返回 null。 */
    fun resolveManagerVersion(context: Context): String? {
        val pm = context.packageManager
        for (pkg in listOf("org.lsposed.manager", "io.github.lsposed.manager")) {
            try {
                val info = pm.getPackageInfo(pkg, 0)
                val name = info.versionName?.trim().orEmpty()
                if (name.isNotEmpty()) return name
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return null
    }

    /**
     * LSPosed 框架版本：Manager → Root module.prop → getprop → dumpsys（隐藏 Manager 时）。
     */
    fun resolveFrameworkVersion(context: Context, useRoot: Boolean = false): String? {
        resolveManagerVersion(context)?.let { return it }
        if (!useRoot || !RootShell.ensureRoot()) return null

        for (path in LSPOSED_MODULE_PROP_PATHS) {
            val out = RootShell.exec("cat '$path' 2>/dev/null")
            if (out.exitCode == 0) {
                parseModulePropVersion(out.output)?.let { return it }
            }
        }

        for (key in listOf("ro.lsposed.version", "persist.sys.lsposed.version")) {
            val ver = RootShell.exec("getprop $key").output.trim()
            if (ver.isNotEmpty()) return ver
        }

        for (pkg in listOf("org.lsposed.manager", "io.github.lsposed.manager")) {
            val ver = readPackageVersionViaRoot(pkg)
            if (!ver.isNullOrBlank()) return ver
        }
        return null
    }

    private val LSPOSED_MODULE_PROP_PATHS = listOf(
        "/data/adb/modules/zygisk_lsposed/module.prop",
        "/data/adb/modules/riru_lsposed/module.prop",
        "/data/adb/modules/lsposed/module.prop",
    )

    private fun parseModulePropVersion(text: String): String? {
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("version=") -> {
                    trimmed.removePrefix("version=").trim().takeIf { it.isNotEmpty() }?.let { return it }
                }
                trimmed.startsWith("versionName=") -> {
                    trimmed.removePrefix("versionName=").trim().takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
        }
        return null
    }

    private fun readPackageVersionViaRoot(packageName: String): String? {
        val dumpsys = RootShell.exec("dumpsys package $packageName 2>/dev/null")
        if (dumpsys.exitCode != 0) return null
        val fromDumpsys = Regex("""versionName=([^\s]+)""")
            .find(dumpsys.output)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        if (!fromDumpsys.isNullOrEmpty()) return fromDumpsys

        val pmPath = RootShell.exec("pm path $packageName 2>/dev/null").output.trim()
        if (pmPath.isBlank()) return null
        val apkPath = pmPath.removePrefix("package:").trim()
        if (apkPath.isBlank()) return null
        val badging = RootShell.exec("aapt dump badging '$apkPath' 2>/dev/null")
        if (badging.exitCode != 0) return null
        return Regex("""versionName='([^']+)'""")
            .find(badging.output)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun queryModuleEnabledViaProvider(context: Context, modulePackage: String): Boolean? {
        val authorities = listOf(
            "org.lsposed.manager.status",
            "io.github.lsposed.manager.status",
            "org.lsposed.manager",
            "io.github.lsposed.manager",
        )
        for (authority in authorities) {
            if (!isProviderAvailable(context, authority)) continue
            queryProvider(context, authority, modulePackage)?.let { return it }
        }
        return null
    }

    private fun isProviderAvailable(context: Context, authority: String): Boolean {
        return context.packageManager.resolveContentProvider(authority, 0) != null
    }

    private fun queryProvider(context: Context, authority: String, modulePackage: String): Boolean? {
        val uri = Uri.parse("content://$authority")
        val methods = listOf("getModuleState", "isModuleEnabled", "module")
        for (method in methods) {
            try {
                val bundle = context.contentResolver.call(uri, method, modulePackage, null) ?: continue
                if (bundle.containsKey("enabled")) return bundle.getBoolean("enabled")
                if (bundle.containsKey("active")) return bundle.getBoolean("active")
                if (bundle.containsKey("isEnabled")) return bundle.getBoolean("isEnabled")
            } catch (_: Throwable) {
            }
        }
        val statusUri = Uri.parse("content://$authority/status")
        try {
            context.contentResolver.query(statusUri, null, null, arrayOf(modulePackage), null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                readEnabledFromCursor(cursor, modulePackage)?.let { return it }
            }
        } catch (_: Throwable) {
        }
        try {
            context.contentResolver.query(statusUri, null, "package=?", arrayOf(modulePackage), null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                readEnabledFromCursor(cursor, modulePackage)?.let { return it }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun readEnabledFromCursor(cursor: android.database.Cursor, packageName: String): Boolean? {
        val enabledIdx = cursor.getColumnIndex("enabled")
        if (enabledIdx >= 0) return cursor.getInt(enabledIdx) == 1
        val pkgIdx = cursor.getColumnIndex(packageName)
        if (pkgIdx >= 0) return cursor.getInt(pkgIdx) == 1
        if (cursor.columnCount == 1) return cursor.getInt(0) == 1
        return null
    }

    /**
     * LSPosed 使用 WAL；只 cp 主 .db 会拿到旧快照（新勾选 App 不在里面）。
     * 优先 sqlite3 .backup，否则 cp db + -wal + -shm。
     */
    private fun copyDbToCache(context: Context): File? {
        if (!RootShell.ensureRoot()) return null
        invalidateSchemaCache()
        val cacheFile = File(context.cacheDir, CACHE_DB_NAME)
        val cachePath = cacheFile.cacheQuote()
        val walPath = "${cacheFile.absolutePath}-wal"
        val shmPath = "${cacheFile.absolutePath}-shm"
        cacheFile.delete()
        File(walPath).delete()
        File(shmPath).delete()

        val backup = RootShell.exec(
            """
            if command -v sqlite3 >/dev/null 2>&1; then
              sqlite3 '$LSPOSED_DB_PATH' "PRAGMA wal_checkpoint(TRUNCATE);"
              sqlite3 '$LSPOSED_DB_PATH' ".backup '$cachePath'"
            else
              exit 127
            fi
            """.trimIndent(),
        )
        val copied = if (backup.exitCode == 0 && cacheFile.exists() && cacheFile.length() > 0L) {
            true
        } else {
            val shellCopy = RootShell.exec(
                """
                cp '$LSPOSED_DB_PATH' '$cachePath' &&
                cp '${LSPOSED_DB_PATH}-wal' '$walPath' 2>/dev/null || true &&
                cp '${LSPOSED_DB_PATH}-shm' '$shmPath' 2>/dev/null || true &&
                chmod 644 '$cachePath' '$walPath' '$shmPath' 2>/dev/null || chmod 644 '$cachePath'
                """.trimIndent(),
            )
            shellCopy.exitCode == 0 && cacheFile.exists() && cacheFile.length() > 0L
        }
        if (!copied) return null
        return cacheFile
    }

    private fun File.cacheQuote(): String = absolutePath.replace("'", "'\\''")

    private fun readModuleStateFromFile(dbFile: File, modulePackage: String): ModuleState? {
        return try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val schema = loadSchema(db, dbFile.lastModified()) ?: return null
                val enabled = queryModuleEnabled(db, schema.modules, modulePackage) ?: return null
                val scope = mergeScopedPackages(db, schema, modulePackage)
                ModuleState(enabled, scope)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 单元测试：验证 scope JOIN 能读出全部勾选 App。 */
    internal fun readScopedPackagesFromDbFile(dbFile: File, modulePackage: String): List<String> {
        return try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val schema = loadSchema(db, dbFile.lastModified()) ?: return emptyList()
                mergeScopedPackages(db, schema, modulePackage)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadSchema(db: SQLiteDatabase, dbMtime: Long): LsposedDbSchema? {
        if (cachedSchema != null && cachedSchemaDbMtime == dbMtime) {
            return cachedSchema
        }
        val moduleColumns = readTableColumns(db, "modules") ?: return null
        val scopeColumns = readTableColumns(db, "scope")

        val packageColumn = pickColumn(
            moduleColumns,
            "module_pkg_name",
            "package_name",
            "module_package_name",
        ) ?: return null
        val enabledColumn = moduleColumns.firstOrNull { it == "enabled" }
        val moduleIdColumn = pickColumn(moduleColumns, "mid", "module_id", "_id")

        val modules = ModulesTableSchema(packageColumn, enabledColumn, moduleIdColumn)
        val scope = scopeColumns?.let { cols ->
            val appCol = pickColumn(cols, "app_pkg_name", "app_package_name", "package_name", "pkg")
                ?: return@let null
            ScopeTableSchema(
                appPackageColumn = appCol,
                modulePackageColumn = pickColumn(
                    cols,
                    "module_pkg_name",
                    "package_name",
                    "module_package_name",
                    "module",
                ),
                moduleIdColumn = pickColumn(cols, "mid", "module_id", "module_mid"),
            )
        }
        return LsposedDbSchema(modules, scope).also {
            cachedSchema = it
            cachedSchemaDbMtime = dbMtime
        }
    }

    private fun readTableColumns(db: SQLiteDatabase, table: String): Set<String>? {
        if (!tableExists(db, table)) return null
        val columns = linkedSetOf<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            if (nameIdx < 0) return null
            while (cursor.moveToNext()) {
                cursor.getString(nameIdx)?.let(columns::add)
            }
        }
        return columns.takeIf { it.isNotEmpty() }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean {
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table),
        ).use { return it.moveToFirst() }
    }

    private fun pickColumn(columns: Set<String>, vararg candidates: String): String? {
        for (candidate in candidates) {
            if (candidate in columns) return candidate
        }
        return null
    }

    private fun queryModuleEnabled(
        db: SQLiteDatabase,
        schema: ModulesTableSchema,
        modulePackage: String,
    ): Boolean? {
        val sql = if (schema.enabledColumn != null) {
            "SELECT ${schema.enabledColumn} FROM modules WHERE ${schema.packageColumn} = ? LIMIT 1"
        } else {
            "SELECT 1 FROM modules WHERE ${schema.packageColumn} = ? LIMIT 1"
        }
        return try {
            db.rawQuery(sql, arrayOf(modulePackage)).use { cursor ->
                if (!cursor.moveToFirst()) return false
                if (schema.enabledColumn == null) return true
                cursor.getInt(0) == 1
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryModuleId(
        db: SQLiteDatabase,
        schema: ModulesTableSchema,
        modulePackage: String,
    ): Long? {
        val idColumn = schema.idColumn ?: return null
        return try {
            db.rawQuery(
                "SELECT $idColumn FROM modules WHERE ${schema.packageColumn} = ? LIMIT 1",
                arrayOf(modulePackage),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                cursor.getLong(0)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun mergeScopedPackages(
        db: SQLiteDatabase,
        schema: LsposedDbSchema,
        modulePackage: String,
    ): List<String> {
        val merged = linkedSetOf<String>()
        queryScopedPackages(db, schema, modulePackage).forEach(merged::add)
        queryScopeViaRootSqlite(modulePackage).forEach(merged::add)
        return merged
            .filter { it.isNotBlank() && it != modulePackage && it != "lspd" }
            .toList()
    }

    private fun queryScopedPackages(
        db: SQLiteDatabase,
        schema: LsposedDbSchema,
        modulePackage: String,
    ): List<String> {
        val scopeSchema = schema.scope ?: return emptyList()
        val merged = linkedSetOf<String>()

        val scopeId = scopeSchema.moduleIdColumn
        val modulesId = schema.modules.idColumn
        if (scopeId != null && modulesId != null) {
            val enabledFilter = schema.modules.enabledColumn?.let { " AND m.$it = 1" }.orEmpty()
            queryDistinct(
                db,
                """
                SELECT DISTINCT s.${scopeSchema.appPackageColumn}
                FROM scope s
                INNER JOIN modules m ON s.$scopeId = m.$modulesId
                WHERE m.${schema.modules.packageColumn} = ?$enabledFilter
                """.trimIndent(),
                arrayOf(modulePackage),
            )?.let(merged::addAll)
        }

        scopeSchema.modulePackageColumn?.let { moduleCol ->
            queryDistinct(
                db,
                "SELECT DISTINCT ${scopeSchema.appPackageColumn} FROM scope WHERE $moduleCol = ?",
                arrayOf(modulePackage),
            )?.let(merged::addAll)
        }

        if (scopeId != null && merged.isEmpty()) {
            val mid = queryModuleId(db, schema.modules, modulePackage)
            if (mid != null) {
                queryDistinct(
                    db,
                    "SELECT DISTINCT ${scopeSchema.appPackageColumn} FROM scope WHERE $scopeId = ?",
                    arrayOf(mid.toString()),
                )?.let(merged::addAll)
            }
        }

        return merged.toList()
    }

    private fun queryDistinct(db: SQLiteDatabase, sql: String, args: Array<String>): List<String>? {
        return try {
            val result = linkedSetOf<String>()
            db.rawQuery(sql, args).use { cursor ->
                if (cursor.count == 0) return emptyList()
                val idx = 0
                while (cursor.moveToNext()) {
                    cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let(result::add)
                }
            }
            result.toList()
        } catch (_: Exception) {
            null
        }
    }

    private fun queryScopeViaRootSqlite(modulePackage: String): List<String> {
        if (!RootShell.ensureRoot()) return emptyList()
        val safeModule = modulePackage.filter { it.isLetterOrDigit() || it == '.' }
        if (safeModule != modulePackage || safeModule.isBlank()) return emptyList()

        val sql = """
            SELECT DISTINCT s.app_pkg_name
            FROM scope s
            INNER JOIN modules m ON s.mid = m.mid
            WHERE m.module_pkg_name = '$safeModule' AND m.enabled = 1;
        """.trimIndent().replace('\n', ' ')

        val out = RootShell.exec(
            "if command -v sqlite3 >/dev/null 2>&1; then " +
                "sqlite3 '$LSPOSED_DB_PATH' \"PRAGMA wal_checkpoint(TRUNCATE); $sql\"; " +
                "else exit 127; fi",
        )
        if (out.exitCode != 0 || out.output.isBlank()) return emptyList()
        return out.output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "lspd" && it != modulePackage }
            .distinct()
            .toList()
    }
}
