package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.Serializable

@Serializable
data class GeofenceChannelConfig(
    val enabled: Boolean = false,
    val zones: List<GeofenceZone> = emptyList(),
)

@Serializable
data class GeofenceZone(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val notifyOnEnter: Boolean = true,
    val notifyOnExit: Boolean = true,
)
