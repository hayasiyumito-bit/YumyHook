package com.yumito.yumyhook.ui.config

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.yumito.yumyhook.model.HookFeatureItem
import com.yumito.yumyhook.model.HookFeatures
import com.yumito.yumyhook.model.StoredProfile
import com.yumito.yumyhook.util.HookProfile
import com.yumito.yumyhook.util.HookProfileStore
import com.yumito.yumyhook.util.HookProfilesStore
import com.yumito.yumyhook.util.isEnabled
import com.yumito.yumyhook.xposed.BuildSpoofGenerator

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

    val idsFieldKeys: List<String> = listOf(
        "androidId", "serialNo", "imei", "imsi", "phoneNo",
        "simOperator", "simOperatorName", "simCountryIso",
    )

    val buildFieldKeys: List<String> = listOf(
        "MODEL", "MANUFACTURER", "BRAND", "DEVICE", "PRODUCT", "BOARD", "HARDWARE",
        "ID", "DISPLAY", "INCREMENTAL", "TYPE", "TAGS", "FINGERPRINT",
        "CPU_ABI", "SUPPORTED_ABIS", "SDK_INT", "RELEASE", "SECURITY_PATCH",
    )

    val sectionKeys: List<String> = listOf(
        "spoofPartialDeviceId",
        "simSimulation",
        "spoofFullDeviceId",
    )

    init {
        reloadTabs()
    }

    fun reloadTabs() {
        val doc = HookProfilesStore.loadDocument(getApplication())
        _tabs.value = doc.profiles
        val index = doc.profiles.indexOfFirst { it.id == doc.activeProfileId }.coerceAtLeast(0)
        _activeTabIndex.value = index
        applyProfile(HookProfileStore.load(getApplication()))
    }

    fun selectTab(index: Int, buildDraft: Map<String, String>, idsDraft: Map<String, String>) {
        val tabs = _tabs.value ?: return
        if (index !in tabs.indices) return
        persistDrafts(buildDraft, idsDraft)
        HookProfilesStore.setActiveProfileId(getApplication(), tabs[index].id)
        _activeTabIndex.value = index
        applyProfile(HookProfileStore.load(getApplication()))
    }

    fun addProfile(name: String) {
        val created = HookProfilesStore.addProfile(getApplication(), name)
        reloadTabs()
        val index = _tabs.value?.indexOfFirst { it.id == created.id } ?: 0
        _activeTabIndex.value = index
        applyProfile(HookProfileStore.load(getApplication()))
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
        HookProfileStore.setFeature(getApplication(), key, enabled)
        applyProfile(HookProfileStore.load(getApplication()))
        return true
    }

    fun saveBuildFields(fields: Map<String, String>) {
        HookProfileStore.saveBuildFields(getApplication(), fields)
        applyProfile(HookProfileStore.load(getApplication()))
        _saveMessage.value = "设备参数已保存"
    }

    fun saveIdsFields(fields: Map<String, String>) {
        HookProfileStore.saveIdsFields(getApplication(), fields)
        applyProfile(HookProfileStore.load(getApplication()))
        _saveMessage.value = "SIM 参数已保存"
    }

    fun randomizeAll() {
        val result = BuildSpoofGenerator.randomize()
        val current = HookProfileStore.load(getApplication())
        val updated = current.copy(values = result.values)
        HookProfileStore.save(getApplication(), updated)
        applyProfile(updated)
        _saveMessage.value = "已随机生成设备参数"
    }

    fun consumeSaveMessage() {
        _saveMessage.value = null
    }

    private fun persistDrafts(buildDraft: Map<String, String>, idsDraft: Map<String, String>) {
        HookProfileStore.saveBuildFields(getApplication(), buildDraft)
        HookProfileStore.saveIdsFields(getApplication(), idsDraft)
    }

    private fun applyProfile(profile: HookProfile) {
        _profile.value = profile
        _featureRows.value = HookFeatures.privacyCatalog().map { it to profile.features.isEnabled(it.key) }
        _experimentalRows.value = HookFeatures.experimentalCatalog().map { it to profile.features.isEnabled(it.key) }
        _sectionStates.value = sectionKeys.associateWith { profile.features.isEnabled(it) }
    }
}
