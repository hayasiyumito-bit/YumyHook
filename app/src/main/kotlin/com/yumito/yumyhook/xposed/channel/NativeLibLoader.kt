package com.yumito.yumyhook.xposed.channel

import android.os.Build
import com.yumito.yumyhook.xposed.channel.strategy.profiles.ShadowhookKnownApps
import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.runtime.HookReentryGuard
import dalvik.system.BaseDexClassLoader
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.util.zip.ZipFile

/** 从模块 APK 解压 .so 到宿主 code_cache，并注入宿主 ClassLoader 命名空间。 */
object NativeLibLoader {

    private const val EXTRACT_DIR = "code_cache/yumyhook_native"
    private val NATIVE_LIBS = listOf("libshadowhook.so", "libyumyhook_native.so")
    private val NATIVE_ONLY = listOf("libyumyhook_native.so")

    @Volatile private var loaded = false
    @Volatile private var probed = false
    @Volatile private var procStealthActive = false
    private var mapsLines: List<String> = emptyList()

    fun isLoaded(): Boolean = loaded

    /** proc stealth hook 装后禁止 live 读 maps（会重入）。 */
    fun markProcStealthActive() { procStealthActive = true }

    fun isIntegrityLibMapped(): Boolean { ensureProbed(); return mapsLines.any { it.lowercase().contains("pairipcore") } }
    fun isHostNativeReady(): Boolean { ensureProbed(); return isHostPresent() || mapsLines.any { isHostCrashLib(it) } }
    fun isHostPresent(): Boolean { ensureProbed(); return mapsLines.any { it.contains("libshadowhook.so") && !it.contains("yumyhook_native") } }

    private fun ensureProbed() {
        if (probed) return
        synchronized(this) {
            if (probed) return
            mapsLines = if (procStealthActive) emptyList() 
            else HookReentryGuard.runMapsBypass {
                runCatching { File("/proc/self/maps").readLines() }.getOrElse { emptyList() }
            }
            probed = true
        }
    }

    private fun isHostCrashLib(line: String): Boolean {
        if (line.contains("yumyhook_native")) return false
        val l = line.lowercase()
        return listOf("wechatcrash", "libbugly", "libgaea", "crashsdk", "pairipcore", "rmonitor", "qimei", "marsxlog", "libqsec", "libmsfboot", "libtencentloc").any { l.contains(it) }
    }

    fun ensureLoaded(moduleApkPath: String, appDataDir: String, packageName: String? = null, classLoader: ClassLoader? = null, reuseHostShadowhook: Boolean = false, callerClass: Class<*>? = null): Boolean {
        if (loaded) return true
        if (moduleApkPath.isBlank() || appDataDir.isBlank() || classLoader == null) return false
        ensureProbed()
        val pkg = packageName.orEmpty()
        val hostShadowhook = isHostPresent()
        if (ShadowhookKnownApps.isKnown(pkg) && !hostShadowhook && !reuseHostShadowhook) return false
        
        val libs = if (hostShadowhook || reuseHostShadowhook) NATIVE_ONLY else NATIVE_LIBS
        synchronized(this) {
            if (loaded) return true
            return try {
                File(appDataDir, "cache/yumyhook_native").deleteRecursively()
                val destDir = File(appDataDir, EXTRACT_DIR)
                destDir.deleteRecursively()
                destDir.mkdirs()
                val abi = preferredAbi()
                ZipFile(moduleApkPath).use { apk ->
                    libs.forEach { lib ->
                        val entry = apk.getEntry("lib/$abi/$lib") ?: throw IllegalStateException("missing $lib")
                        File(destDir, lib).outputStream().use { out -> apk.getInputStream(entry).use { it.copyTo(out) } }
                        File(destDir, lib).apply { setReadable(true, false); setExecutable(true, false) }
                    }
                }
                NativeJniHost.ensureModuleDexOnHost(classLoader, moduleApkPath)
                (classLoader as? BaseDexClassLoader)?.let { XposedHelpers.callMethod(it, "addNativePath", listOf(destDir.absolutePath)) }
                val jniClass = NativeJniHost.hostClass("com.yumito.yumyhook.xposed.channel.NativeJni", classLoader, moduleApkPath)
                libs.forEach { lib ->
                    val caller = if (lib.contains("shadowhook")) NativeJniHost.hostClass("com.bytedance.shadowhook.ShadowHook", classLoader, moduleApkPath) else jniClass
                    loadInHostNs(classLoader, File(destDir, lib).absolutePath, caller)
                }
                NativeJniHost.bind(classLoader, moduleApkPath)
                loaded = true
                XposedBridge.log("${XposedConstants.TAG}: native loaded libs=${libs.joinToString()}")
                true
            } catch (e: Throwable) { XposedBridge.log("${XposedConstants.TAG}: native load fail: ${e.message}"); false }
        }
    }

    private fun loadInHostNs(cl: ClassLoader, path: String, caller: Class<*>) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val method = Runtime::class.java.getDeclaredMethod("nativeLoad", String::class.java, ClassLoader::class.java, Class::class.java).apply { isAccessible = true }
                val err = method.invoke(null, path, cl, caller) as? String
                if (err.isNullOrBlank()) return
            } catch (_: Throwable) {}
        }
        val runtime = Runtime.getRuntime()
        listOf("load", "load0").forEach { method ->
            try {
                if (method == "load") XposedHelpers.callMethod(runtime, method, path, cl)
                else XposedHelpers.callMethod(runtime, method, cl, path)
                return
            } catch (_: Throwable) {}
        }
        throw UnsatisfiedLinkError("cannot load $path")
    }

    private fun preferredAbi(): String = Build.SUPPORTED_ABIS?.let { abis ->
        if (abis.contains("arm64-v8a")) "arm64-v8a" else if (abis.contains("armeabi-v7a")) "armeabi-v7a" else abis.firstOrNull()
    } ?: "arm64-v8a"
}
