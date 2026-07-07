package com.yumito.yumyhook.xposed.channel

import com.yumito.yumyhook.xposed.stealth.hide.NativeStealthBridge
import com.yumito.yumyhook.xposed.channel.strategy.profiles.ShadowhookKnownApps
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XC_MethodHook

/**
 * 宿主 shadowhook App：在 bindApplication 结束后读 maps 装 Native；
 * 不 hook System.load / Runtime.load0，避免干扰 libwechatcrash JNI_OnLoad。
 */
object HostShadowhookLoadGuard {

    @Volatile
    private var postBindScheduled = false

    @Volatile
    private var boundPkg = ""

    @Volatile
    private var boundDataDir = ""

    @Volatile
    private var boundClassLoader: ClassLoader? = null

    fun bind(packageName: String, dataDir: String, classLoader: ClassLoader) {
        boundPkg = packageName
        boundDataDir = dataDir
        boundClassLoader = classLoader
    }

    /** handleBindApplication 结束后尝试装 Native（宿主 crash 库应已映射）。 */
    fun schedulePostBindInstall() {
        if (postBindScheduled) return
        synchronized(this) {
            if (postBindScheduled) return
            hookBindApplicationEnd()
            postBindScheduled = true
            XposedBridge.log("${XposedConstants.TAG}: host shadowhook post-bind watcher installed")
        }
    }

    /** onCreate 等阶段再试一次（部分宿主晚于 bind 才映射 crash 库）。 */
    fun tryInstallFromMaps(stage: String): Boolean {
        val pkg = boundPkg
        val dataDir = boundDataDir
        val cl = boundClassLoader ?: return false
        if (pkg.isBlank() || dataDir.isBlank()) return false
        if (!ShadowhookKnownApps.isKnown(pkg)) return false
        if (NativeBridge.isHooksInstalled()) {
            NativeStealthBridge.install(pkg, dataDir, cl)
            return true
        }
        if (!HostShadowhookDetector.isHostNativeReady()) {
            XposedBridge.log("${XposedConstants.TAG}: native $stage wait host lib pkg=$pkg")
            return false
        }
        val ok = NativeBridge.installAfterHostLibrary(dataDir, pkg, cl)
        XposedBridge.log("${XposedConstants.TAG}: native $stage after maps=$ok pkg=$pkg")
        NativeStealthBridge.install(pkg, dataDir, cl)
        return ok
    }

    private fun hookBindApplicationEnd() {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ActivityThread",
                null,
                "handleBindApplication",
                "android.app.ActivityThread\$AppBindData",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        tryInstallFromMaps("post-bind")
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: post-bind watcher skip: ${e.message}")
        }
    }
}
