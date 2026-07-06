package com.yumito.yumyhook.xposed

/** initZygote 保存模块 APK 路径，供 Native 库解压加载。 */
object ModulePathHolder {

    @Volatile
    var moduleApkPath: String = ""
}
