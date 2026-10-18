package com.danielealbano.androidremotecontrolmcp.services.channel.geofence

import android.content.Context
import android.content.Intent
import com.danielealbano.androidremotecontrolmcp.services.channel.EventChannelService
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("GeofenceTransitionReceiver")
class GeofenceTransitionReceiverTest {
    private val context = mockk<Context>(relaxed = true)
    private val receiver = GeofenceTransitionReceiver()

    @BeforeEach
    fun setup() {
        mockkStatic(GeofencingEvent::class)
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(GeofencingEvent::class)
    }

    @Nested
    @DisplayName("onReceive")
    inner class OnReceive {
        @Test
        fun `onReceive with null geofencing event returns early`() {
            val intent = mockk<Intent>()
            every { GeofencingEvent.fromIntent(intent) } returns null

            receiver.onReceive(context, intent)

            verify(exactly = 0) { context.startForegroundService(any()) }
        }

        @Test
        fun `onReceive with error event returns early`() {
            val intent = mockk<Intent>()
            val event = mockk<GeofencingEvent>()
            every { GeofencingEvent.fromIntent(intent) } returns event
            every { event.hasError() } returns true
            every { event.errorCode } returns 1

            receiver.onReceive(context, intent)

            verify(exactly = 0) { context.startForegroundService(any()) }
        }

        @Test
        fun `onReceive with enter transition forwards intent to service`() {
            val intent = mockk<Intent>()
            val event = mockk<GeofencingEvent>()
            val geofence = mockk<Geofence>()
            every { GeofencingEvent.fromIntent(intent) } returns event
            every { event.hasError() } returns false
            every { event.geofenceTransition } returns Geofence.GEOFENCE_TRANSITION_ENTER
            every { event.triggeringGeofences } returns listOf(geofence)
            every { geofence.requestId } returns "zone1"

            receiver.onReceive(context, intent)

            verify { context.startForegroundService(any()) }
        }

        @Test
        fun `onReceive with exit transition forwards intent to service`() {
            val intent = mockk<Intent>()
            val event = mockk<GeofencingEvent>()
            val geofence = mockk<Geofence>()
            every { GeofencingEvent.fromIntent(intent) } returns event
            every { event.hasError() } returns false
            every { event.geofenceTransition } returns Geofence.GEOFENCE_TRANSITION_EXIT
            every { event.triggeringGeofences } returns listOf(geofence)
            every { geofence.requestId } returns "zone2"

            receiver.onReceive(context, intent)

            verify { context.startForegroundService(any()) }
        }
    }
}
