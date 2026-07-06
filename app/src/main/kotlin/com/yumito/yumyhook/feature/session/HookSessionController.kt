package com.yumito.yumyhook.feature.session

import android.content.Context
import com.yumito.yumyhook.data.lsposed.LsposedScopeReader
import com.yumito.yumyhook.data.lsposed.RootShell
import com.yumito.yumyhook.data.lsposed.ScopeLabelResolver
import com.yumito.yumyhook.data.profile.HookProfilesStore
import com.yumito.yumyhook.xposed.config.XposedConstants

data class HookSessionResult(
    val enabled: Boolean,
    val message: String,
)

/** 管理 hook 开关与目标 App 重启（Root force-stop）。 */
object HookSessionController {

    fun enable(context: Context): HookSessionResult {
        setHookEnabled(context, true)
        return restartScopedTargets(context, prefix = "Hook 已开启")
    }

    /** 冷启动：Hook 已开 + 有 Root 时强停作用域内 App，无需再拨开关。 */
    fun applyOnColdStart(context: Context): HookSessionResult? {
        if (!isEnabled(context)) return null
        return restartScopedTargets(context, prefix = "Hook 已开启（启动同步）")
    }

    fun disable(context: Context, reason: String = "已关闭"): HookSessionResult {
        setHookEnabled(context, false)
        return restartScopedTargets(context, prefix = "Hook 已关闭")
    }

    fun isEnabled(context: Context): Boolean = HookProfilesStore.isHookEnabled(context)

    /** 配置变更后强停目标 App。onlyPackages=null 表示作用域内全部。 */
    fun notifyConfigChanged(
        context: Context,
        onlyPackages: Collection<String>? = null,
    ): HookSessionResult? {
        if (!isEnabled(context)) return null
        return restartScopedTargets(context, prefix = "伪装参数已更新", onlyPackages = onlyPackages)
    }

    private fun restartScopedTargets(
        context: Context,
        prefix: String,
        onlyPackages: Collection<String>? = null,
    ): HookSessionResult {
        if (!RootShell.ensureRoot()) {
            return HookSessionResult(
                enabled = isEnabled(context),
                message = "$prefix。无 Root 权限，请手动重启目标 App 使配置生效",
            )
        }

        val packages = LsposedScopeReader.readScopedPackages(context)
            .filter { it != XposedConstants.MODULE_PACKAGE }
            .let { scoped ->
                if (onlyPackages == null) scoped
                else scoped.filter { it in onlyPackages }
            }
        if (packages.isEmpty()) {
            return HookSessionResult(
                enabled = isEnabled(context),
                message = "$prefix。未读取到 LSPosed 作用域，已跳过强停；请手动重启目标 App",
            )
        }

        val stopped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (pkg in packages) {
            if (RootShell.forceStop(pkg)) {
                stopped.add(shortLabel(context, pkg))
            } else {
                failed.add(shortLabel(context, pkg))
            }
        }
        val msg = buildString {
            append(prefix)
            append("，已强行停止 ${stopped.size} 个 App")
            if (stopped.isNotEmpty()) append("：${stopped.joinToString()}")
            if (failed.isNotEmpty()) append("；失败：${failed.joinToString()}")
            append("。重新打开目标 App 后 Hook 生效")
        }
        return HookSessionResult(enabled = isEnabled(context), message = msg)
    }

    private fun setHookEnabled(context: Context, enabled: Boolean) {
        val profile = HookProfilesStore.load(context)
        HookProfilesStore.save(context, profile.copy(hookEnabled = enabled))
    }

    private fun shortLabel(context: Context, packageName: String): String {
        return ScopeLabelResolver.label(context, packageName)
    }
}
