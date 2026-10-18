package com.danielealbano.androidremotecontrolmcp.services.channel.geofence

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.danielealbano.androidremotecontrolmcp.data.model.GeofenceZone
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class GeofenceManagerImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : GeofenceManager {
        private val geofencingClient = LocationServices.getGeofencingClient(context)

        private val pendingIntent: PendingIntent by lazy {
            val intent = Intent(context, GeofenceTransitionReceiver::class.java)
            PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        }

        // Permission (ACCESS_FINE_LOCATION + ACCESS_BACKGROUND_LOCATION) is declared in manifest
        // and requested at runtime via PermissionsSettingsScreen. SecurityException from missing
        // permission is caught and returned as Result.failure().
        @SuppressWarnings("MissingPermission")
        override suspend fun addGeofence(zone: GeofenceZone): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    var transitionTypes = 0
                    if (zone.notifyOnEnter) {
                        transitionTypes = transitionTypes or Geofence.GEOFENCE_TRANSITION_ENTER
                    }
                    if (zone.notifyOnExit) {
                        transitionTypes = transitionTypes or Geofence.GEOFENCE_TRANSITION_EXIT
                    }

                    val geofence =
                        Geofence
                            .Builder()
                            .setRequestId(zone.id)
                            .setCircularRegion(zone.latitude, zone.longitude, zone.radiusMeters)
                            .setExpirationDuration(Geofence.NEVER_EXPIRE)
                            .setTransitionTypes(transitionTypes)
                            .build()

                    val request =
                        GeofencingRequest
                            .Builder()
                            .setInitialTrigger(
                                if (zone.notifyOnEnter) {
                                    GeofencingRequest.INITIAL_TRIGGER_ENTER
                                } else {
                                    0
                                },
                            ).addGeofence(geofence)
                            .build()

                    geofencingClient.addGeofences(request, pendingIntent).awaitResult()
                    Result.success(Unit)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    Logger.e(TAG, "Failed to add geofence ${zone.id}: ${e.message}")
                    Result.failure(e)
                }
            }

        override suspend fun removeGeofence(zoneId: String): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    geofencingClient.removeGeofences(listOf(zoneId)).awaitResult()
                    Result.success(Unit)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    Logger.e(TAG, "Failed to remove geofence $zoneId: ${e.message}")
                    Result.failure(e)
                }
            }

        override suspend fun removeAllGeofences(): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    geofencingClient.removeGeofences(pendingIntent).awaitResult()
                    Result.success(Unit)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    Logger.e(TAG, "Failed to remove all geofences: ${e.message}")
                    Result.failure(e)
                }
            }

        override suspend fun syncGeofences(zones: List<GeofenceZone>): Result<Unit> {
            removeAllGeofences()
            for (zone in zones) {
                val result = addGeofence(zone)
                if (result.isFailure) return result
            }
            return Result.success(Unit)
        }

        private suspend fun <T> Task<T>.awaitResult(): T =
            suspendCancellableCoroutine { cont ->
                addOnSuccessListener { cont.resume(it) }
                addOnFailureListener { cont.resumeWithException(it) }
            }

        companion object {
            private const val TAG = "MCP:GeofenceManager"
        }
    }
