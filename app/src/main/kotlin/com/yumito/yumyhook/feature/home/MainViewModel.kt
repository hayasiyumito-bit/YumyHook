package com.yumito.yumyhook.feature.home

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.yumito.yumyhook.R
import com.yumito.yumyhook.model.SpoofUiState
import com.yumito.yumyhook.model.XposedStatus
import com.yumito.yumyhook.data.publish.HookConfigPublisher
import com.yumito.yumyhook.data.profile.HookProfilesStore
import com.yumito.yumyhook.feature.session.HookSessionController
import com.yumito.yumyhook.data.lsposed.XposedStatusChecker
import java.util.concurrent.Executors

/** 主页 ViewModel：状态检测与 Hook 会话管理。 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _status = MutableLiveData<XposedStatus>()
    val status: LiveData<XposedStatus> = _status
    private val _spoofState = MutableLiveData<SpoofUiState>()
    val spoofState: LiveData<SpoofUiState> = _spoofState
    private val _hookEnabled = MutableLiveData<Boolean>()
    val hookEnabled: LiveData<Boolean> = _hookEnabled
    private val _sessionMessage = MutableLiveData<String?>()
    val sessionMessage: LiveData<String?> = _sessionMessage
    private val _hookBusy = MutableLiveData(false)
    val hookBusy: LiveData<Boolean> = _hookBusy
    private val _frameworkHideRoot = MutableLiveData(true)
    val frameworkHideRoot: LiveData<Boolean> = _frameworkHideRoot
    private val _frameworkHideMagisk = MutableLiveData(true)
    val frameworkHideMagisk: LiveData<Boolean> = _frameworkHideMagisk

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var coldStartDone = false

    fun refreshOnOpen() = refresh(useRoot = true, startup = true)
    fun refresh(useRoot: Boolean = false) = refresh(useRoot, startup = false)

    private fun refresh(useRoot: Boolean, startup: Boolean) {
        val app = getApplication<Application>()
        HookProfilesStore.ensureDefaults(app)
        val spoof = HookProfilesStore.loadSpoofValues(app)
        val features = HookProfilesStore.loadFeatures(app)
        _hookEnabled.value = HookSessionController.isEnabled(app)
        _frameworkHideRoot.value = features.frameworkHideRoot
        _frameworkHideMagisk.value = features.frameworkHideMagisk
        _spoofState.value = SpoofUiState(features.configName, spoof.buildSummary(), spoof.fullParametersSummary(features))
        
        worker.execute {
            val status = XposedStatusChecker.check(app, useRoot)
            val res = if (startup && !coldStartDone) { coldStartDone = true; HookSessionController.applyOnColdStart(app) } else null
            mainHandler.post { 
                _status.value = status
                res?.message?.let { _sessionMessage.value = it }
            }
        }
    }

    fun refreshSpoofOnly() {
        val app = getApplication<Application>()
        val spoof = HookProfilesStore.loadSpoofValues(app)
        val features = HookProfilesStore.loadFeatures(app)
        _hookEnabled.value = HookSessionController.isEnabled(app)
        _frameworkHideRoot.value = features.frameworkHideRoot
        _frameworkHideMagisk.value = features.frameworkHideMagisk
        _spoofState.value = SpoofUiState(features.configName, spoof.buildSummary(), spoof.fullParametersSummary(features))
    }

    fun setHookEnabled(enabled: Boolean) {
        if (_hookBusy.value == true) return
        val app = getApplication<Application>()
        if (!enabled) { updateSession(HookSessionController.disable(app)); return }
        _hookBusy.value = true
        worker.execute { val r = HookSessionController.enable(app); mainHandler.post { _hookBusy.value = false; updateSession(r) } }
    }

    fun setFrameworkHideRoot(v: Boolean) = updateStealth("frameworkHideRoot", v)
    fun setFrameworkHideMagisk(v: Boolean) = updateStealth("frameworkHideMagisk", v)

    private fun updateStealth(key: String, v: Boolean) {
        val app = getApplication<Application>()
        if (!HookProfilesStore.setFeature(app, key, v)) return
        val f = HookProfilesStore.loadFeatures(app)
        _frameworkHideRoot.value = f.frameworkHideRoot
        _frameworkHideMagisk.value = f.frameworkHideMagisk
        _sessionMessage.value = app.getString(if (key == "frameworkHideRoot") (if (v) R.string.framework_root_enabled_toast else R.string.framework_root_disabled_toast) else (if (v) R.string.framework_magisk_enabled_toast else R.string.framework_magisk_disabled_toast))
    }

    fun consumeSessionMessage() { _sessionMessage.value = null }

    private fun updateSession(r: com.yumito.yumyhook.feature.session.HookSessionResult) {
        _hookEnabled.value = r.enabled
        _sessionMessage.value = r.message
        refreshSpoofOnly()
    }

    fun randomizeSpoof() {
        val app = getApplication<Application>()
        _hookBusy.value = true
        worker.execute {
            val summary = HookProfilesStore.randomizeSpoof(app)
            HookConfigPublisher.publish(app)
            val res = HookSessionController.notifyConfigChanged(app)
            val spoof = HookProfilesStore.loadSpoofValues(app)
            val f = HookProfilesStore.loadFeatures(app)
            mainHandler.post {
                _hookBusy.value = false
                _spoofState.value = SpoofUiState(f.configName, spoof.buildSummary(), summary)
                res?.message?.let { _sessionMessage.value = it }
            }
        }
    }
}
