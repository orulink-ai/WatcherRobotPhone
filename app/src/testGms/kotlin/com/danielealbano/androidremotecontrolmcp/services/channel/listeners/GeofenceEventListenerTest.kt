package com.danielealbano.androidremotecontrolmcp.services.channel.listeners

import com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus
import com.danielealbano.androidremotecontrolmcp.data.model.GeofenceChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.GeofenceZone
import com.danielealbano.androidremotecontrolmcp.services.channel.EventDispatcher
import com.danielealbano.androidremotecontrolmcp.services.channel.geofence.GeofenceManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("GeofenceEventListener")
class GeofenceEventListenerTest {
    private val mockContext = mockk<android.content.Context>(relaxed = true)

    private val testZone =
        GeofenceZone(
            id = "z1",
            name = "Office",
            latitude = 40.7128,
            longitude = -74.006,
            radiusMeters = 200f,
        )

    private fun createMockDispatcher(): EventDispatcher {
        val mock = mockk<EventDispatcher>(relaxed = true)
        coEvery { mock.dispatch(any()) } returns Result.success(Unit)
        coEvery { mock.connectionStatus } returns MutableStateFlow(ChannelConnectionStatus.Idle)
        return mock
    }

    private fun createMockGeofenceManager(): GeofenceManager {
        val mock = mockk<GeofenceManager>(relaxed = true)
        coEvery { mock.syncGeofences(any()) } returns Result.success(Unit)
        coEvery { mock.removeAllGeofences() } returns Result.success(Unit)
        return mock
    }

    @Nested
    @DisplayName("lifecycle")
    inner class Lifecycle {
        @Test
        fun `start syncs geofences with manager`() =
            runTest {
                val manager = createMockGeofenceManager()
                val listener = GeofenceEventListener(createMockDispatcher(), manager, this, mockContext)
                val config = GeofenceChannelConfig(enabled = true, zones = listOf(testZone))
                listener.start(config)
                testScheduler.advanceUntilIdle()
                coVerify { manager.syncGeofences(listOf(testZone)) }
            }

        @Test
        fun `stop removes all geofences`() =
            runTest {
                val manager = createMockGeofenceManager()
                val listener = GeofenceEventListener(createMockDispatcher(), manager, this, mockContext)
                listener.stop()
                testScheduler.advanceUntilIdle()
                coVerify { manager.removeAllGeofences() }
            }

        @Test
        fun `updateConfig re-syncs geofences`() =
            runTest {
                val manager = createMockGeofenceManager()
                val listener = GeofenceEventListener(createMockDispatcher(), manager, this, mockContext)
                val newZone = testZone.copy(id = "z2", name = "Home")
                listener.updateConfig(GeofenceChannelConfig(enabled = true, zones = listOf(newZone)))
                testScheduler.advanceUntilIdle()
                coVerify { manager.syncGeofences(listOf(newZone)) }
            }
    }

    @Nested
    @DisplayName("transitions")
    inner class Transitions {
        @Test
        fun `handleTransition dispatches event for known zone`() =
            runTest {
                val dispatcher = createMockDispatcher()
                val listener = GeofenceEventListener(dispatcher, createMockGeofenceManager(), this, mockContext)
                listener.start(GeofenceChannelConfig(enabled = true, zones = listOf(testZone)))
                listener.handleTransition("z1", "enter")
                coVerify { dispatcher.dispatch(match { it.type == "geofence" }) }
            }

        @Test
        fun `handleTransition ignores unknown zone`() =
            runTest {
                val dispatcher = createMockDispatcher()
                val listener = GeofenceEventListener(dispatcher, createMockGeofenceManager(), this, mockContext)
                listener.start(GeofenceChannelConfig(enabled = true, zones = listOf(testZone)))
                listener.handleTransition("unknown-zone", "enter")
                coVerify(exactly = 0) { dispatcher.dispatch(any()) }
            }
    }
}
