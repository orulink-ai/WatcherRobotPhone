package com.danielealbano.androidremotecontrolmcp.ui.navigation

/** gms-only geofence navigation routes (kept out of the shared SettingsRoute so foss has none). */
object GeofenceRoutes {
    const val LIST = "settings/channel/geofence_list"
    const val MAP_PATTERN = "settings/channel/geofence_map/{zoneId}"

    fun map(zoneId: String? = null): String = "settings/channel/geofence_map/${zoneId ?: ""}"
}
