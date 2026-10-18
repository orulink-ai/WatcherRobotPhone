package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.GeofenceChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.GeofenceZone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * gms implementation of [GeofenceConfigRepository] backed by the same settings [DataStore] that
 * [SettingsRepositoryImpl] uses (so the one-time migration can read the legacy `event_channel_config`
 * blob), but persisting geofence config under its own dedicated [GEOFENCE_CONFIG_KEY].
 */
@Singleton
class GeofenceConfigRepositoryImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : GeofenceConfigRepository {
        private val json = Json { ignoreUnknownKeys = true }

        override val geofenceConfig: Flow<GeofenceChannelConfig> =
            dataStore.data.map { prefs -> decode(prefs[GEOFENCE_CONFIG_KEY]) }

        override suspend fun getGeofenceConfig(): GeofenceChannelConfig {
            migrateIfNeeded()
            return geofenceConfig.first()
        }

        @Suppress("TooGenericExceptionCaught")
        override suspend fun migrateIfNeeded() {
            val alreadyDone =
                dataStore.data.map { it[GEOFENCE_MIGRATION_DONE_KEY] ?: false }.first()
            if (alreadyDone) return

            dataStore.edit { prefs ->
                // Re-check inside the (serialized) transaction to prevent double-migration.
                if (prefs[GEOFENCE_MIGRATION_DONE_KEY] == true) return@edit
                try {
                    val legacy = prefs[LEGACY_EVENT_CHANNEL_CONFIG_KEY]
                    if (legacy != null) {
                        val geofenceElement = json.parseToJsonElement(legacy).jsonObject["geofence"]
                        if (geofenceElement != null) {
                            val migrated =
                                json.decodeFromJsonElement(
                                    GeofenceChannelConfig.serializer(),
                                    geofenceElement,
                                )
                            if (migrated.enabled || migrated.zones.isNotEmpty()) {
                                prefs[GEOFENCE_CONFIG_KEY] =
                                    json.encodeToString(GeofenceChannelConfig.serializer(), migrated)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Malformed/absent legacy data — nothing to migrate; still mark migration done.
                }
                prefs[GEOFENCE_MIGRATION_DONE_KEY] = true
            }
        }

        override suspend fun updateGeofenceChannelEnabled(enabled: Boolean) = updateGeofenceConfig { it.copy(enabled = enabled) }

        override suspend fun addGeofenceZone(zone: GeofenceZone) = updateGeofenceConfig { it.copy(zones = it.zones + zone) }

        override suspend fun removeGeofenceZone(zoneId: String) =
            updateGeofenceConfig { it.copy(zones = it.zones.filter { z -> z.id != zoneId }) }

        override suspend fun updateGeofenceZone(zone: GeofenceZone) =
            updateGeofenceConfig { it.copy(zones = it.zones.map { z -> if (z.id == zone.id) zone else z }) }

        private suspend fun updateGeofenceConfig(transform: (GeofenceChannelConfig) -> GeofenceChannelConfig) {
            migrateIfNeeded()
            dataStore.edit { prefs ->
                val updated = transform(decode(prefs[GEOFENCE_CONFIG_KEY]))
                prefs[GEOFENCE_CONFIG_KEY] = json.encodeToString(GeofenceChannelConfig.serializer(), updated)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun decode(raw: String?): GeofenceChannelConfig =
            raw?.let {
                try {
                    json.decodeFromString(GeofenceChannelConfig.serializer(), it)
                } catch (e: Exception) {
                    GeofenceChannelConfig()
                }
            } ?: GeofenceChannelConfig()

        companion object {
            private val GEOFENCE_CONFIG_KEY = stringPreferencesKey("geofence_channel_config")
            private val GEOFENCE_MIGRATION_DONE_KEY = booleanPreferencesKey("geofence_config_migrated_v1")

            // Same key string SettingsRepositoryImpl uses for the shared EventChannelConfig blob.
            private val LEGACY_EVENT_CHANNEL_CONFIG_KEY = stringPreferencesKey("event_channel_config")
        }
    }
