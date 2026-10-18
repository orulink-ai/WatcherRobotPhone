package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.ControlEffectsConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject

/** Keeps continuous slider previews local, then saves each completed gesture in order. */
@HiltViewModel
class ControlEffectsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val mutableConfig = MutableStateFlow(ControlEffectsConfig())
        val config = mutableConfig.asStateFlow()
        private val mutableSaveFailed = MutableStateFlow(false)
        val saveFailed = mutableSaveFailed.asStateFlow()
        private val saveMutex = Mutex()
        private var editing = false
        private var editRevision = 0L
        private var latestStored: ControlEffectsConfig? = null
        private var savedConfig: ControlEffectsConfig? = null
        private var savedRevision: Long? = null

        init {
            viewModelScope.launch {
                settingsRepository.controlEffectsConfig.collect { stored ->
                    latestStored = stored
                    if (savedRevision == editRevision && savedConfig == stored) editing = false
                    if (!editing) mutableConfig.value = stored
                }
            }
        }

        fun preview(config: ControlEffectsConfig) {
            editing = true
            editRevision++
            savedConfig = null
            savedRevision = null
            mutableConfig.value = config.normalized()
            mutableSaveFailed.value = false
        }

        fun savePreview() {
            val snapshot = mutableConfig.value
            val snapshotRevision = editRevision
            viewModelScope.launch {
                try {
                    saveMutex.withLock { settingsRepository.updateControlEffectsConfig(snapshot) }
                    if (editRevision == snapshotRevision) {
                        savedConfig = snapshot
                        savedRevision = snapshotRevision
                        if (latestStored == snapshot) editing = false
                        mutableSaveFailed.value = false
                    }
                } catch (_: IOException) {
                    if (editRevision == snapshotRevision) mutableSaveFailed.value = true
                }
            }
        }

        fun restoreDefaults() {
            preview(ControlEffectsConfig())
            savePreview()
        }
    }
