package com.yumito.yumyhook.xposed

import android.content.Context

/** 缓存目标 App Application，供无 Context 参数的 Hook 读取模块配置。 */
object TargetContextHolder {

    @Volatile
    var appContext: Context? = null

    fun bind(context: Context?) {
        if (context != null) {
            appContext = context.applicationContext
            packageName = context.packageName
        }
    }

    @Volatile
    var packageName: String? = null
}
