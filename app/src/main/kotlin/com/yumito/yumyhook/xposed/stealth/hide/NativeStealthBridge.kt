package com.yumito.yumyhook.xposed.stealth.hide

import com.yumito.yumyhook.xposed.channel.HostShadowhookDetector
import com.yumito.yumyhook.xposed.channel.NativeLibLoader
import com.yumito.yumyhook.xposed.config.HookFeatureConfig
import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.runtime.ModulePathHolder
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** Native 层 proc / path / linker 反检测（依赖 libyumyhook_native）。 */
object NativeStealthBridge {

    @Volatile
    private var procHooksInstalled = false

    @Volatile
    private var libLoaded = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        return install(
            packageName = lpparam.packageName,
            dataDir = lpparam.appInfo.dataDir,
            classLoader = lpparam.classLoader,
        )
    }

    fun install(packageName: String, dataDir: String?, classLoader: ClassLoader?): Boolean {
        if (!HookFeatureConfig.current().let { it.hideLsposed || it.hideRoot }) return false
        if (procHooksInstalled) return true
        synchronized(this) {
            if (procHooksInstalled) return true
            if (!ensureLibLoaded(packageName, dataDir, classLoader)) return false
            return try {
                if (nativeInstallProcStealth(dataDir!!)) {
                    procHooksInstalled = true
                    XposedBridge.log(
                        "${XposedConstants.NATIVE_STEALTH_TAG}: proc stealth installed pkg=$packageName",
                    )
                    true
                } else {
                    XposedBridge.log(
                        "${XposedConstants.NATIVE_STEALTH_TAG}: proc stealth defer pkg=$packageName",
                    )
                    false
                }
            } catch (e: Throwable) {
                XposedBridge.log("${XposedConstants.NATIVE_STEALTH_TAG}: proc stealth failed: ${e.message}")
                false
            }
        }
    }

    /** Native 属性 Hook 就绪后重试 proc stealth（shadowhook 引擎已 init）。 */
    fun retryAfterNativeEngine(packageName: String, dataDir: String?, classLoader: ClassLoader?) {
        if (procHooksInstalled) return
        install(packageName, dataDir, classLoader)
    }

    fun isInstalled(): Boolean = procHooksInstalled

    private fun ensureLibLoaded(
        packageName: String,
        dataDir: String?,
        classLoader: ClassLoader?,
    ): Boolean {
        if (libLoaded) return true
        val apk = ModulePathHolder.moduleApkPath
        if (apk.isBlank() || dataDir.isNullOrBlank()) {
            XposedBridge.log("${XposedConstants.TAG}: native stealth skip empty path")
            return false
        }
        val hostShadowhook = HostShadowhookDetector.isHostPresent()
        libLoaded = NativeLibLoader.ensureLoaded(
            apk,
            dataDir,
            packageName,
            classLoader,
            reuseHostShadowhook = hostShadowhook,
        )
        if (!libLoaded) {
            XposedBridge.log(
                "${XposedConstants.TAG}: native stealth lib defer pkg=$packageName host=$hostShadowhook",
            )
        }
        return libLoaded
    }

    @JvmStatic
    private external fun nativeInstallProcStealth(cacheDir: String): Boolean
}
