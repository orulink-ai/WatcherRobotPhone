package com.danielealbano.androidremotecontrolmcp.data.model

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val street: String?,
)
