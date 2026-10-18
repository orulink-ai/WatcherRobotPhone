package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.ControlEffectsConfig
import kotlinx.coroutines.flow.Flow

/** Overlay appearance slice of [SettingsRepository], backed by its existing settings DataStore. */
interface ControlEffectsSettings {
    val controlEffectsConfig: Flow<ControlEffectsConfig>

    suspend fun updateControlEffectsConfig(config: ControlEffectsConfig)
}
