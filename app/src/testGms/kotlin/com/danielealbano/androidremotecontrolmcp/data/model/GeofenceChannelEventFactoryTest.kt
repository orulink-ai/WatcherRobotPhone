package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("GeofenceChannelEventFactory")
class GeofenceChannelEventFactoryTest {
    @Test
    fun `geofence event has expected fields`() {
        val zone =
            GeofenceZone(
                id = "z1",
                name = "Office",
                latitude = 40.7128,
                longitude = -74.006,
                radiusMeters = 200f,
            )

        val event = GeofenceChannelEventFactory.geofence(zone, "enter", "5th Ave")

        assertEquals("geofence", event.type)
        val data = event.data.jsonObject
        assertEquals("z1", data["zoneId"]?.jsonPrimitive?.content)
        assertEquals("Office", data["zoneName"]?.jsonPrimitive?.content)
        assertEquals("5th Ave", data["address"]?.jsonPrimitive?.content)
        assertEquals("enter", data["transition"]?.jsonPrimitive?.content)
        assertNotNull(data["latitude"])
        assertNotNull(data["longitude"])
        assertNotNull(data["radiusMeters"])
    }
}
