package com.yumito.yumyhook.ui.config

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.yumito.yumyhook.model.HookFeatureItem
import com.yumito.yumyhook.model.HookFeatures
import com.yumito.yumyhook.model.StoredProfile
import com.yumito.yumyhook.data.profile.HookProfile
import com.yumito.yumyhook.data.profile.HookProfilesStore
import com.yumito.yumyhook.data.lsposed.LsposedScopeReader
import com.yumito.yumyhook.data.lsposed.ScopeLabelResolver
import com.yumito.yumyhook.xposed.channel.BuildSpoofGenerator
import com.yumito.yumyhook.xposed.config.XposedConstants

class ConfigEditViewModel(application: Application) : AndroidViewModel(application) {

    private val _tabs = MutableLiveData<List<StoredProfile>>(emptyList())
    val tabs: LiveData<List<StoredProfile>> = _tabs

    private val _activeTabIndex = MutableLiveData(0)
    val activeTabIndex: LiveData<Int> = _activeTabIndex

    private val _profile = MutableLiveData<HookProfile>()
    val profile: LiveData<HookProfile> = _profile

    private val _featureRows = MutableLiveData<List<Pair<HookFeatureItem, Boolean>>>()
    val featureRows: LiveData<List<Pair<HookFeatureItem, Boolean>>> = _featureRows

    private val _experimentalRows = MutableLiveData<List<Pair<HookFeatureItem, Boolean>>>()
    val experimentalRows: LiveData<List<Pair<HookFeatureItem, Boolean>>> = _experimentalRows

    private val _sectionStates = MutableLiveData<Map<String, Boolean>>()
    val sectionStates: LiveData<Map<String, Boolean>> = _sectionStates

    private val _saveMessage = MutableLiveData<String?>()
    val saveMessage: LiveData<String?> = _saveMessage

    private val _scopeChannelRows = MutableLiveData<List<ScopeChannelRow>>(emptyList())
    val scopeChannelRows: LiveData<List<ScopeChannelRow>> = _scopeChannelRows

    private val _scopeSectionVisible = MutableLiveData(false)
    val scopeSectionVisible: LiveData<Boolean> = _scopeSectionVisible

    private val _scopeNativeMasterEnabled = MutableLiveData(false)
    val scopeNativeMasterEnabled: LiveData<Boolean> = _scopeNativeMasterEnabled

    val simFieldKeys: List<String> = listOf(
        "simOperator", "simOperatorName", "simCountryIso",
    )

    val deviceIdFieldKeys: List<String> = listOf(
        "androidId", "serialNo", "imei", "imsi", "phoneNo",
    )

    val idsFieldKeys: List<String> = simFieldKeys + deviceIdFieldKeys

    val buildFieldKeys: List<String> = listOf(
        "MODEL", "MANUFACTURER", "BRAND", "DEVICE", "PRODUCT", "BOARD", "HARDWARE",
        "ID", "DISPLAY", "INCREMENTAL", "TYPE", "TAGS", "FINGERPRINT",
        "CPU_ABI", "SUPPORTED_ABIS", "SDK_INT", "RELEASE", "SECURITY_PATCH",
    )

    val sectionKeys: List<String> = listOf(
        "spoofPartialDeviceId",
        "simSimulation",
        "spoofFullDeviceId",
        "spoofLocation",
    )

    val locationFieldKeys: List<String> = listOf(
        "placeName", "latitude", "longitude", "altitude", "accuracy",
    )

    val locationFieldHints: Map<String, String> = mapOf(
        "placeName" to "地名",
        "latitude" to "纬度",
        "longitude" to "经度",
        "altitude" to "海拔(米)",
        "accuracy" to "精度(米)",
    )

    init {
        reloadTabs()
    }

    fun reloadTabs() {
        val doc = HookProfilesStore.loadDocument(getApplication())
        _tabs.value = doc.profiles
        val index = doc.profiles.indexOfFirst { it.id == doc.activeProfileId }.coerceAtLeast(0)
        _activeTabIndex.value = index
        applyProfile(HookProfilesStore.load(getApplication()))
    }

    fun selectTab(index: Int, buildDraft: Map<String, String>, idsDraft: Map<String, String>) {
        val tabs = _tabs.value ?: return
        if (index !in tabs.indices) return
        persistDrafts(buildDraft, idsDraft)
        HookProfilesStore.setActiveProfileId(getApplication(), tabs[index].id)
        _activeTabIndex.value = index
        applyProfile(HookProfilesStore.load(getApplication()))
    }

    fun addProfile(name: String) {
        val created = HookProfilesStore.addProfile(getApplication(), name)
        reloadTabs()
        val index = _tabs.value?.indexOfFirst { it.id == created.id } ?: 0
        _activeTabIndex.value = index
        applyProfile(HookProfilesStore.load(getApplication()))
    }

    fun deleteActiveProfile(): Boolean {
        val index = _activeTabIndex.value ?: 0
        val tabs = _tabs.value ?: return false
        val id = tabs.getOrNull(index)?.id ?: return false
        if (!HookProfilesStore.deleteProfile(getApplication(), id)) return false
        reloadTabs()
        return true
    }

    fun setFeature(key: String, enabled: Boolean): Boolean {
        if (!HookFeatures.isImplemented(key)) return false
        HookProfilesStore.setFeature(getApplication(), key, enabled)
        applyProfile(HookProfilesStore.load(getApplication()))
        return true
    }

    fun setScopeFourChannel(packageName: String, enabled: Boolean): Boolean {
        val ok = HookProfilesStore.setScopedFourChannel(getApplication(), packageName, enabled)
        if (ok) applyProfile(HookProfilesStore.load(getApplication()))
        return ok
    }

    fun setScopeNative(packageName: String, enabled: Boolean): Boolean {
        val ok = HookProfilesStore.setScopedNative(getApplication(), packageName, enabled)
        if (ok) applyProfile(HookProfilesStore.load(getApplication()))
        return ok
    }

    fun refreshScopeChannels() {
        refreshScopeChannels(HookProfilesStore.load(getApplication()).features)
    }

    fun saveBuildFields(fields: Map<String, String>) {
        HookProfilesStore.saveBuildFields(getApplication(), fields)
        applyProfile(HookProfilesStore.load(getApplication()))
        _saveMessage.value = "设备参数已保存"
    }

    fun saveIdsFields(fields: Map<String, String>) {
        HookProfilesStore.saveIdsFields(getApplication(), fields)
        applyProfile(HookProfilesStore.load(getApplication()))
        _saveMessage.value = "标识参数已保存"
    }

    fun saveSimFields(fields: Map<String, String>) {
        val merged = HookProfilesStore.load(getApplication()).values.idsFields.toMutableMap()
        merged.putAll(fields)
        saveIdsFields(merged)
        _saveMessage.value = "SIM 参数已保存"
    }

    fun saveDeviceIdFields(fields: Map<String, String>) {
        val merged = HookProfilesStore.load(getApplication()).values.idsFields.toMutableMap()
        merged.putAll(fields)
        saveIdsFields(merged)
        _saveMessage.value = "设备标识已保存"
    }

    fun saveLocationFields(fields: Map<String, String>) {
        HookProfilesStore.saveLocationFields(getApplication(), fields)
        applyProfile(HookProfilesStore.load(getApplication()))
        _saveMessage.value = "地理位置已保存"
    }

    fun randomizeLocation() {
        HookProfilesStore.randomizeLocation(getApplication())
        applyProfile(HookProfilesStore.load(getApplication()))
        _saveMessage.value = "已随机生成地理位置"
    }

    fun randomizeAll() {
        val result = BuildSpoofGenerator.randomize()
        val current = HookProfilesStore.load(getApplication())
        val updated = current.copy(values = result.values)
        HookProfilesStore.save(getApplication(), updated)
        applyProfile(updated)
        _saveMessage.value = "已随机生成设备参数"
    }

    fun consumeSaveMessage() {
        _saveMessage.value = null
    }

    private fun persistDrafts(buildDraft: Map<String, String>, idsDraft: Map<String, String>) {
        HookProfilesStore.saveBuildFields(getApplication(), buildDraft)
        HookProfilesStore.saveIdsFields(getApplication(), idsDraft)
    }

    private fun applyProfile(profile: HookProfile) {
        _profile.value = profile
        _featureRows.value = HookFeatures.privacyCatalog().map { it to profile.features.isEnabled(it.key) }
        _experimentalRows.value = HookFeatures.experimentalCatalog().map { it to profile.features.isEnabled(it.key) }
        _sectionStates.value = sectionKeys.associateWith { profile.features.isEnabled(it) }
        _scopeSectionVisible.value = profile.features.spoofBuildProperties
        _scopeNativeMasterEnabled.value = profile.features.nativePropertyHook
        refreshScopeChannels(profile.features)
    }

    private fun refreshScopeChannels(features: HookFeatures) {
        if (!features.spoofBuildProperties) {
            _scopeChannelRows.value = emptyList()
            return
        }
        val app = getApplication<Application>()
        val packages = LsposedScopeReader.readScopedPackages(app)
            .filter { it != XposedConstants.MODULE_PACKAGE }
            .distinct()
            .sorted()
        _scopeChannelRows.value = packages.map { pkg ->
            ScopeChannelRow(
                packageName = pkg,
                label = ScopeLabelResolver.label(app, pkg),
                javaChannelEnabled = features.isJavaThreeChannelStoredFor(pkg),
                nativeChannelEnabled = features.isNativeStoredFor(pkg),
            )
        }
    }
}
