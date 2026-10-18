package com.danielealbano.androidremotecontrolmcp.services.channel.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.danielealbano.androidremotecontrolmcp.services.channel.EventChannelService
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * Receives geofence transition events from GeofencingClient.
 *
 * Does NOT look up zones or access repositories. Extracts raw transition
 * data from the GeofencingEvent and forwards it to [EventChannelService]
 * via intent. The service performs the zone lookup and event dispatch.
 */
class GeofenceTransitionReceiver : BroadcastReceiver() {
    @Suppress("ReturnCount")
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Logger.e(TAG, "Geofence error: ${event.errorCode}")
            return
        }

        val transition =
            when (event.geofenceTransition) {
                Geofence.GEOFENCE_TRANSITION_ENTER -> "enter"
                Geofence.GEOFENCE_TRANSITION_EXIT -> "exit"
                else -> return
            }

        for (geofence in event.triggeringGeofences ?: emptyList()) {
            val serviceIntent =
                Intent(context, EventChannelService::class.java).apply {
                    action = EventChannelService.ACTION_GEOFENCE_EVENT
                    putExtra(EventChannelService.EXTRA_GEOFENCE_ZONE_ID, geofence.requestId)
                    putExtra(EventChannelService.EXTRA_GEOFENCE_TRANSITION, transition)
                }
            context.startForegroundService(serviceIntent)
        }
    }

    companion object {
        private const val TAG = "MCP:GeofenceReceiver"
    }
}
