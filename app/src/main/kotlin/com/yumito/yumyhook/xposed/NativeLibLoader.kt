package com.yumito.yumyhook.xposed

import android.content.Context
import android.os.Build
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.zip.ZipFile

/** 从模块 APK 解压 .so 到宿主 App cache 并 System.load。 */
object NativeLibLoader {

    /** libyumyhook_native 动态依赖 libshadowhook，须同目录先加载。 */
    private val NATIVE_LIBS = listOf("libshadowhook.so", "libyumyhook_native.so")

    @Volatile
    private var loaded = false

    fun ensureLoaded(moduleApkPath: String, hostContext: Context): Boolean =
        ensureLoaded(moduleApkPath, hostContext.applicationInfo.dataDir)

    /** handleLoadPackage 阶段无 Application，用 appInfo.dataDir 提前加载。 */
    fun ensureLoaded(moduleApkPath: String, appDataDir: String): Boolean {
        if (loaded) return true
        if (moduleApkPath.isBlank() || appDataDir.isBlank()) return false
        synchronized(this) {
            if (loaded) return true
            return try {
                val destDir = File(appDataDir, "cache/yumyhook_native")
                loadFromApk(moduleApkPath, destDir)
            } catch (e: Throwable) {
                XposedBridge.log("${XposedConstants.TAG}: native load failed: ${e.message}")
                false
            }
        }
    }

    private fun loadFromApk(moduleApkPath: String, destDir: File): Boolean {
        val abi = preferredAbi()
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw IllegalStateException("cannot create ${destDir.absolutePath}")
        }
        ZipFile(moduleApkPath).use { apk ->
            for (libName in NATIVE_LIBS) {
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
        for (libName in NATIVE_LIBS) {
            System.load(File(destDir, libName).absolutePath)
        }
        loaded = true
        XposedBridge.log("${XposedConstants.TAG}: native loaded abi=$abi libs=${NATIVE_LIBS.joinToString()} dir=${destDir.absolutePath}")
        return true
    }

    private fun preferredAbi(): String {
        val abis = Build.SUPPORTED_ABIS ?: emptyArray()
        if (abis.contains("arm64-v8a")) return "arm64-v8a"
        if (abis.contains("armeabi-v7a")) return "armeabi-v7a"
        return abis.firstOrNull() ?: "arm64-v8a"
    }
}
