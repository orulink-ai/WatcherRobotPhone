package com.danielealbano.androidremotecontrolmcp.services.channel

import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/** foss flavor: geofencing is absent, so the channel controller is inert. */
@Singleton
class NoOpGeofenceChannelController
    @Inject
    constructor() : GeofenceChannelController {
        override fun onChannelStarted(
            dispatcher: EventDispatcher,
            scope: CoroutineScope,
        ) = Unit

        override fun onChannelStopped() = Unit

        override fun handleGeofenceIntent(intent: Intent) = Unit
    }
