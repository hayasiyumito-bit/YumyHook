package com.yumito.yumyhook.xposed.channel

import com.yumito.yumyhook.xposed.config.XposedConstants
import dalvik.system.BaseDexClassLoader
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * libyumyhook_native 经 [NativeLibLoader] 载入宿主 linker namespace；模块 CL 与宿主 ns 隔离，
 * native 方法须绑定在宿主 CL 上的类。持有类 [NativeJni] 零依赖，避免 Xposed compileOnly 类校验失败。
 */
object NativeJniHost {

    private const val JNI_HOLDER = "com.yumito.yumyhook.xposed.channel.NativeJni"

    @Volatile
    private var jniClass: Class<*>? = null

    /** 把模块 dex 并入宿主 CL，使宿主能解析 NativeJni / ShadowHook。 */
    fun ensureModuleDexOnHost(hostClassLoader: ClassLoader, moduleApkPath: String) {
        try {
            hostClassLoader.loadClass(JNI_HOLDER)
            return
        } catch (_: ClassNotFoundException) {
        }
        val dexLoader = hostClassLoader as? BaseDexClassLoader
            ?: throw IllegalStateException("host ClassLoader is not BaseDexClassLoader")
        XposedHelpers.callMethod(dexLoader, "addDexPath", moduleApkPath, false)
        XposedBridge.log("${XposedConstants.TAG}: module dex merged into host CL")
    }

    fun hostClass(name: String, hostClassLoader: ClassLoader, moduleApkPath: String): Class<*> {
        ensureModuleDexOnHost(hostClassLoader, moduleApkPath)
        return hostClassLoader.loadClass(name)
    }

    /** native 库加载后绑定宿主 CL 上的 NativeJni；校验/JNI link 均在此触发。 */
    fun bind(hostClassLoader: ClassLoader, moduleApkPath: String) {
        if (jniClass != null) return
        synchronized(this) {
            if (jniClass != null) return
            val clazz = hostClass(JNI_HOLDER, hostClassLoader, moduleApkPath)
            val stats = XposedHelpers.callStaticMethod(clazz, "nativeHookStats") as String
            jniClass = clazz
            XposedBridge.log("${XposedConstants.NATIVE_PROP_TAG}: host JNI bound stats=$stats")
        }
    }

    fun isBound(): Boolean = jniClass != null

    fun nativeInstallPropertyHook(libcOnly: Boolean, cacheDir: String?): Boolean =
        XposedHelpers.callStaticMethod(require(), "nativeInstallPropertyHook", libcOnly, cacheDir) as Boolean

    fun nativeRetryDeferredHooks(): Boolean =
        XposedHelpers.callStaticMethod(require(), "nativeRetryDeferredHooks") as Boolean

    fun nativeSetSpoofActive(active: Boolean) {
        XposedHelpers.callStaticMethod(require(), "nativeSetSpoofActive", active)
    }

    fun nativeProbeProperty(name: String): String =
        XposedHelpers.callStaticMethod(require(), "nativeProbeProperty", name) as String

    fun nativeProbeLibcutilsProperty(name: String): String =
        XposedHelpers.callStaticMethod(require(), "nativeProbeLibcutilsProperty", name) as String

    fun nativeHookStats(): String =
        XposedHelpers.callStaticMethod(require(), "nativeHookStats") as String

    fun nativeUpdateProperties(keys: Array<String>, values: Array<String>) {
        XposedHelpers.callStaticMethod(require(), "nativeUpdateProperties", keys, values)
    }

    fun nativeInstallProcStealth(cacheDir: String): Boolean =
        XposedHelpers.callStaticMethod(require(), "nativeInstallProcStealth", cacheDir) as Boolean

    private fun require(): Class<*> =
        jniClass ?: throw IllegalStateException("host NativeJni not bound")
}
