package com.yumito.yumyhook.xposed.stealth.location
import android.location.Location
import android.os.SystemClock
import com.yumito.yumyhook.xposed.config.HookConfig
import com.yumito.yumyhook.xposed.config.HookFeatureConfig

data class ResolvedSpoofLocation(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val placeName: String,
)

/** 从配置解析当前应注入的地理位置（全局开关，不按包名分支）。 */
object LocationSpoofRuntime {

    private val SYSTEM_PROVIDERS = setOf(
        "gps", "network", "passive", "fused", "hybrid", "lbs", "nlp",
    )

    fun resolve(): ResolvedSpoofLocation? {
        if (!HookFeatureConfig.current().spoofLocation) return null
        val fields = HookConfig.refreshHookCacheIfStale().locationFields
        val preset = com.yumito.yumyhook.xposed.channel.LocationSpoofGenerator.fieldsOrDefault(fields)
        val lat = preset["latitude"]?.toDoubleOrNull() ?: return null
        val lng = preset["longitude"]?.toDoubleOrNull() ?: return null
        val altitude = preset["altitude"]?.toDoubleOrNull() ?: 35.0
        val accuracy = preset["accuracy"]?.toFloatOrNull() ?: 10f
        val placeName = preset["placeName"].orEmpty()
        return ResolvedSpoofLocation(lat, lng, altitude, accuracy, placeName)
    }

    fun isSystemProvider(provider: String?): Boolean {
        if (provider.isNullOrBlank()) return true
        return provider.lowercase() in SYSTEM_PROVIDERS
    }

    fun buildLocation(provider: String): Location {
        val spoof = resolve() ?: throw IllegalStateException("spoof location inactive")
        val p = provider.ifBlank { "gps" }
        return Location(p).apply {
            latitude = spoof.latitude
            longitude = spoof.longitude
            altitude = spoof.altitude
            accuracy = spoof.accuracy
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                bearingAccuracyDegrees = 0f
                verticalAccuracyMeters = 1f
                speedAccuracyMetersPerSecond = 0f
            }
        }
    }

    fun patchLocation(location: Location) {
        val spoof = resolve() ?: return
        if (!isSystemProvider(location.provider)) return
        location.latitude = spoof.latitude
        location.longitude = spoof.longitude
        location.altitude = spoof.altitude
        location.accuracy = spoof.accuracy
        location.time = System.currentTimeMillis()
        location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }
}
