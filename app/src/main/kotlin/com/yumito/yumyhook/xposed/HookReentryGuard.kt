package com.yumito.yumyhook.xposed

/** SystemProperties / getprop Hook 防重入，避免读配置时又触发属性读取死循环。 */
object HookReentryGuard {

    private val depth = ThreadLocal<Int>()
    private val getpropBypass = ThreadLocal<Boolean>()
    private val mapsBypass = ThreadLocal<Boolean>()
    private val shellProbeBypass = ThreadLocal<Boolean>()

    fun isInside(): Boolean = (depth.get() ?: 0) > 0

    fun isGetpropBypass(): Boolean = getpropBypass.get() == true

    fun isMapsBypass(): Boolean = mapsBypass.get() == true

    fun isShellProbeBypass(): Boolean = shellProbeBypass.get() == true

    fun runGetpropBypass(block: () -> String): String {
        getpropBypass.set(true)
        return try {
            block()
        } finally {
            getpropBypass.set(false)
        }
    }

    fun <T> runMapsBypass(block: () -> T): T {
        mapsBypass.set(true)
        return try {
            block()
        } finally {
            mapsBypass.set(false)
        }
    }

    fun <T> runShellProbeBypass(block: () -> T): T {
        shellProbeBypass.set(true)
        return try {
            block()
        } finally {
            shellProbeBypass.set(false)
        }
    }

    fun enter(): Boolean {
        if ((depth.get() ?: 0) > 0) return false
        depth.set(1)
        return true
    }

    fun exit() {
        if ((depth.get() ?: 0) > 0) {
            depth.set(0)
        }
    }
}
