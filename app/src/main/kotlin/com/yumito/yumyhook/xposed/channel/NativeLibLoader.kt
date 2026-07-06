package com.yumito.yumyhook.xposed.channel

import android.content.Context
import android.os.Build
import com.yumito.yumyhook.xposed.channel.strategy.profiles.ShadowhookKnownApps
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.zip.ZipFile

/** 从模块 APK 解压 .so 到宿主 cache，用模块 ClassLoader 加载。 */
object NativeLibLoader {

    private const val EXTRACT_DIR = "cache/yumyhook_native"

    private val NATIVE_LIBS = listOf("libshadowhook.so", "libyumyhook_native.so")
    private val NATIVE_ONLY = listOf("libyumyhook_native.so")

    @Volatile
    private var loaded = false

    fun ensureLoaded(
        moduleApkPath: String,
        hostContext: Context,
        packageName: String? = null,
        classLoader: ClassLoader? = null,
    ): Boolean = ensureLoaded(
        moduleApkPath,
        hostContext.applicationInfo.dataDir,
        packageName,
        classLoader,
    )

    fun ensureLoaded(
        moduleApkPath: String,
        appDataDir: String,
        packageName: String? = null,
        classLoader: ClassLoader? = null,
        reuseHostShadowhook: Boolean = false,
    ): Boolean {
        if (loaded) return true
        if (moduleApkPath.isBlank() || appDataDir.isBlank()) return false
        val pkg = packageName.orEmpty()
        val hostShadowhook = HostShadowhookDetector.isHostPresent()
        if (ShadowhookKnownApps.isKnown(pkg) && !hostShadowhook && !reuseHostShadowhook) {
            XposedBridge.log(
                "${XposedConstants.TAG}: native load deferred until host shadowhook pkg=$pkg",
            )
            return false
        }
        val libs = if (hostShadowhook || reuseHostShadowhook) NATIVE_ONLY else NATIVE_LIBS
        if (hostShadowhook || reuseHostShadowhook) {
            XposedBridge.log(
                "${XposedConstants.TAG}: native load via host shadowhook, libs=${libs.joinToString()}",
            )
        }
        val loader = classLoader ?: NativeBridge::class.java.classLoader
            ?: return false.also {
                XposedBridge.log("${XposedConstants.TAG}: native load skip: no class loader")
            }
        synchronized(this) {
            if (loaded) return true
            return try {
                purgeStaleExtract(appDataDir)
                loadFromApk(moduleApkPath, File(appDataDir, EXTRACT_DIR), libs, loader)
            } catch (e: Throwable) {
                XposedBridge.log("${XposedConstants.TAG}: native load failed: ${e.message}")
                false
            }
        }
    }

    private fun purgeStaleExtract(appDataDir: String) {
        listOf("code_cache/yumyhook_native", EXTRACT_DIR).forEach { rel ->
            val dir = File(appDataDir, rel)
            if (!dir.exists()) return@forEach
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    private fun loadFromApk(
        moduleApkPath: String,
        destDir: File,
        libs: List<String>,
        classLoader: ClassLoader,
    ): Boolean {
        val abi = preferredAbi()
        if (destDir.exists()) {
            destDir.listFiles()?.forEach { it.delete() }
        } else if (!destDir.mkdirs()) {
            throw IllegalStateException("cannot create ${destDir.absolutePath}")
        }
        ZipFile(moduleApkPath).use { apk ->
            for (libName in libs) {
                val entry = "lib/$abi/$libName"
                val zipEntry = apk.getEntry(entry)
                    ?: throw IllegalStateException("missing $entry in module apk")
                val dest = File(destDir, libName)
                apk.getInputStream(zipEntry).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                dest.setReadable(true, false)
                dest.setExecutable(true, false)
            }
        }
        for (libName in libs) {
            NativeBridge.loadNativeLibrary(File(destDir, libName).absolutePath)
        }
        loaded = true
        XposedBridge.log(
            "${XposedConstants.TAG}: native loaded abi=$abi libs=${libs.joinToString()} dir=${destDir.absolutePath}",
        )
        return true
    }

    private fun preferredAbi(): String {
        val abis = Build.SUPPORTED_ABIS ?: emptyArray()
        if (abis.contains("arm64-v8a")) return "arm64-v8a"
        if (abis.contains("armeabi-v7a")) return "armeabi-v7a"
        return abis.firstOrNull() ?: "arm64-v8a"
    }
}
