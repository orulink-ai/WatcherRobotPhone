package com.danielealbano.androidremotecontrolmcp.services.location

import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("FossLocationProviderImpl")
class FossLocationProviderImplTest {
    private val context = mockk<Context>(relaxed = true)
    private val locationManager = mockk<LocationManager>(relaxed = true)
    private lateinit var provider: FossLocationProviderImpl

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        mockkStatic(Geocoder::class)
        every { Geocoder.isPresent() } returns false // reverseGeocode short-circuits to null
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED

        every { context.getSystemService(Context.LOCATION_SERVICE) } returns locationManager
        provider = FossLocationProviderImpl(context)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class, Geocoder::class, ContextCompat::class)
    }

    private fun location(
        lat: Double,
        lon: Double,
        acc: Float,
        t: Long,
    ): Location =
        mockk {
            every { latitude } returns lat
            every { longitude } returns lon
            every { accuracy } returns acc
            every { time } returns t
        }

    @Test
    fun `missing permission returns failure`() =
        runTest {
            every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_DENIED

            val result = provider.getLocation(freshFix = false)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is SecurityException)
        }

    @Test
    fun `last known location returned when freshFix false`() =
        runTest {
            every { locationManager.allProviders } returns listOf("gps", "network", "fused")
            every { locationManager.getLastKnownLocation("gps") } returns location(1.0, 2.0, 10f, 100L)
            every { locationManager.getLastKnownLocation("network") } returns location(3.0, 4.0, 20f, 500L)
            every { locationManager.getLastKnownLocation("fused") } returns null

            val result = provider.getLocation(freshFix = false)

            assertTrue(result.isSuccess)
            val data = result.getOrNull()!!
            // Most-recent fix (network, t=500) wins.
            assertEquals(3.0, data.latitude)
            assertEquals(4.0, data.longitude)
        }

    @Test
    fun `no fix returns descriptive failure`() =
        runTest {
            every { locationManager.allProviders } returns listOf("gps", "network")
            every { locationManager.getLastKnownLocation(any()) } returns null

            val result = provider.getLocation(freshFix = false)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("No last known location") == true)
        }

    @Test
    fun `fresh fix timeout returns failure`() =
        runTest {
            every { locationManager.allProviders } returns listOf("gps")
            every { locationManager.isProviderEnabled(any()) } returns true
            // getCurrentLocation never invokes its consumer → the suspend point never resumes.
            every { locationManager.getCurrentLocation(any(), any(), any(), any()) } just runs

            val result = provider.getLocation(freshFix = true)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("Timed out") == true)
        }
}
