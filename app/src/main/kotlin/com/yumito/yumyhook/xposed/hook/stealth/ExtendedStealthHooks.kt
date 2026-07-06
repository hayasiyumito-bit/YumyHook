package com.yumito.yumyhook.xposed.hook.stealth

import android.content.pm.InstallSourceInfo
import android.net.NetworkCapabilities
import android.os.Build
import com.yumito.yumyhook.xposed.HookConfig
import com.yumito.yumyhook.xposed.XposedConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** HackChecker.isVPN — 隐藏 VPN 传输层 */
object VpnStealthHook {
    fun install() {
        try {
            XposedHelpers.findAndHookMethod(
                NetworkCapabilities::class.java,
                "hasTransport",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val transport = param.args[0] as Int
                        if (transport == NetworkCapabilities.TRANSPORT_VPN) {
                            param.result = false
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: VpnStealth skip: ${e.message}")
        }
    }
}

/** PackageInfo / AppsflyerInfo 安装来源 → Google Play */
object InstallSourceStealthHook {
    private const val PLAY_STORE = "com.android.vending"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                XposedHelpers.findAndHookMethod(
                    "android.app.ApplicationPackageManager",
                    lpparam.classLoader,
                    "getInstallSourceInfo",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val info = param.result as? InstallSourceInfo ?: return
                            try {
                                XposedHelpers.setObjectField(info, "mInitiatingPackageName", PLAY_STORE)
                                XposedHelpers.setObjectField(info, "mInstallingPackageName", PLAY_STORE)
                                XposedHelpers.setObjectField(info, "mOriginatingPackageName", PLAY_STORE)
                            } catch (_: Throwable) {
                            }
                        }
                    },
                )
            } catch (e: Throwable) {
                XposedBridge.log("${XposedConstants.TAG}: InstallSourceInfo skip: ${e.message}")
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                lpparam.classLoader,
                "getInstallerPackageName",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = PLAY_STORE
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: getInstallerPackageName skip: ${e.message}")
        }
    }
}

/** Settings.Global 飞行模式 */
object AirplaneModeStealthHook {
    private const val AIRPLANE = "airplane_mode_on"

    fun install() {
        hookGetInt("android.provider.Settings\$Global", AIRPLANE)
        hookGetInt("android.provider.Settings\$System", AIRPLANE)
    }

    private fun hookGetInt(className: String, target: String) {
        try {
            XposedHelpers.findAndHookMethod(
                className,
                null,
                "getInt",
                android.content.ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args[1] == target) param.result = 0
                    }
                },
            )
        } catch (_: Throwable) {
        }
    }
}

/** System.getProperty 代理 */
object ProxyStealthHook {
    fun install() {
        try {
            XposedHelpers.findAndHookMethod(
                System::class.java,
                "getProperty",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        if (key.contains("proxy", ignoreCase = true)) {
                            param.result = null
                        }
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                System::class.java,
                "getProperty",
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        if (key.contains("proxy", ignoreCase = true)) {
                            param.result = param.args[1]
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: ProxyStealth skip: ${e.message}")
        }
    }
}

/** Net.java Wi-Fi 扫描 / 连接信息 */
object WifiStealthHook {
    fun install(lpparam: XC_LoadPackage.LoadPackageParam, hideNetworks: Boolean, spoofInfo: Boolean) {
        if (hideNetworks) {
            try {
                XposedHelpers.findAndHookMethod(
                    "android.net.wifi.WifiManager",
                    lpparam.classLoader,
                    "getScanResults",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = emptyList<Any>()
                        }
                    },
                )
            } catch (e: Throwable) {
                XposedBridge.log("${XposedConstants.TAG}: getScanResults skip: ${e.message}")
            }
        }
        if (spoofInfo) {
            try {
                XposedHelpers.findAndHookMethod(
                    "android.net.wifi.WifiManager",
                    lpparam.classLoader,
                    "getConnectionInfo",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val info = param.result ?: return
                            try {
                                XposedHelpers.setObjectField(info, "mSSID", "AndroidWifi")
                                XposedHelpers.setObjectField(info, "mBSSID", "02:00:00:00:00:00")
                            } catch (_: Throwable) {
                            }
                        }
                    },
                )
            } catch (e: Throwable) {
                XposedBridge.log("${XposedConstants.TAG}: getConnectionInfo skip: ${e.message}")
            }
        }
    }
}

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
                        param.result = "02:00:00:00:00:00"
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

/** IDs / SimCard — TelephonyManager */
object TelephonyStealthHook {
    fun install(lpparam: XC_LoadPackage.LoadPackageParam, fullId: Boolean, simSim: Boolean) {
        if (!fullId && !simSim) return
        val values = HookConfig.valuesForHook()
        val hooks = mapOf(
            "getSimOperator" to values.idsFields["simOperator"],
            "getSimOperatorName" to values.idsFields["simOperatorName"],
            "getSimCountryIso" to values.idsFields["simCountryIso"],
            "getSubscriberId" to values.idsFields["imsi"],
            "getLine1Number" to values.idsFields["phoneNo"],
            "getDeviceId" to values.idsFields["imei"],
        )
        for ((method, spoof) in hooks) {
            if (spoof.isNullOrBlank()) continue
            try {
                XposedHelpers.findAndHookMethod(
                    "android.telephony.TelephonyManager",
                    lpparam.classLoader,
                    method,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = spoof
                        }
                    },
                )
            } catch (_: Throwable) {
            }
        }
        if (fullId) {
            val androidId = values.idsFields["androidId"]
            if (!androidId.isNullOrBlank()) {
                try {
                    XposedHelpers.findAndHookMethod(
                        "android.provider.Settings\$Secure",
                        null,
                        "getString",
                        android.content.ContentResolver::class.java,
                        String::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (param.args[1] == "android_id") {
                                    param.result = androidId
                                }
                            }
                        },
                    )
                } catch (_: Throwable) {
                }
            }
        }
    }
}

/** Net.java NetworkInterface 枚举 — 减少局域网可见网卡 */
object LanScanStealthHook {
    fun install() {
        try {
            XposedHelpers.findAndHookMethod(
                "java.net.NetworkInterface",
                null,
                "getNetworkInterfaces",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val en = param.result as? java.util.Enumeration<*> ?: return
                        val kept = mutableListOf<java.net.NetworkInterface>()
                        while (en.hasMoreElements()) {
                            val ni = en.nextElement() as? java.net.NetworkInterface ?: continue
                            val name = ni.name ?: ""
                            if (name.startsWith("lo") || name.startsWith("wlan") || name.startsWith("rmnet")) {
                                kept.add(ni)
                            }
                        }
                        param.result = java.util.Collections.enumeration(kept)
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: LanScanStealth skip: ${e.message}")
        }
    }
}

/** 实验性运行时间伪装 */
object UptimeStealthHook {
    private const val OFFSET_MS = 86_400_000L * 3

    fun install() {
        val offsetHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val orig = param.result as? Long ?: return
                param.result = orig + OFFSET_MS
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                android.os.SystemClock::class.java,
                "elapsedRealtime",
                offsetHook,
            )
            XposedHelpers.findAndHookMethod(
                android.os.SystemClock::class.java,
                "uptimeMillis",
                offsetHook,
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: UptimeStealth skip: ${e.message}")
        }
    }
}

/** 伪装应用身份：去除目标 App 自身 DEBUGGABLE 标记 */
object AppIdentityStealthHook {
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clearDebug = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val pkg = param.args[0] as? String ?: return
                if (pkg != lpparam.packageName) return
                when (val result = param.result) {
                    is android.content.pm.ApplicationInfo ->
                        result.flags = result.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE.inv()
                    is android.content.pm.PackageInfo -> {
                        val appInfo = result.applicationInfo ?: return
                        appInfo.flags = appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE.inv()
                    }
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                lpparam.classLoader,
                "getApplicationInfo",
                String::class.java,
                Int::class.javaPrimitiveType,
                clearDebug,
            )
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                lpparam.classLoader,
                "getPackageInfo",
                String::class.java,
                Int::class.javaPrimitiveType,
                clearDebug,
            )
        } catch (e: Throwable) {
            XposedBridge.log("${XposedConstants.TAG}: AppIdentityStealth skip: ${e.message}")
        }
    }
}

/** Settings.Global development_settings_enabled */
object DeveloperOptionsStealthHook {
    fun install() {
        val keys = setOf("development_settings_enabled", "adb_enabled")
        try {
            XposedHelpers.findAndHookMethod(
                "android.provider.Settings\$Global",
                null,
                "getInt",
                android.content.ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val name = param.args[1] as? String ?: return
                        if (name in keys) param.result = 0
                    }
                },
            )
        } catch (_: Throwable) {
        }
    }
}
