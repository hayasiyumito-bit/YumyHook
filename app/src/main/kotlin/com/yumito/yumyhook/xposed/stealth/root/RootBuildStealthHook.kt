package com.yumito.yumyhook.xposed.stealth.root

import android.os.Build
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** Build.TAGS / TYPE 等 Java 层 Root 指纹。 */
object RootBuildStealthHook {

    fun install() {
        patchField("TAGS", "release-keys")
        patchField("TYPE", "user")
    }

    private fun patchField(field: String, value: String) {
        try {
            XposedHelpers.setStaticObjectField(Build::class.java, field, value)
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: RootBuild.$field skip: ${e.message}")
        }
    }
}
