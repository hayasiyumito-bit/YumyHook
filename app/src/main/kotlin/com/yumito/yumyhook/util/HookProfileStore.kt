package com.yumito.yumyhook.util

import android.content.Context
import com.yumito.yumyhook.model.HookFeatures
import com.yumito.yumyhook.xposed.HookSpoofValues

data class HookProfile(
    val values: HookSpoofValues,
    val features: HookFeatures,
    val hookEnabled: Boolean = false,
)

/** 兼容旧调用，委托给 [HookProfilesStore]。 */
object HookProfileStore {

    fun load(context: Context): HookProfile = HookProfilesStore.loadHookProfile(context)

    fun save(context: Context, profile: HookProfile) {
        val doc = HookProfilesStore.loadDocument(context)
        val active = doc.activeProfile() ?: return
        val updated = active.copy(values = profile.values, features = profile.features)
        HookProfilesStore.saveDocument(context, doc.copy(hookEnabled = profile.hookEnabled, profiles = doc.profiles.map {
            if (it.id == updated.id) updated else it
        }))
    }

    fun setFeature(context: Context, key: String, enabled: Boolean): Boolean {
        if (!HookFeatures.isImplemented(key)) return false
        val profile = HookProfilesStore.activeProfile(context)
        val features = profile.features.withToggle(key, enabled).normalized()
        HookProfilesStore.updateActiveProfile(context, profile.copy(features = features))
        return true
    }

    fun saveBuildFields(context: Context, fields: Map<String, String>) {
        val profile = HookProfilesStore.activeProfile(context)
        val build = profile.values.buildFields.toMutableMap()
        fields.forEach { (k, v) ->
            if (v.isBlank()) build.remove(k) else build[k] = v
        }
        HookProfilesStore.updateActiveProfile(
            context,
            profile.copy(values = profile.values.copy(buildFields = build)),
        )
    }

    fun saveIdsFields(context: Context, fields: Map<String, String>) {
        val profile = HookProfilesStore.activeProfile(context)
        val ids = profile.values.idsFields.toMutableMap()
        fields.forEach { (k, v) ->
            if (v.isBlank()) ids.remove(k) else ids[k] = v
        }
        HookProfilesStore.updateActiveProfile(
            context,
            profile.copy(values = profile.values.copy(idsFields = ids)),
        )
    }
}

private fun HookFeatures.withToggle(key: String, enabled: Boolean): HookFeatures = when (key) {
    "hideDeveloperOptions" -> copy(hideDeveloperOptions = enabled)
    "hideVpn" -> copy(hideVpn = enabled)
    "spoofInstallSourcePlay" -> copy(spoofInstallSourcePlay = enabled)
    "spoofWifiInfo" -> copy(spoofWifiInfo = enabled)
    "spoofPartialDeviceId" -> copy(spoofPartialDeviceId = enabled)
    "spoofFullDeviceId" -> copy(spoofFullDeviceId = enabled)
    "simSimulation" -> copy(simSimulation = enabled)
    "blockLanScan" -> copy(blockLanScan = enabled)
    "hideLsposed" -> copy(hideLsposed = enabled)
    "hideRoot" -> copy(hideRoot = enabled)
    "hideAirplaneMode" -> copy(hideAirplaneMode = enabled)
    "hideProxy" -> copy(hideProxy = enabled)
    "hideWifiNetworks" -> copy(hideWifiNetworks = enabled)
    "hideBluetooth" -> copy(hideBluetooth = enabled)
    "spoofUptime" -> copy(spoofUptime = enabled)
    "spoofAppIdentity" -> copy(spoofAppIdentity = enabled)
    "spoofBrowserFingerprint" -> copy(spoofBrowserFingerprint = enabled)
    "spoofBuildProperties" -> copy(spoofBuildProperties = enabled).normalized()
    "nativePropertyHook" -> copy(spoofBuildProperties = enabled).normalized()
    "preventNativeCrash" -> copy(spoofBuildProperties = !enabled).normalized()
    else -> this
}

fun HookFeatures.isEnabled(key: String): Boolean = when (key) {
    "hideDeveloperOptions" -> hideDeveloperOptions
    "hideVpn" -> hideVpn
    "spoofInstallSourcePlay" -> spoofInstallSourcePlay
    "spoofWifiInfo" -> spoofWifiInfo
    "spoofPartialDeviceId" -> spoofPartialDeviceId
    "spoofFullDeviceId" -> spoofFullDeviceId
    "simSimulation" -> simSimulation
    "blockLanScan" -> blockLanScan
    "hideLsposed" -> hideLsposed
    "hideRoot" -> hideRoot
    "hideAirplaneMode" -> hideAirplaneMode
    "hideProxy" -> hideProxy
    "hideWifiNetworks" -> hideWifiNetworks
    "hideBluetooth" -> hideBluetooth
    "spoofUptime" -> spoofUptime
    "spoofAppIdentity" -> spoofAppIdentity
    "spoofBrowserFingerprint" -> spoofBrowserFingerprint
    "spoofBuildProperties" -> spoofBuildProperties
    "nativePropertyHook" -> spoofBuildProperties
    "preventNativeCrash" -> !spoofBuildProperties
    else -> false
}
