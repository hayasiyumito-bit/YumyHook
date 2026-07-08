package com.yumito.yumyhook.xposed.channel

import android.content.Context
import android.os.Build
import com.yumito.yumyhook.xposed.channel.strategy.profiles.ShadowhookKnownApps
import com.yumito.yumyhook.xposed.config.XposedConstants
import dalvik.system.BaseDexClassLoader
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.reflect.Method
import java.util.zip.ZipFile

/** 从模块 APK 解压 .so 到宿主 code_cache，经宿主 ClassLoader 命名空间加载（与 libdevice.so 等同域）。 */
object NativeLibLoader {

    /** cache/ 不在 SDK 34 linker permitted_path；code_cache 可 dlopen。 */
    private const val EXTRACT_DIR = "code_cache/yumyhook_native"

    private val NATIVE_LIBS = listOf("libshadowhook.so", "libyumyhook_native.so")
    private val NATIVE_ONLY = listOf("libyumyhook_native.so")

    @Volatile
    private var loaded = false

    fun isLoaded(): Boolean = loaded

    fun ensureLoaded(
        moduleApkPath: String,
        hostContext: Context,
        packageName: String? = null,
        classLoader: ClassLoader? = null,
        callerClass: Class<*>? = null,
    ): Boolean = ensureLoaded(
        moduleApkPath,
        hostContext.applicationInfo.dataDir,
        packageName,
        classLoader,
        callerClass = callerClass,
    )

    fun ensureLoaded(
        moduleApkPath: String,
        appDataDir: String,
        packageName: String? = null,
        classLoader: ClassLoader? = null,
        reuseHostShadowhook: Boolean = false,
        callerClass: Class<*>? = null,
    ): Boolean {
        if (loaded) return true
        if (moduleApkPath.isBlank() || appDataDir.isBlank()) return false
        HostShadowhookDetector.ensureProbed()
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
                loadFromApk(moduleApkPath, File(appDataDir, EXTRACT_DIR), libs, loader, callerClass)
            } catch (e: Throwable) {
                loaded = false
                XposedBridge.log("${XposedConstants.TAG}: native load failed: ${e.message}")
                false
            }
        }
    }

    private fun purgeStaleExtract(appDataDir: String) {
        listOf("cache/yumyhook_native", EXTRACT_DIR).forEach { rel ->
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
        callerClass: Class<*>?,
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
        loadNativeLibsInHostNamespace(moduleApkPath, classLoader, destDir, libs)
        NativeJniHost.bind(classLoader, moduleApkPath)
        loaded = true
        XposedBridge.log(
            "${XposedConstants.TAG}: native loaded host-ns abi=$abi libs=${libs.joinToString()} " +
                "dir=${destDir.absolutePath}",
        )
        return true
    }

    private fun loadNativeLibsInHostNamespace(
        moduleApkPath: String,
        hostClassLoader: ClassLoader,
        destDir: File,
        libFileNames: List<String>,
    ) {
        NativeJniHost.ensureModuleDexOnHost(hostClassLoader, moduleApkPath)
        val dexLoader = hostClassLoader as? BaseDexClassLoader
            ?: throw IllegalStateException(
                "host ClassLoader is not BaseDexClassLoader: ${hostClassLoader.javaClass.name}",
            )
        XposedHelpers.callMethod(dexLoader, "addNativePath", listOf(destDir.absolutePath))
        val bridgeCaller = NativeJniHost.hostClass(
            "com.yumito.yumyhook.xposed.channel.NativeJni",
            hostClassLoader,
            moduleApkPath,
        )
        for (libName in libFileNames) {
            val libCaller = if (libName.contains("shadowhook")) {
                NativeJniHost.hostClass(
                    "com.bytedance.shadowhook.ShadowHook",
                    hostClassLoader,
                    moduleApkPath,
                )
            } else {
                bridgeCaller
            }
            loadNativeInHostNamespace(
                hostClassLoader,
                File(destDir, libName).absolutePath,
                libCaller,
            )
        }
    }

    private fun loadNativeInHostNamespace(
        hostClassLoader: ClassLoader,
        absolutePath: String,
        caller: Class<*>,
    ) {
        var lastError: Throwable? = null
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                loadViaRuntimeNativeLoad(hostClassLoader, absolutePath, caller)
                return
            } catch (e: Throwable) {
                lastError = e
                XposedBridge.log(
                    "${XposedConstants.TAG}: Runtime.nativeLoad failed sdk=${Build.VERSION.SDK_INT}: ${e.message}",
                )
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                loadViaVmRuntime(hostClassLoader, absolutePath, caller)
                return
            } catch (e: Throwable) {
                lastError = e
                XposedBridge.log(
                    "${XposedConstants.TAG}: VMRuntime.nativeLoad failed sdk=${Build.VERSION.SDK_INT}: ${e.message}",
                )
            }
        }
        val runtime = Runtime.getRuntime()
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

    private fun loadViaRuntimeNativeLoad(
        hostClassLoader: ClassLoader,
        absolutePath: String,
        caller: Class<*>,
    ) {
        val method = Runtime::class.java.getDeclaredMethod(
            "nativeLoad",
            String::class.java,
            ClassLoader::class.java,
            Class::class.java,
        )
        method.isAccessible = true
        val err = method.invoke(null, absolutePath, hostClassLoader, caller) as? String
        if (!err.isNullOrBlank()) {
            throw UnsatisfiedLinkError(err)
        }
        XposedBridge.log(
            "${XposedConstants.TAG}: native load host-ns via Runtime.nativeLoad " +
                "caller=${caller.name} path=$absolutePath",
        )
    }

    private fun loadViaVmRuntime(
        hostClassLoader: ClassLoader,
        absolutePath: String,
        caller: Class<*>,
    ) {
        val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime", false, null)
        val vmRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime").invoke(null)
        val callers = buildList {
            add(caller)
            addAll(hostCallerClasses(hostClassLoader).filter { it != caller })
        }
        var lastError: Throwable? = null
        val loaderType = ClassLoader::class.java
        val classType = Class::class.java
        val nativeLoad3 = findNativeLoadMethod(vmRuntimeClass, loaderType, classType, 3)
        val nativeLoad2 = findNativeLoadMethod(vmRuntimeClass, loaderType, classType, 2)
        for (caller in callers) {
            if (nativeLoad3 != null) {
                try {
                    invokeNativeLoad(nativeLoad3, vmRuntime, absolutePath, hostClassLoader, caller)
                    logNativeLoadSuccess(3, hostClassLoader, caller, absolutePath)
                    return
                } catch (e: Throwable) {
                    lastError = e
                    logNativeLoadFailure(3, caller, e)
                }
            }
            if (nativeLoad2 != null) {
                try {
                    invokeNativeLoad(nativeLoad2, vmRuntime, absolutePath, hostClassLoader, null)
                    logNativeLoadSuccess(2, hostClassLoader, caller, absolutePath)
                    return
                } catch (e: Throwable) {
                    lastError = e
                    logNativeLoadFailure(2, caller, e)
                }
            }
        }
        throw IllegalStateException(
            "cannot load native lib in host namespace: $absolutePath",
            lastError,
        )
    }

    private fun findNativeLoadMethod(
        vmRuntimeClass: Class<*>,
        loaderType: Class<*>,
        classType: Class<*>,
        argCount: Int,
    ): Method? {
        return try {
            val method = if (argCount == 3) {
                vmRuntimeClass.getDeclaredMethod("nativeLoad", String::class.java, loaderType, classType)
            } else {
                vmRuntimeClass.getDeclaredMethod("nativeLoad", String::class.java, loaderType)
            }
            method.isAccessible = true
            method
        } catch (_: Throwable) {
            null
        }
    }

    private fun invokeNativeLoad(
        method: Method,
        vmRuntime: Any,
        absolutePath: String,
        hostClassLoader: ClassLoader,
        caller: Class<*>?,
    ) {
        val err = if (caller != null && method.parameterCount == 3) {
            method.invoke(vmRuntime, absolutePath, hostClassLoader, caller) as? String
        } else {
            method.invoke(vmRuntime, absolutePath, hostClassLoader) as? String
        }
        if (!err.isNullOrBlank()) {
            throw UnsatisfiedLinkError(err)
        }
    }

    private fun logNativeLoadSuccess(
        argCount: Int,
        hostClassLoader: ClassLoader,
        caller: Class<*>,
        absolutePath: String,
    ) {
        XposedBridge.log(
            "${XposedConstants.TAG}: native load host-ns via VMRuntime.nativeLoad " +
                "args=$argCount cl=${hostClassLoader.javaClass.name} caller=${caller.name} path=$absolutePath",
        )
    }

    private fun logNativeLoadFailure(argCount: Int, caller: Class<*>, e: Throwable) {
        XposedBridge.log(
            "${XposedConstants.TAG}: VMRuntime.nativeLoad(${argCount}arg,caller=${caller.name}) failed: ${e.message}",
        )
    }

    /** 优先用宿主 APK 内的类作 caller，避免模块 Class 影响 linker 解析。 */
    private fun hostCallerClasses(hostClassLoader: ClassLoader): List<Class<*>> {
        val callers = mutableListOf<Class<*>>()
        for (name in arrayOf(
            "android.app.Application",
            "android.app.Activity",
            "android.content.Context",
            "android.view.View",
        )) {
            try {
                @Suppress("UNCHECKED_CAST")
                callers += hostClassLoader.loadClass(name) as Class<*>
            } catch (_: Throwable) {
            }
        }
        if (callers.isEmpty()) {
            callers += Class.forName("android.app.ActivityThread", false, null)
        }
        return callers
    }

    private fun preferredAbi(): String {
        val abis = Build.SUPPORTED_ABIS ?: emptyArray()
        if (abis.contains("arm64-v8a")) return "arm64-v8a"
        if (abis.contains("armeabi-v7a")) return "armeabi-v7a"
        return abis.firstOrNull() ?: "arm64-v8a"
    }
}
