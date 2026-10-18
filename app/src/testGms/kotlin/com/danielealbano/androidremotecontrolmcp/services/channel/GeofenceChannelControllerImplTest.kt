package com.danielealbano.androidremotecontrolmcp.services.channel

import android.content.Context
import android.content.Intent
import com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus
import com.danielealbano.androidremotecontrolmcp.data.model.GeofenceChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.GeofenceZone
import com.danielealbano.androidremotecontrolmcp.data.repository.GeofenceConfigRepository
import com.danielealbano.androidremotecontrolmcp.services.channel.geofence.GeofenceManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("GeofenceChannelControllerImpl")
class GeofenceChannelControllerImplTest {
    private val context = mockk<Context>(relaxed = true)

    private val zone =
        GeofenceZone(
            id = "z1",
            name = "Home",
            latitude = 40.7128,
            longitude = -74.006,
            radiusMeters = 200f,
        )

    private fun geofenceManager(): GeofenceManager {
        val manager = mockk<GeofenceManager>(relaxed = true)
        coEvery { manager.syncGeofences(any()) } returns Result.success(Unit)
        coEvery { manager.removeAllGeofences() } returns Result.success(Unit)
        return manager
    }

    private fun eventDispatcher(): EventDispatcher {
        val dispatcher = mockk<EventDispatcher>(relaxed = true)
        coEvery { dispatcher.dispatch(any()) } returns Result.success(Unit)
        every { dispatcher.connectionStatus } returns MutableStateFlow(ChannelConnectionStatus.Idle)
        return dispatcher
    }

    private fun repository(flow: MutableStateFlow<GeofenceChannelConfig>): GeofenceConfigRepository {
        val repo = mockk<GeofenceConfigRepository>()
        every { repo.geofenceConfig } returns flow
        return repo
    }

    @Test
    fun `enabled config syncs geofences`() =
        runTest(UnconfinedTestDispatcher()) {
            val manager = geofenceManager()
            val controller =
                GeofenceChannelControllerImpl(
                    context,
                    manager,
                    repository(MutableStateFlow(GeofenceChannelConfig(enabled = true, zones = listOf(zone)))),
                )

            controller.onChannelStarted(eventDispatcher(), backgroundScope)
            advanceUntilIdle()

            coVerify { manager.syncGeofences(listOf(zone)) }
        }

    @Test
    fun `disabled config removes geofences`() =
        runTest(UnconfinedTestDispatcher()) {
            val manager = geofenceManager()
            val flow = MutableStateFlow(GeofenceChannelConfig(enabled = true, zones = listOf(zone)))
            val controller = GeofenceChannelControllerImpl(context, manager, repository(flow))

            controller.onChannelStarted(eventDispatcher(), backgroundScope)
            advanceUntilIdle()
            flow.value = GeofenceChannelConfig(enabled = false)
            advanceUntilIdle()

            coVerify { manager.removeAllGeofences() }
        }

    @Test
    fun `handleGeofenceIntent dispatches transition when started`() =
        runTest(UnconfinedTestDispatcher()) {
            val dispatcher = eventDispatcher()
            val controller =
                GeofenceChannelControllerImpl(
                    context,
                    geofenceManager(),
                    repository(MutableStateFlow(GeofenceChannelConfig(enabled = true, zones = listOf(zone)))),
                )
            controller.onChannelStarted(dispatcher, backgroundScope)
            advanceUntilIdle()

            val intent = mockk<Intent>()
            every { intent.getStringExtra(EventChannelService.EXTRA_GEOFENCE_ZONE_ID) } returns "z1"
            every { intent.getStringExtra(EventChannelService.EXTRA_GEOFENCE_TRANSITION) } returns "enter"

            controller.handleGeofenceIntent(intent)
            advanceUntilIdle()

            coVerify { dispatcher.dispatch(any()) }
        }

    @Test
    fun `handleGeofenceIntent without active session is a safe no-op`() =
        runTest(UnconfinedTestDispatcher()) {
            val dispatcher = eventDispatcher()
            val controller =
                GeofenceChannelControllerImpl(
                    context,
                    geofenceManager(),
                    repository(MutableStateFlow(GeofenceChannelConfig())),
                )

            val intent = mockk<Intent>()
            every { intent.getStringExtra(any()) } returns "z1"

            // No onChannelStarted → stored scope is null → must not throw or dispatch (P53-007).
            controller.handleGeofenceIntent(intent)
            advanceUntilIdle()

            coVerify(exactly = 0) { dispatcher.dispatch(any()) }
        }

    @Test
    fun `onChannelStopped stops listener`() =
        runTest(UnconfinedTestDispatcher()) {
            val manager = geofenceManager()
            val controller =
                GeofenceChannelControllerImpl(
                    context,
                    manager,
                    repository(MutableStateFlow(GeofenceChannelConfig(enabled = true, zones = listOf(zone)))),
                )
            controller.onChannelStarted(eventDispatcher(), backgroundScope)
            advanceUntilIdle()

            controller.onChannelStopped()
            advanceUntilIdle()

            coVerify { manager.removeAllGeofences() }
        }
}
