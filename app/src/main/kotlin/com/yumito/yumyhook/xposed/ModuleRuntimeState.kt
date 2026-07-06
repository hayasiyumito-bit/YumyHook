package com.yumito.yumyhook.xposed

/** 模块进程内是否已被 LSPosed 注入（仅当前进程有效，不持久化）。 */
object ModuleRuntimeState {

    @Volatile
    private var hookedThisProcess: Boolean = false

    fun markHooked() {
        hookedThisProcess = true
    }

    fun isHookedThisProcess(): Boolean = hookedThisProcess
}
