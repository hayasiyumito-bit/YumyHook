package com.yumito.yumyhook.xposed.hook.stealth

import android.content.Context
import android.webkit.WebSettings
import com.yumito.yumyhook.xposed.HookConfig
import com.yumito.yumyhook.xposed.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** 系统层：WebView User-Agent 与伪装 Build 一致。 */
object WebSettingsStealthHook {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                WebSettings::class.java,
                "getDefaultUserAgent",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        HookConfig.refreshHookCacheIfStale()
                        if (!HookConfig.isEnabledForHook()) return
                        val values = HookConfig.valuesForHook()
                        val model = values.getBuildField("MODEL") ?: return
                        val release = values.getBuildField("RELEASE") ?: return
                        val buildId = values.getBuildField("ID") ?: values.getBuildField("DISPLAY") ?: return
                        param.result = buildUserAgent(model, release, buildId)
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: WebSettingsStealth skip: ${e.message}")
        }
    }

    private fun buildUserAgent(model: String, release: String, buildId: String): String {
        return "Mozilla/5.0 (Linux; Android $release; $model Build/$buildId; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
            "Chrome/149.0.7827.159 Mobile Safari/537.36"
    }
}
