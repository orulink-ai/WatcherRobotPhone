package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.GeofenceChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.GeofenceZone
import kotlinx.coroutines.flow.Flow

/**
 * gms-only persistence for geofence channel configuration, stored under its own DataStore key
 * (separate from the shared `EventChannelConfig` blob so `main` settings writes cannot clobber it).
 */
interface GeofenceConfigRepository {
    val geofenceConfig: Flow<GeofenceChannelConfig>

    suspend fun getGeofenceConfig(): GeofenceChannelConfig

    /** Idempotent one-time migration of legacy blob geofence data into the dedicated key. */
    suspend fun migrateIfNeeded()

    suspend fun updateGeofenceChannelEnabled(enabled: Boolean)

    suspend fun addGeofenceZone(zone: GeofenceZone)

    suspend fun removeGeofenceZone(zoneId: String)

    suspend fun updateGeofenceZone(zone: GeofenceZone)
}
