package com.yumito.yumyhook.ui.main

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.yumito.yumyhook.model.SpoofUiState
import com.yumito.yumyhook.model.XposedStatus
import com.yumito.yumyhook.util.HookConfigPublisher
import com.yumito.yumyhook.util.HookPrefs
import com.yumito.yumyhook.util.HookSessionController
import com.yumito.yumyhook.util.HookSessionResult
import com.yumito.yumyhook.util.XposedStatusChecker
import java.util.concurrent.Executors

/** 主页 ViewModel：状态检测、Hook 会话、伪装参数展示。 */
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

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var coldStartForceStopDone = false

    /** 冷启动：Root 深度检测 + Hook 已开则强停目标 App。 */
    fun refreshOnOpen() {
        val app = getApplication<Application>()
        HookPrefs.ensureDefaults(app)
        val spoof = HookPrefs.loadSpoofValues(app)
        val features = HookPrefs.loadFeatures(app)
        val summary = spoof.fullParametersSummary(features)
        val hookState = HookSessionController.isEnabled(app)
        worker.execute {
            val status = XposedStatusChecker.check(app, useRoot = true)
            val startupResult = if (!coldStartForceStopDone) {
                coldStartForceStopDone = true
                HookSessionController.applyOnColdStart(app)
            } else {
                null
            }
            mainHandler.post {
                _hookEnabled.value = hookState
                _status.value = status
                _spoofState.value = SpoofUiState(
                    profileLabel = features.configName,
                    buildSummary = spoof.buildSummary(),
                    currentFields = summary,
                )
                startupResult?.message?.let { _sessionMessage.value = it }
            }
        }
    }

    fun refresh(useRoot: Boolean = false) {
        val app = getApplication<Application>()
        HookPrefs.ensureDefaults(app)
        val spoof = HookPrefs.loadSpoofValues(app)
        val features = HookPrefs.loadFeatures(app)
        val summary = spoof.fullParametersSummary(features)
        val hookState = HookSessionController.isEnabled(app)
        worker.execute {
            val status = XposedStatusChecker.check(app, useRoot)
            mainHandler.post {
                _hookEnabled.value = hookState
                _status.value = status
                _spoofState.value = SpoofUiState(
                    profileLabel = features.configName,
                    buildSummary = spoof.buildSummary(),
                    currentFields = summary,
                )
            }
        }
    }

    /** 从子页返回：只刷新参数，不覆盖 Xposed 状态。 */
    fun refreshSpoofOnly() {
        val app = getApplication<Application>()
        HookPrefs.ensureDefaults(app)
        val spoof = HookPrefs.loadSpoofValues(app)
        val features = HookPrefs.loadFeatures(app)
        val summary = spoof.fullParametersSummary(features)
        _hookEnabled.value = HookSessionController.isEnabled(app)
        _spoofState.value = SpoofUiState(
            profileLabel = features.configName,
            buildSummary = spoof.buildSummary(),
            currentFields = summary,
        )
    }

    fun setHookEnabled(enabled: Boolean) {
        if (_hookBusy.value == true) return
        val app = getApplication<Application>()
        if (!enabled) {
            publishSessionResult(HookSessionController.disable(app))
            return
        }
        _hookBusy.value = true
        worker.execute {
            val result = HookSessionController.enable(app)
            mainHandler.post {
                _hookBusy.value = false
                publishSessionResult(result)
            }
        }
    }

    fun consumeSessionMessage() {
        _sessionMessage.value = null
    }

    private fun publishSessionResult(result: HookSessionResult) {
        _hookEnabled.value = result.enabled
        _sessionMessage.value = result.message
        refreshSpoofOnly()
    }

    fun randomizeSpoof() {
        val app = getApplication<Application>()
        _hookBusy.value = true
        worker.execute {
            val summary = HookPrefs.randomizeSpoof(app)
            HookConfigPublisher.publish(app)
            val restart = HookSessionController.notifyConfigChanged(app)
            val spoof = HookPrefs.loadSpoofValues(app)
            val features = HookPrefs.loadFeatures(app)
            mainHandler.post {
                _hookBusy.value = false
                _spoofState.value = SpoofUiState(
                    profileLabel = features.configName,
                    buildSummary = spoof.buildSummary(),
                    currentFields = summary,
                )
                restart?.message?.let { _sessionMessage.value = it }
            }
        }
    }
}
