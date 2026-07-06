package com.yumito.yumyhook.xposed.stealth.location
import android.location.Location
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.yumito.yumyhook.xposed.config.XposedConstants

/**
 * Google Play Services FusedLocationProviderClient 伪装。
 * 类不存在或 Hook 失败时静默跳过，避免无 GMS 的 App crash。
 */
object FusedLocationStealthHook {

  private const val FUSED_CLIENT = "com.google.android.gms.location.FusedLocationProviderClient"
  private const val LOCATION_RESULT = "com.google.android.gms.location.LocationResult"
  private const val LOCATION_AVAILABILITY = "com.google.android.gms.location.LocationAvailability"
  private const val TASKS = "com.google.android.gms.tasks.Tasks"

  fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
    val cl = lpparam.classLoader
    if (classOrNull(cl, FUSED_CLIENT) == null) return
    var hooked = 0
    hooked += hookFusedTask(cl, "getLastLocation")
    hooked += hookFusedTask(cl, "getLastLocation", "com.google.android.gms.location.LastLocationRequest")
    hooked += hookFusedTask(
      cl,
      "getCurrentLocation",
      Int::class.javaPrimitiveType!!,
      "com.google.android.gms.tasks.CancellationToken",
    )
    hooked += hookFusedTask(
      cl,
      "getCurrentLocation",
      "com.google.android.gms.location.CurrentLocationRequest",
      "com.google.android.gms.tasks.CancellationToken",
    )
    hooked += hookLocationResult(cl)
    hooked += hookLocationAvailability(cl)
    if (hooked > 0) {
      XposedBridge.log("${XposedConstants.TAG}: fused location spoof hooks=$hooked pkg=${lpparam.packageName}")
    }
  }

  private fun hookFusedTask(cl: ClassLoader, method: String, vararg paramTypeNames: Any): Int {
    val paramTypes = paramTypeNames.map { resolveParamType(cl, it) }
    if (paramTypes.any { it == null }) return 0
    return try {
      XposedHelpers.findAndHookMethod(
        FUSED_CLIENT,
        cl,
        method,
        *paramTypes.filterNotNull().toTypedArray(),
        spoofTaskHook(cl),
      )
      1
    } catch (_: Throwable) {
      0
    }
  }

  private fun hookLocationResult(cl: ClassLoader): Int {
    var n = 0
    n += hookAfterLocation(cl, LOCATION_RESULT, "getLastLocation")
    n += hookAfterLocationList(cl)
    return n
  }

  private fun hookAfterLocation(cl: ClassLoader, className: String, method: String): Int {
    return try {
      XposedHelpers.findAndHookMethod(
        className,
        cl,
        method,
        object : XC_MethodHook() {
          override fun afterHookedMethod(param: MethodHookParam) {
            if (LocationSpoofRuntime.resolve() == null) return
            val loc = param.result as? Location ?: return
            param.result = patchedCopy(loc)
          }
        },
      )
      1
    } catch (_: Throwable) {
      0
    }
  }

  private fun hookAfterLocationList(cl: ClassLoader): Int {
    return try {
      XposedHelpers.findAndHookMethod(
        LOCATION_RESULT,
        cl,
        "getLocations",
        object : XC_MethodHook() {
          override fun afterHookedMethod(param: MethodHookParam) {
            if (LocationSpoofRuntime.resolve() == null) return
            @Suppress("UNCHECKED_CAST")
            val list = param.result as? List<Location> ?: return
            param.result = list.map { patchedCopy(it) }
          }
        },
      )
      1
    } catch (_: Throwable) {
      0
    }
  }

  private fun hookLocationAvailability(cl: ClassLoader): Int {
    return try {
      XposedHelpers.findAndHookMethod(
        LOCATION_AVAILABILITY,
        cl,
        "isLocationAvailable",
        object : XC_MethodHook() {
          override fun afterHookedMethod(param: MethodHookParam) {
            if (LocationSpoofRuntime.resolve() != null) param.result = true
          }
        },
      )
      1
    } catch (_: Throwable) {
      0
    }
  }

  private fun spoofTaskHook(cl: ClassLoader) = object : XC_MethodHook() {
    override fun afterHookedMethod(param: MethodHookParam) {
      val task = buildSuccessTask(cl) ?: return
      param.result = task
    }
  }

  private fun buildSuccessTask(cl: ClassLoader): Any? {
    if (LocationSpoofRuntime.resolve() == null) return null
    return try {
      val loc = LocationSpoofRuntime.buildLocation("fused")
      val tasks = XposedHelpers.findClass(TASKS, cl)
      XposedHelpers.callStaticMethod(tasks, "forResult", loc)
    } catch (_: Throwable) {
      null
    }
  }

  private fun patchedCopy(location: Location): Location {
    val copy = Location(location)
    LocationSpoofRuntime.patchLocation(copy)
    return copy
  }

  private fun resolveParamType(cl: ClassLoader, type: Any): Class<*>? = when (type) {
    is Class<*> -> type
    is String -> classOrNull(cl, type)
    else -> null
  }

  private fun classOrNull(cl: ClassLoader, name: String): Class<*>? =
    try {
      XposedHelpers.findClass(name, cl)
    } catch (_: Throwable) {
      null
    }
}
