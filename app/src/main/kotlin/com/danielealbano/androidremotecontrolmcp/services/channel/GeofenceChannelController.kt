package com.danielealbano.androidremotecontrolmcp.services.channel

import android.content.Intent
import kotlinx.coroutines.CoroutineScope

/**
 * Flavor seam for geofence event-channel integration. Real implementation in `gms`
 * (drives the geofence listener + config observation); no-op in `foss`.
 */
interface GeofenceChannelController {
    /** Called when the event channel starts; begins observing geofence config and dispatching. */
    fun onChannelStarted(
        dispatcher: EventDispatcher,
        scope: CoroutineScope,
    )

    /** Called when the event channel stops/destroys; tears down geofence monitoring. */
    fun onChannelStopped()

    /** Routes a geofence transition broadcast (delivered to the service) into the listener. */
    fun handleGeofenceIntent(intent: Intent)
}
