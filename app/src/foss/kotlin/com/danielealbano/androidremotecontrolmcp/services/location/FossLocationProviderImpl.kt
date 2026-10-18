package com.danielealbano.androidremotecontrolmcp.services.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.data.model.LocationData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.function.Consumer
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * FOSS (no Google Play Services) [LocationProvider] backed by the framework [LocationManager].
 *
 * Mirrors the Fused implementation's `getLocation(freshFix)` contract and error semantics. Reverse
 * geocoding is delegated to the shared framework-only [reverseGeocode] helper.
 */
class FossLocationProviderImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : LocationProvider {
        private val locationManager: LocationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        @Suppress("TooGenericExceptionCaught", "ReturnCount")
        override suspend fun getLocation(freshFix: Boolean): Result<LocationData> {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return Result.failure(
                    SecurityException(
                        "Location permission not granted. " +
                            "Please grant ACCESS_FINE_LOCATION in Android Settings.",
                    ),
                )
            }

            val location =
                try {
                    if (freshFix) {
                        requestFreshLocation()
                    } else {
                        getLastKnownLocation()
                    }
                } catch (e: TimeoutCancellationException) {
                    return Result.failure(
                        IllegalStateException(
                            "Timed out waiting for fresh GPS fix " +
                                "(${LocationProvider.FRESH_FIX_TIMEOUT_MS}ms)",
                            e,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return Result.failure(
                        IllegalStateException("Failed to get location: ${e.message}", e),
                    )
                }

            if (location == null) {
                return Result.failure(
                    IllegalStateException(
                        "No last known location available. Try with fresh_fix=true.",
                    ),
                )
            }

            val street = reverseGeocode(context, location.latitude, location.longitude)

            return Result.success(
                LocationData(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy,
                    street = street,
                ),
            )
        }

        /** Providers to try, best-first: framework fused (API 31+), then GPS, then network. */
        private fun candidateProviders(): List<String> =
            listOf(
                LocationManager.FUSED_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
            ).filter { it in locationManager.allProviders }

        @SuppressLint("MissingPermission")
        private fun getLastKnownLocation(): Location? =
            candidateProviders()
                .mapNotNull { locationManager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }

        @SuppressLint("MissingPermission")
        private suspend fun requestFreshLocation(): Location =
            withTimeout(LocationProvider.FRESH_FIX_TIMEOUT_MS) {
                val provider =
                    candidateProviders().firstOrNull { locationManager.isProviderEnabled(it) }
                        ?: throw IllegalStateException("No enabled location provider available")
                suspendCancellableCoroutine { cont ->
                    val cancellationSignal = CancellationSignal()
                    val consumer =
                        Consumer<Location?> { loc ->
                            if (loc != null) {
                                cont.resume(loc)
                            } else {
                                cont.resumeWithException(
                                    IllegalStateException("Location result was null"),
                                )
                            }
                        }
                    locationManager.getCurrentLocation(
                        provider,
                        cancellationSignal,
                        context.mainExecutor,
                        consumer,
                    )
                    cont.invokeOnCancellation { cancellationSignal.cancel() }
                }
            }
    }
