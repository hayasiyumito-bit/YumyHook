package com.yumito.yumyhook.xposed.channel

import android.content.Context
import android.os.Build
import com.yumito.yumyhook.xposed.channel.strategy.profiles.ShadowhookKnownApps
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.util.zip.ZipFile

/** 从模块 APK 解压 .so 到宿主 cache，经宿主 ClassLoader 命名空间加载（与 libdevice.so 等同域）。 */
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
        val loader = classLoader
            ?: return false.also {
                XposedBridge.log(
                    "${XposedConstants.TAG}: native load skip: host ClassLoader required (avoid module ns)",
                )
            }
        synchronized(this) {
            if (loaded) return true
            return try {
                purgeStaleExtract(appDataDir)
                loadFromApk(moduleApkPath, File(appDataDir, EXTRACT_DIR), libs, loader)
            } catch (e: Throwable) {
                loaded = false
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
            loadNativeInHostNamespace(classLoader, File(destDir, libName).absolutePath)
        }
        loaded = true
        XposedBridge.log(
            "${XposedConstants.TAG}: native loaded host-ns abi=$abi libs=${libs.joinToString()} " +
                "dir=${destDir.absolutePath}",
        )
        return true
    }

  /**
   * 必须在宿主 ClassLoader 的 linker namespace 加载，禁止回退 System.load（会进模块隔离 ns）。
   * Android 14+ 封禁 Runtime.load(path, ClassLoader)；改走 VMRuntime.nativeLoad。
   */
    private fun loadNativeInHostNamespace(hostClassLoader: ClassLoader, absolutePath: String) {
        if (Build.VERSION.SDK_INT >= 34) {
            loadViaVmRuntime(hostClassLoader, absolutePath)
            return
        }
        val runtime = Runtime.getRuntime()
        var lastError: Throwable? = null
        for (method in arrayOf("load", "load0")) {
            try {
                if (method == "load") {
                    XposedHelpers.callMethod(runtime, method, absolutePath, hostClassLoader)
                } else {
                    XposedHelpers.callMethod(runtime, method, hostClassLoader, absolutePath)
                }
                XposedBridge.log(
                    "${XposedConstants.TAG}: native load host-ns via Runtime.$method " +
                        "cl=${hostClassLoader.javaClass.name} path=$absolutePath",
                )
                return
            } catch (e: Throwable) {
                lastError = e
                XposedBridge.log(
                    "${XposedConstants.TAG}: Runtime.$method(hostCl) failed: ${e.message}",
                )
            }
        }
        throw IllegalStateException(
            "cannot load native lib in host namespace: $absolutePath",
            lastError,
        )
    }

    private fun loadViaVmRuntime(hostClassLoader: ClassLoader, absolutePath: String) {
        val vmRuntime = XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("dalvik.system.VMRuntime", null),
            "getRuntime",
        )
        val caller = hostCallerClass(hostClassLoader)
        var lastError: Throwable? = null
        val argVariants = listOf(
            arrayOf(absolutePath, hostClassLoader, caller),
            arrayOf(absolutePath, hostClassLoader),
        )
        for (args in argVariants) {
            try {
                val err = XposedHelpers.callMethod(vmRuntime, "nativeLoad", *args) as? String
                if (!err.isNullOrBlank()) {
                    throw UnsatisfiedLinkError(err)
                }
                XposedBridge.log(
                    "${XposedConstants.TAG}: native load host-ns via VMRuntime.nativeLoad " +
                        "args=${args.size} cl=${hostClassLoader.javaClass.name} caller=${caller.name} " +
                        "path=$absolutePath",
                )
                return
            } catch (e: Throwable) {
                lastError = e
                XposedBridge.log(
                    "${XposedConstants.TAG}: VMRuntime.nativeLoad(${args.size}arg) failed: ${e.message}",
                )
            }
        }
        throw IllegalStateException(
            "cannot load native lib in host namespace: $absolutePath",
            lastError,
        )
    }

    /** 优先用宿主 APK 内的类作 caller，避免模块 Class 影响 linker 解析。 */
    private fun hostCallerClass(hostClassLoader: ClassLoader): Class<*> {
        for (name in arrayOf("android.app.Application", "android.content.Context")) {
            try {
                @Suppress("UNCHECKED_CAST")
                return hostClassLoader.loadClass(name) as Class<*>
            } catch (_: Throwable) {
            }
        }
        return NativeLibLoader::class.java
    }

    private fun preferredAbi(): String {
        val abis = Build.SUPPORTED_ABIS ?: emptyArray()
        if (abis.contains("arm64-v8a")) return "arm64-v8a"
        if (abis.contains("armeabi-v7a")) return "armeabi-v7a"
        return abis.firstOrNull() ?: "arm64-v8a"
    }
}
