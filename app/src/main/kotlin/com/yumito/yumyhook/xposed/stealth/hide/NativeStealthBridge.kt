package com.yumito.yumyhook.xposed.stealth.hide

import com.yumito.yumyhook.xposed.channel.HostShadowhookDetector
import com.yumito.yumyhook.xposed.channel.NativeLibLoader
import com.yumito.yumyhook.xposed.config.HookFeatureConfig
import com.yumito.yumyhook.xposed.config.XposedConstants
import com.yumito.yumyhook.xposed.runtime.ModulePathHolder
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** Native 层 proc / dlsym / dl_iterate_phdr 反检测（依赖 libyumyhook_native）。 */
object NativeStealthBridge {

    @Volatile
    private var installed = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        return install(
            packageName = lpparam.packageName,
            dataDir = lpparam.appInfo.dataDir,
            classLoader = lpparam.classLoader,
        )
    }

    fun install(packageName: String, dataDir: String?, classLoader: ClassLoader?): Boolean {
        if (!HookFeatureConfig.refreshIfStale().hideLsposed) return false
        if (installed) return true
        synchronized(this) {
            if (installed) return true
            val apk = ModulePathHolder.moduleApkPath
            if (apk.isBlank() || dataDir.isNullOrBlank()) {
                XposedBridge.log("${XposedConstants.TAG}: native stealth skip empty path")
                return false
            }
            val hostShadowhook = HostShadowhookDetector.isHostPresent()
            val loaded = NativeLibLoader.ensureLoaded(
                apk,
                dataDir,
                packageName,
                classLoader,
                reuseHostShadowhook = hostShadowhook,
            )
            if (!loaded) {
                XposedBridge.log(
                    "${XposedConstants.TAG}: native stealth defer load pkg=$packageName host=$hostShadowhook",
                )
                return false
            }
            return try {
                if (nativeInstallProcStealth(dataDir)) {
                    installed = true
                    XposedBridge.log(
                        "${XposedConstants.TAG}: native proc stealth installed pkg=$packageName",
                    )
                    true
                } else {
                    false
                }
            } catch (e: Throwable) {
                XposedBridge.log("${XposedConstants.TAG}: native proc stealth failed: ${e.message}")
                false
            }
        }
    }

    fun isInstalled(): Boolean = installed

    @JvmStatic
    private external fun nativeInstallProcStealth(cacheDir: String): Boolean
}
