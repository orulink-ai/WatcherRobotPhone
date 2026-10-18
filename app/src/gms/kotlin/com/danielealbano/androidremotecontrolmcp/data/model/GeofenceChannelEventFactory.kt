package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** gms-only factory for geofence channel events (moved out of the shared [ChannelEventFactory]). */
object GeofenceChannelEventFactory {
    fun geofence(
        zone: GeofenceZone,
        transition: String,
        address: String? = null,
    ): ChannelEvent =
        ChannelEvent(
            type = "geofence",
            timestamp = Instant.now().toString(),
            data =
                buildJsonObject {
                    put("zoneId", zone.id)
                    put("zoneName", zone.name)
                    put("address", address?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("transition", transition)
                    put("latitude", zone.latitude)
                    put("longitude", zone.longitude)
                    put("radiusMeters", zone.radiusMeters)
                },
        )
}
