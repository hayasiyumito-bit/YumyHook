package com.yumito.yumyhook.xposed.stealth.bluetooth
import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.config.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** BluetoothAdapter 地址 / 配对列表 */
object BluetoothStealthHook {
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.bluetooth.BluetoothAdapter",
                lpparam.classLoader,
                "getAddress",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val values = HookConfig.refreshHookCacheIfStale()
                        param.result = values.idsFields["mac"] ?: "02:00:00:00:00:00"
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                "android.bluetooth.BluetoothAdapter",
                lpparam.classLoader,
                "getBondedDevices",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = emptySet<Any>()
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: BluetoothStealth skip: ${e.message}")
        }
    }
}
