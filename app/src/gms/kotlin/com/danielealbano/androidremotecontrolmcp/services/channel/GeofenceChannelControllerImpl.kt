package com.danielealbano.androidremotecontrolmcp.services.channel

import android.content.Context
import android.content.Intent
import com.danielealbano.androidremotecontrolmcp.data.repository.GeofenceConfigRepository
import com.danielealbano.androidremotecontrolmcp.services.channel.geofence.GeofenceManager
import com.danielealbano.androidremotecontrolmcp.services.channel.listeners.GeofenceEventListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * gms implementation of [GeofenceChannelController]. Observes the gms geofence config and drives the
 * [GeofenceEventListener] over the event-channel lifecycle, reproducing the behavior previously inlined
 * in [EventChannelService] (sync on enabled, reconfigure on change, remove on disable, dispatch on
 * transition).
 */
@Singleton
class GeofenceChannelControllerImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val geofenceManager: GeofenceManager,
        private val geofenceConfigRepository: GeofenceConfigRepository,
    ) : GeofenceChannelController {
        private val lock = Any()
        private var scope: CoroutineScope? = null
        private var listener: GeofenceEventListener? = null
        private var observationJob: Job? = null
        private var started = false

        override fun onChannelStarted(
            dispatcher: EventDispatcher,
            scope: CoroutineScope,
        ) {
            synchronized(lock) {
                // Idempotent: tear down any previous session before starting a new one.
                observationJob?.cancel()
                listener?.stop()
                started = false

                this.scope = scope
                val eventListener = GeofenceEventListener(dispatcher, geofenceManager, scope, context)
                listener = eventListener
                observationJob =
                    scope.launch {
                        geofenceConfigRepository.geofenceConfig.collect { config ->
                            when {
                                config.enabled && !started -> {
                                    eventListener.start(config)
                                    started = true
                                }

                                config.enabled && started -> {
                                    eventListener.updateConfig(config)
                                }

                                !config.enabled && started -> {
                                    eventListener.stop()
                                    started = false
                                }
                            }
                        }
                    }
            }
        }

        override fun onChannelStopped() {
            synchronized(lock) {
                observationJob?.cancel()
                observationJob = null
                listener?.stop()
                listener = null
                scope = null
                started = false
            }
        }

        override fun handleGeofenceIntent(intent: Intent) {
            val zoneId = intent.getStringExtra(EventChannelService.EXTRA_GEOFENCE_ZONE_ID) ?: return
            val transition = intent.getStringExtra(EventChannelService.EXTRA_GEOFENCE_TRANSITION) ?: return
            synchronized(lock) {
                // Null-guarded: if the service was restarted directly with the geofence action (so
                // onChannelStarted has not run), this is a safe no-op.
                scope?.launch { listener?.handleTransition(zoneId, transition) }
            }
        }
    }
