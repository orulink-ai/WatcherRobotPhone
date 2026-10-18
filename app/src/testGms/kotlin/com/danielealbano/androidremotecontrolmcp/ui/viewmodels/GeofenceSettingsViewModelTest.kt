package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.data.model.GeofenceChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.GeofenceZone
import com.danielealbano.androidremotecontrolmcp.data.model.LocationData
import com.danielealbano.androidremotecontrolmcp.data.repository.GeofenceConfigRepository
import com.danielealbano.androidremotecontrolmcp.services.location.LocationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("GeofenceSettingsViewModel")
class GeofenceSettingsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val repository = mockk<GeofenceConfigRepository>(relaxed = true)
    private val locationProvider = mockk<LocationProvider>(relaxed = true)
    private val configFlow = MutableStateFlow(GeofenceChannelConfig())
    private lateinit var viewModel: GeofenceSettingsViewModel

    private val zone = GeofenceZone("z1", "Home", 40.0, -74.0, 200f)

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { repository.geofenceConfig } returns configFlow
        viewModel = GeofenceSettingsViewModel(repository, locationProvider, testDispatcher)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `mutators delegate to repository`() =
        runTest {
            viewModel.updateGeofenceChannelEnabled(true)
            viewModel.addGeofenceZone(zone)
            viewModel.updateGeofenceZone(zone.copy(name = "Office"))
            viewModel.removeGeofenceZone("z1")
            advanceUntilIdle()

            coVerify { repository.updateGeofenceChannelEnabled(true) }
            coVerify { repository.addGeofenceZone(zone) }
            coVerify { repository.updateGeofenceZone(zone.copy(name = "Office")) }
            coVerify { repository.removeGeofenceZone("z1") }
        }

    @Test
    fun `geofenceConfig mirrors repository flow`() =
        runTest {
            val expected = GeofenceChannelConfig(enabled = true, zones = listOf(zone))
            configFlow.value = expected

            viewModel.geofenceConfig.test {
                assertEquals(expected, expectMostRecentItem())
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `currentLocation delegates to LocationProvider (last-known)`() =
        runTest {
            val data = LocationData(40.0, -74.0, 5f, "Home St")
            coEvery { locationProvider.getLocation(false) } returns Result.success(data)

            val result = viewModel.currentLocation()

            assertEquals(data, result.getOrNull())
            coVerify { locationProvider.getLocation(freshFix = false) }
        }
}
