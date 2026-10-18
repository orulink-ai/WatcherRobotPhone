package com.danielealbano.androidremotecontrolmcp.services.channel.listeners

import android.content.Context
import android.location.Geocoder
import com.danielealbano.androidremotecontrolmcp.data.model.GeofenceChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.GeofenceChannelEventFactory
import com.danielealbano.androidremotecontrolmcp.services.channel.EventDispatcher
import com.danielealbano.androidremotecontrolmcp.services.channel.geofence.GeofenceManager
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class GeofenceEventListener(
    private val eventDispatcher: EventDispatcher,
    private val geofenceManager: GeofenceManager,
    private val scope: CoroutineScope,
    private val context: Context,
) {
    @Volatile
    private var currentConfig: GeofenceChannelConfig = GeofenceChannelConfig()

    fun start(config: GeofenceChannelConfig) {
        currentConfig = config
        scope.launch {
            geofenceManager.syncGeofences(config.zones)
        }
    }

    fun stop() {
        scope.launch {
            geofenceManager.removeAllGeofences()
        }
    }

    fun updateConfig(config: GeofenceChannelConfig) {
        currentConfig = config
        scope.launch {
            geofenceManager.syncGeofences(config.zones)
        }
    }

    suspend fun handleTransition(
        zoneId: String,
        transition: String,
    ) {
        val zone =
            currentConfig.zones.find { it.id == zoneId } ?: run {
                Logger.w(TAG, "Geofence zone not found: $zoneId")
                return
            }
        val address = reverseGeocode(zone.latitude, zone.longitude)
        val event = GeofenceChannelEventFactory.geofence(zone, transition, address)
        eventDispatcher.dispatch(event)
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(
        lat: Double,
        lon: Double,
    ): String? =
        try {
            val results = Geocoder(context).getFromLocation(lat, lon, 1)
            val addr = results?.firstOrNull() ?: return null
            buildString {
                addr.thoroughfare?.let { append(it) }
                addr.subThoroughfare?.let {
                    if (isNotEmpty()) append(" ")
                    append(it)
                }
                val cityParts = listOfNotNull(addr.postalCode, addr.locality ?: addr.subAdminArea)
                if (cityParts.isNotEmpty()) {
                    if (isNotEmpty()) append(", ")
                    append(cityParts.joinToString(" "))
                }
                if (isEmpty()) addr.getAddressLine(0)?.let { append(it) }
            }.ifBlank { null }
        } catch (_: Exception) {
            null
        }

    companion object {
        private const val TAG = "MCP:GeofenceListener"
    }
}
