@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.AppInfo
import com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus
import com.danielealbano.androidremotecontrolmcp.data.model.EventChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationFilterMode
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.apps.AppIconCache
import com.danielealbano.androidremotecontrolmcp.services.channel.EventChannelService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChannelViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val appIconCache: AppIconCache,
        @ApplicationContext private val appContext: Context,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val eventChannelConfig: StateFlow<EventChannelConfig> =
            settingsRepository.eventChannelConfig
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EventChannelConfig())

        val channelConnectionStatus: StateFlow<ChannelConnectionStatus> =
            EventChannelService.serviceStatus

        private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
        val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

        private val _appIcons = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
        val appIcons: StateFlow<Map<String, Bitmap>> = _appIcons.asStateFlow()

        private val _endpointUrlInput = MutableStateFlow("")
        val endpointUrlInput: StateFlow<String> = _endpointUrlInput.asStateFlow()
        private val _endpointUrlError = MutableStateFlow<String?>(null)
        val endpointUrlError: StateFlow<String?> = _endpointUrlError.asStateFlow()
        private val _authTokenInput = MutableStateFlow("")
        val authTokenInput: StateFlow<String> = _authTokenInput.asStateFlow()

        init {
            viewModelScope.launch {
                eventChannelConfig.collect { config ->
                    _endpointUrlInput.value = config.endpointUrl
                    _authTokenInput.value = config.authToken
                }
            }
        }

        fun updateChannelEnabled(enabled: Boolean) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateEventChannelEnabled(enabled)
            }
        }

        fun startChannel() {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateEventChannelEnabled(true)
            }
            startChannelService()
        }

        fun stopChannel() {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateEventChannelEnabled(false)
            }
            stopChannelService()
        }

        fun updateEndpointUrl(url: String) {
            _endpointUrlInput.value = url
            val result = settingsRepository.validateEndpointUrl(url)
            if (result.isSuccess) {
                _endpointUrlError.value = null
                viewModelScope.launch(ioDispatcher) {
                    settingsRepository.updateEventChannelEndpointUrl(url)
                }
            } else {
                _endpointUrlError.value = result.exceptionOrNull()?.message
            }
        }

        fun updateAuthToken(token: String) {
            _authTokenInput.value = token
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateEventChannelAuthToken(token)
            }
        }

        fun generateNewAuthToken() {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.generateNewEventChannelAuthToken()
            }
        }

        // Notification settings
        fun updateNotificationChannelEnabled(enabled: Boolean) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateNotificationChannelEnabled(enabled)
            }
        }

        fun updateNotificationFilterMode(mode: NotificationFilterMode) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateNotificationFilterMode(mode)
            }
        }

        fun toggleNotificationFilterApp(
            packageName: String,
            included: Boolean,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val current = settingsRepository.getEventChannelConfig().notifications.filterApps
                val updated = if (included) current + packageName else current - packageName
                settingsRepository.updateNotificationFilterApps(updated)
            }
        }

        // WiFi settings
        fun updateWifiChannelEnabled(enabled: Boolean) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateWifiChannelEnabled(enabled)
            }
        }

        fun addWifiSsid(ssid: String) {
            viewModelScope.launch(ioDispatcher) {
                val current = settingsRepository.getEventChannelConfig().wifi.ssids
                settingsRepository.updateWifiSsids(current + ssid)
            }
        }

        fun removeWifiSsid(ssid: String) {
            viewModelScope.launch(ioDispatcher) {
                val current = settingsRepository.getEventChannelConfig().wifi.ssids
                settingsRepository.updateWifiSsids(current - ssid)
            }
        }

        fun updateWifiNotifyOnDiscovered(enabled: Boolean) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateWifiNotifyOnDiscovered(enabled)
            }
        }

        fun updateWifiNotifyOnLost(enabled: Boolean) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateWifiNotifyOnLost(enabled)
            }
        }

        fun updateWifiNotifyOnConnected(enabled: Boolean) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateWifiNotifyOnConnected(enabled)
            }
        }

        fun updateWifiNotifyOnDisconnected(enabled: Boolean) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateWifiNotifyOnDisconnected(enabled)
            }
        }

        fun loadInstalledApps() {
            // Show cached list immediately (preloaded at app start)
            _installedApps.value = toAppInfoList(appIconCache.getLaunchableApps())
            _appIcons.value = appIconCache.getAll()

            // Refresh in background: detects newly installed / uninstalled apps
            appIconCache.refresh {
                _installedApps.value = toAppInfoList(appIconCache.getLaunchableApps())
                _appIcons.value = appIconCache.getAll()
            }
        }

        private fun toAppInfoList(apps: List<Pair<String, String>>): List<AppInfo> =
            apps.map { (packageId, name) ->
                AppInfo(
                    packageId = packageId,
                    name = name,
                    versionName = null,
                    versionCode = 0,
                    isSystemApp = false,
                )
            }

        private fun startChannelService() {
            val intent =
                Intent(appContext, EventChannelService::class.java).apply {
                    action = EventChannelService.ACTION_START
                }
            appContext.startForegroundService(intent)
        }

        private fun stopChannelService() {
            val intent =
                Intent(appContext, EventChannelService::class.java).apply {
                    action = EventChannelService.ACTION_STOP
                }
            appContext.startService(intent)
        }

        companion object {
            private const val STOP_TIMEOUT_MS = 5000L
        }
    }
