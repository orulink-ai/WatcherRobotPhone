package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.ControlEffectsConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Delegated by [SettingsRepositoryImpl]; does not create another store or change server config. */
internal class ControlEffectsSettingsImpl(
    private val dataStore: DataStore<Preferences>,
    private val settingsChangeLogger: SettingsChangeLogger,
) : ControlEffectsSettings {
    override val controlEffectsConfig: Flow<ControlEffectsConfig> =
        dataStore.data
            .map { prefs -> ControlEffectsConfig.fromJsonOrDefault(prefs[CONFIG_KEY]) }
            .distinctUntilChanged()

    override suspend fun updateControlEffectsConfig(config: ControlEffectsConfig) {
        val updated = config.normalized().toJson()
        var previous = ""
        dataStore.edit { prefs ->
            previous = ControlEffectsConfig.fromJsonOrDefault(prefs[CONFIG_KEY]).toJson()
            prefs[CONFIG_KEY] = updated
        }
        settingsChangeLogger.submit("control_effects", previous, updated) { _, _ ->
            "Control overlay appearance changed"
        }
    }

    private companion object {
        val CONFIG_KEY = stringPreferencesKey("control_effects_config")
    }
}
