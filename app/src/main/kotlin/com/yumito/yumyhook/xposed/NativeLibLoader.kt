package com.yumito.yumyhook.xposed

import android.content.Context
import android.os.Build
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.zip.ZipFile

/** 从模块 APK 解压 .so 到宿主 App cache 并 System.load。 */
object NativeLibLoader {

    @Volatile
    private var loaded = false

    fun ensureLoaded(moduleApkPath: String, hostContext: Context): Boolean {
        if (loaded) return true
        if (moduleApkPath.isBlank()) return false
        synchronized(this) {
            if (loaded) return true
            return try {
                val abi = preferredAbi()
                val entry = "lib/$abi/libyumyhook_native.so"
                ZipFile(moduleApkPath).use { apk ->
                    val zipEntry = apk.getEntry(entry)
                        ?: throw IllegalStateException("missing $entry in module apk")
                    val destDir = File(hostContext.cacheDir, "yumyhook_native")
                    if (!destDir.exists() && !destDir.mkdirs()) {
                        throw IllegalStateException("cannot create ${destDir.absolutePath}")
                    }
                    val dest = File(destDir, "libyumyhook_native.so")
                    apk.getInputStream(zipEntry).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    dest.setReadable(true, false)
                    dest.setExecutable(true, false)
                    System.load(dest.absolutePath)
                }
                loaded = true
                XposedBridge.log("${XposedConstants.TAG}: native loaded abi=$abi")
                true
            } catch (e: Throwable) {
                XposedBridge.log("${XposedConstants.TAG}: native load failed: ${e.message}")
                false
            }
        }
    }

    private fun preferredAbi(): String {
        val abis = Build.SUPPORTED_ABIS ?: emptyArray()
        if (abis.contains("arm64-v8a")) return "arm64-v8a"
        if (abis.contains("armeabi-v7a")) return "armeabi-v7a"
        return abis.firstOrNull() ?: "arm64-v8a"
    }
}
