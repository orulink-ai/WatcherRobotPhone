package com.danielealbano.androidremotecontrolmcp.services.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.data.model.LocationData
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LocationProviderImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : LocationProvider {
        private val fusedLocationClient: FusedLocationProviderClient =
            LocationServices.getFusedLocationProviderClient(context)

        @Suppress("TooGenericExceptionCaught", "ReturnCount")
        override suspend fun getLocation(freshFix: Boolean): Result<LocationData> {
            val playServicesStatus =
                GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
            if (playServicesStatus != ConnectionResult.SUCCESS) {
                return Result.failure(
                    IllegalStateException("Google Play Services not available"),
                )
            }

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

        @SuppressLint("MissingPermission")
        private suspend fun getLastKnownLocation(): Location? = fusedLocationClient.lastLocation.await()

        @SuppressLint("MissingPermission")
        private suspend fun requestFreshLocation(): Location =
            withTimeout(LocationProvider.FRESH_FIX_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val request =
                        LocationRequest
                            .Builder(Priority.PRIORITY_HIGH_ACCURACY, 0L)
                            .setMaxUpdates(1)
                            .build()

                    val callback =
                        object : LocationCallback() {
                            override fun onLocationResult(result: LocationResult) {
                                fusedLocationClient.removeLocationUpdates(this)
                                val loc = result.lastLocation
                                if (loc != null) {
                                    cont.resume(loc)
                                } else {
                                    cont.resumeWithException(
                                        IllegalStateException("Location result was null"),
                                    )
                                }
                            }
                        }

                    // Main looper is the standard GMS pattern for location callbacks.
                    // The callback is lightweight (only resumes a coroutine), so no
                    // main-thread performance concern.
                    fusedLocationClient.requestLocationUpdates(
                        request,
                        callback,
                        Looper.getMainLooper(),
                    )

                    cont.invokeOnCancellation {
                        fusedLocationClient.removeLocationUpdates(callback)
                    }
                }
            }

        private suspend fun <T> Task<T>.await(): T? =
            suspendCancellableCoroutine { cont ->
                addOnSuccessListener { result -> cont.resume(result) }
                addOnFailureListener { e -> cont.resumeWithException(e) }
                addOnCanceledListener { cont.cancel() }
            }

        companion object {
            private const val TAG = "MCP:LocationProvider"
        }
    }
