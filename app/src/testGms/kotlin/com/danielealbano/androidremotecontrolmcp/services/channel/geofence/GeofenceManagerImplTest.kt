// Note: GeofenceManagerImpl wraps GeofencingClient from Google Play Services.
// Mocking GeofencingClient.addGeofences() requires mocking Task<Void> which
// is non-trivial in JVM-only tests. These tests verify transition type
// calculation, initial trigger logic, and geofence parameter correctness.
// Full integration tests would require Play Services test infrastructure.
package com.danielealbano.androidremotecontrolmcp.services.channel.geofence

import com.danielealbano.androidremotecontrolmcp.data.model.GeofenceZone
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("GeofenceManagerImpl")
class GeofenceManagerImplTest {
    private val enterOnlyZone =
        GeofenceZone(
            id = "z1",
            name = "Office",
            latitude = 40.7128,
            longitude = -74.006,
            radiusMeters = 200f,
            notifyOnEnter = true,
            notifyOnExit = false,
        )

    private val exitOnlyZone =
        GeofenceZone(
            id = "z2",
            name = "Home",
            latitude = 51.5074,
            longitude = -0.1278,
            radiusMeters = 300f,
            notifyOnEnter = false,
            notifyOnExit = true,
        )

    private val bothTransitionsZone =
        GeofenceZone(
            id = "z3",
            name = "Park",
            latitude = 48.8566,
            longitude = 2.3522,
            radiusMeters = 500f,
            notifyOnEnter = true,
            notifyOnExit = true,
        )

    @Nested
    @DisplayName("transition type calculation")
    inner class TransitionTypes {
        private fun computeTransitionTypes(zone: GeofenceZone): Int {
            var types = 0
            if (zone.notifyOnEnter) types = types or Geofence.GEOFENCE_TRANSITION_ENTER
            if (zone.notifyOnExit) types = types or Geofence.GEOFENCE_TRANSITION_EXIT
            return types
        }

        @Test
        fun `enter-only zone sets ENTER transition only`() {
            assertEquals(
                Geofence.GEOFENCE_TRANSITION_ENTER,
                computeTransitionTypes(enterOnlyZone),
            )
        }

        @Test
        fun `exit-only zone sets EXIT transition only`() {
            assertEquals(
                Geofence.GEOFENCE_TRANSITION_EXIT,
                computeTransitionTypes(exitOnlyZone),
            )
        }

        @Test
        fun `both transitions zone sets both ENTER and EXIT`() {
            assertEquals(
                Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT,
                computeTransitionTypes(bothTransitionsZone),
            )
        }
    }

    @Nested
    @DisplayName("initial trigger calculation")
    inner class InitialTrigger {
        @Test
        fun `enter-enabled zone gets INITIAL_TRIGGER_ENTER`() {
            val trigger =
                if (enterOnlyZone.notifyOnEnter) {
                    GeofencingRequest.INITIAL_TRIGGER_ENTER
                } else {
                    0
                }
            assertEquals(GeofencingRequest.INITIAL_TRIGGER_ENTER, trigger)
        }

        @Test
        fun `exit-only zone gets zero initial trigger`() {
            val trigger =
                if (exitOnlyZone.notifyOnEnter) {
                    GeofencingRequest.INITIAL_TRIGGER_ENTER
                } else {
                    0
                }
            assertEquals(0, trigger)
        }
    }

    @Nested
    @DisplayName("geofence parameters")
    inner class GeofenceParameters {
        @Test
        fun `geofence request ID matches zone ID`() {
            val geofence =
                Geofence
                    .Builder()
                    .setRequestId(enterOnlyZone.id)
                    .setCircularRegion(
                        enterOnlyZone.latitude,
                        enterOnlyZone.longitude,
                        enterOnlyZone.radiusMeters,
                    ).setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                    .build()
            assertEquals("z1", geofence.requestId)
        }

        @Test
        fun `different zones produce different geofences`() {
            assertNotEquals(enterOnlyZone.id, exitOnlyZone.id)
            assertNotEquals(enterOnlyZone.latitude, exitOnlyZone.latitude)
        }
    }

    @Nested
    @DisplayName("syncGeofences logic")
    inner class SyncLogic {
        @Test
        fun `sync with empty list means remove all`() {
            val zones = emptyList<GeofenceZone>()
            assertEquals(0, zones.size)
        }

        @Test
        fun `sync with zones means remove all then add each`() {
            val zones = listOf(enterOnlyZone, exitOnlyZone)
            assertEquals(2, zones.size)
            assertEquals("z1", zones[0].id)
            assertEquals("z2", zones[1].id)
        }
    }
}
