package com.yumito.yumyhook.xposed.stealth.location
import android.location.Location
import android.location.LocationListener
import android.os.Handler
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.yumito.yumyhook.xposed.config.XposedConstants
import java.util.concurrent.Executor

/**
 * 系统层 Location API 伪装：LocationManager 返回值 + Location 坐标读取。
 * 对作用域内任意 App 生效，不按包名特化。
 */
object LocationStealthHook {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        hookLocationGetters()
        hookGetLastKnownLocation(cl)
        hookGetCurrentLocation(cl)
        hookRequestLocationUpdates(cl)
        FusedLocationStealthHook.install(lpparam)
        XposedBridge.log("${XposedConstants.TAG}: location spoof installed pkg=${lpparam.packageName}")
    }

    private fun hookLocationGetters() {
        val coords = listOf(
            "getLatitude" to { s: ResolvedSpoofLocation -> s.latitude },
            "getLongitude" to { s: ResolvedSpoofLocation -> s.longitude },
            "getAltitude" to { s: ResolvedSpoofLocation -> s.altitude },
            "getAccuracy" to { s: ResolvedSpoofLocation -> s.accuracy },
        )
        for ((method, value) in coords) {
            try {
                XposedHelpers.findAndHookMethod(
                    Location::class.java,
                    method,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val loc = param.thisObject as? Location ?: return
                            val spoof = LocationSpoofRuntime.resolve() ?: return
                            if (!LocationSpoofRuntime.isSystemProvider(loc.provider)) return
                            param.result = value(spoof)
                        }
                    },
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun hookGetLastKnownLocation(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.location.LocationManager",
                classLoader,
                "getLastKnownLocation",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (LocationSpoofRuntime.resolve() == null) return
                        val provider = param.args[0] as? String ?: "gps"
                        val current = param.result as? Location
                        if (current != null) {
                            LocationSpoofRuntime.patchLocation(current)
                        } else {
                            param.result = LocationSpoofRuntime.buildLocation(provider)
                        }
                    }
                },
            )
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                "android.location.LocationManager",
                classLoader,
                "getLastKnownLocation",
                String::class.java,
                XposedHelpers.findClass("android.location.LocationRequest", classLoader),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (LocationSpoofRuntime.resolve() == null) return
                        val provider = param.args[0] as? String ?: "gps"
                        val current = param.result as? Location
                        if (current != null) {
                            LocationSpoofRuntime.patchLocation(current)
                        } else {
                            param.result = LocationSpoofRuntime.buildLocation(provider)
                        }
                    }
                },
            )
        } catch (_: Throwable) {
        }
    }

    private fun hookGetCurrentLocation(classLoader: ClassLoader) {
        try {
            val consumerClass = XposedHelpers.findClass("java.util.function.Consumer", classLoader)
            XposedHelpers.findAndHookMethod(
                "android.location.LocationManager",
                classLoader,
                "getCurrentLocation",
                String::class.java,
                XposedHelpers.findClass("android.location.LocationRequest", classLoader),
                XposedHelpers.findClass("android.os.CancellationSignal", classLoader),
                Executor::class.java,
                consumerClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (LocationSpoofRuntime.resolve() == null) return
                        val provider = param.args[0] as? String ?: "gps"
                        val consumer = param.args[4] ?: return
                        param.result = null
                        try {
                            consumerClass.getMethod("accept", Any::class.java)
                                .invoke(consumer, LocationSpoofRuntime.buildLocation(provider))
                        } catch (_: Throwable) {
                        }
                    }
                },
            )
        } catch (_: Throwable) {
        }
    }

    private fun hookRequestLocationUpdates(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.location.LocationManager",
                classLoader,
                "requestLocationUpdates",
                String::class.java,
                Long::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                LocationListener::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[3] as? LocationListener ?: return
                        param.args[3] = wrapListener(listener)
                    }
                },
            )
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                "android.location.LocationManager",
                classLoader,
                "requestLocationUpdates",
                String::class.java,
                Long::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                LocationListener::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[3] as? LocationListener ?: return
                        param.args[3] = wrapListener(listener)
                    }
                },
            )
        } catch (_: Throwable) {
        }
    }

    private fun wrapListener(delegate: LocationListener): LocationListener {
        return object : LocationListener {
            override fun onLocationChanged(location: Location) {
                deliver(delegate, location)
            }

            override fun onLocationChanged(locations: MutableList<Location>) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val patched = locations.map { loc ->
                        val copy = Location(loc)
                        LocationSpoofRuntime.patchLocation(copy)
                        copy
                    }.toMutableList()
                    delegate.onLocationChanged(patched)
                } else {
                    locations.forEach { deliver(delegate, it) }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
                @Suppress("DEPRECATION")
                delegate.onStatusChanged(provider, status, extras)
            }

            override fun onProviderEnabled(provider: String) {
                delegate.onProviderEnabled(provider)
            }

            override fun onProviderDisabled(provider: String) {
                delegate.onProviderDisabled(provider)
            }
        }
    }

    private fun deliver(delegate: LocationListener, location: Location) {
        val copy = Location(location)
        LocationSpoofRuntime.patchLocation(copy)
        delegate.onLocationChanged(copy)
    }
}
