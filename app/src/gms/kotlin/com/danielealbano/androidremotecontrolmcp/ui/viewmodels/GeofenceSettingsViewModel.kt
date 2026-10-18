package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.GeofenceChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.GeofenceZone
import com.danielealbano.androidremotecontrolmcp.data.model.LocationData
import com.danielealbano.androidremotecontrolmcp.data.repository.GeofenceConfigRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.location.LocationProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** gms-only ViewModel backing the geofence settings screens (list/map) and the channel-settings row. */
@HiltViewModel
class GeofenceSettingsViewModel
    @Inject
    constructor(
        private val geofenceConfigRepository: GeofenceConfigRepository,
        private val locationProvider: LocationProvider,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val geofenceConfig: StateFlow<GeofenceChannelConfig> =
            geofenceConfigRepository.geofenceConfig
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    GeofenceChannelConfig(),
                )

        fun updateGeofenceChannelEnabled(enabled: Boolean) {
            viewModelScope.launch(ioDispatcher) {
                geofenceConfigRepository.updateGeofenceChannelEnabled(enabled)
            }
        }

        fun addGeofenceZone(zone: GeofenceZone) {
            viewModelScope.launch(ioDispatcher) { geofenceConfigRepository.addGeofenceZone(zone) }
        }

        fun removeGeofenceZone(zoneId: String) {
            viewModelScope.launch(ioDispatcher) { geofenceConfigRepository.removeGeofenceZone(zoneId) }
        }

        fun updateGeofenceZone(zone: GeofenceZone) {
            viewModelScope.launch(ioDispatcher) { geofenceConfigRepository.updateGeofenceZone(zone) }
        }

        /** Last-known location for map centering (mirrors the previous `.lastLocation` behavior). */
        suspend fun currentLocation(): Result<LocationData> = withContext(ioDispatcher) { locationProvider.getLocation(freshFix = false) }

        companion object {
            private const val STOP_TIMEOUT_MS = 5000L
        }
    }
