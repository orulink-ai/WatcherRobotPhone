package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.PlaceholderFormat
import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.data.model.RedactionMode
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyModeManager
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyModeStatus
import com.danielealbano.androidremotecontrolmcp.privacy.model.DownloadState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Privacy Mode settings screen and the Server-screen callout card. A DEDICATED ViewModel
 * (not [MainViewModel]) so the settings/config wiring stays isolated and the constructor stays small.
 */
@HiltViewModel
class PrivacyViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val privacyModeManager: PrivacyModeManager,
    ) : ViewModel() {
        val privacyConfig: StateFlow<PrivacyModeConfig> =
            settingsRepository.serverConfig
                .map { it.privacyModeConfig }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), PrivacyModeConfig())

        val privacyStatus: StateFlow<PrivacyModeStatus> = privacyModeManager.status

        val privacyDownloadState: StateFlow<DownloadState> = privacyModeManager.downloadState

        /** True from an enable request until download + warm-up complete (disables the master toggle). */
        val privacyEnableInProgress: StateFlow<Boolean> = privacyModeManager.enableInProgress

        /** True while the on-device performance benchmark is measuring. */
        val privacyBenchmarkRunning: StateFlow<Boolean> = privacyModeManager.benchmarkRunning

        val privacyBenchmarkEstimate: StateFlow<Double?> =
            settingsRepository.privacyBenchmarkEstimateSeconds
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), null)

        /** True once the user dismissed the home-screen Privacy Mode callout card (persisted). */
        val privacyCardDismissed: StateFlow<Boolean> =
            settingsRepository.privacyModeCardDismissed
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), false)

        fun dismissPrivacyCard() {
            viewModelScope.launch { settingsRepository.updatePrivacyModeCardDismissed(true) }
        }

        private val mutableConsentRequired = MutableStateFlow(false)

        /** True when the master toggle was turned on but the model must be downloaded first (consent). */
        val consentRequired: StateFlow<Boolean> = mutableConsentRequired.asStateFlow()

        /**
         * Master-toggle ON entry point. [PrivacyModeManager.isModelReady] does its filesystem check off
         * the main thread; if ready, enable immediately, otherwise raise [consentRequired] so the screen
         * can show the download dialog. The enable flow itself runs in the manager's own scope so it
         * survives leaving this screen.
         */
        fun requestEnablePrivacyMode() {
            viewModelScope.launch {
                if (privacyModeManager.isModelReady()) {
                    privacyModeManager.enableWithDownloadInBackground()
                } else {
                    mutableConsentRequired.value = true
                }
            }
        }

        /** Consent dialog confirmed: clear the prompt and enable (downloading the model as part of it). */
        fun confirmDownloadAndEnable() {
            mutableConsentRequired.value = false
            privacyModeManager.enableWithDownloadInBackground()
        }

        /** Consent dialog dismissed: leave Privacy Mode off. */
        fun cancelConsent() {
            mutableConsentRequired.value = false
        }

        fun disablePrivacyMode() {
            viewModelScope.launch {
                settingsRepository.updatePrivacyModeEnabled(false)
                privacyModeManager.selfCheck()
            }
        }

        fun updatePrivacyCategoryEnabled(
            category: PiiCategory,
            enabled: Boolean,
        ) {
            viewModelScope.launch { settingsRepository.updatePrivacyCategoryEnabled(category, enabled) }
        }

        fun updatePrivacyRedactionMode(mode: RedactionMode) {
            viewModelScope.launch { settingsRepository.updatePrivacyRedactionMode(mode) }
        }

        fun updatePrivacyPlaceholderFormat(format: PlaceholderFormat) {
            viewModelScope.launch { settingsRepository.updatePrivacyPlaceholderFormat(format) }
        }

        private companion object {
            private const val FLOW_TIMEOUT_MS = 5_000L
        }
    }
