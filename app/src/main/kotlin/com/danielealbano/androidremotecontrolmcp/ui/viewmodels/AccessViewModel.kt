package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Access settings screen: the two auth toggles, the bearer token, and the public-URL
 * override. Runs the one-time auth-model migration in [init] before exposing state so the screen never
 * shows un-migrated defaults. Disabling the last remaining auth method emits a confirm-needed event
 * (UNCONDITIONAL — not gated on binding/tunnel); the change applies only after [confirmDisableLastAuth].
 */
@HiltViewModel
class AccessViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _serverConfig = MutableStateFlow(ServerConfig())
        val serverConfig: StateFlow<ServerConfig> = _serverConfig.asStateFlow()

        private val _publicUrlOverrideInput = MutableStateFlow("")
        val publicUrlOverrideInput: StateFlow<String> = _publicUrlOverrideInput.asStateFlow()

        private val _publicUrlOverrideError = MutableStateFlow<String?>(null)
        val publicUrlOverrideError: StateFlow<String?> = _publicUrlOverrideError.asStateFlow()

        private val _showDisableAuthDialog = MutableStateFlow(false)
        val showDisableAuthDialog: StateFlow<Boolean> = _showDisableAuthDialog.asStateFlow()

        private var pendingApply: (suspend () -> Unit)? = null

        init {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.ensureAuthModelMigrated()
                _publicUrlOverrideInput.value = settingsRepository.serverConfig.first().publicUrlOverride
                settingsRepository.serverConfig.collect { _serverConfig.value = it }
            }
        }

        fun requestSetOauthEnabled(enabled: Boolean) {
            if (!enabled && !serverConfig.value.bearerTokenEnabled) {
                pendingApply = { settingsRepository.updateOauthEnabled(false) }
                _showDisableAuthDialog.value = true
            } else {
                viewModelScope.launch(ioDispatcher) { settingsRepository.updateOauthEnabled(enabled) }
            }
        }

        fun requestSetBearerTokenEnabled(enabled: Boolean) {
            if (!enabled && !serverConfig.value.oauthEnabled) {
                pendingApply = { settingsRepository.updateBearerTokenEnabled(false) }
                _showDisableAuthDialog.value = true
            } else {
                viewModelScope.launch(ioDispatcher) { settingsRepository.updateBearerTokenEnabled(enabled) }
            }
        }

        fun confirmDisableLastAuth() {
            val apply = pendingApply
            pendingApply = null
            _showDisableAuthDialog.value = false
            if (apply != null) viewModelScope.launch(ioDispatcher) { apply() }
        }

        fun dismissDisableAuthDialog() {
            pendingApply = null
            _showDisableAuthDialog.value = false
        }

        fun regenerateBearerToken() {
            viewModelScope.launch(ioDispatcher) { settingsRepository.generateNewBearerToken() }
        }

        fun setPublicUrlOverride(url: String) {
            _publicUrlOverrideInput.value = url
            val result = settingsRepository.validatePublicUrlOverride(url)
            if (result.isFailure) {
                _publicUrlOverrideError.value = result.exceptionOrNull()?.message
                return
            }
            _publicUrlOverrideError.value = null
            viewModelScope.launch(ioDispatcher) { settingsRepository.updatePublicUrlOverride(url) }
        }

        fun copyBearerToken(context: Context) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Bearer token", serverConfig.value.bearerToken))
        }
    }
