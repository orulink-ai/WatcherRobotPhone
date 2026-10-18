package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.PendingApproval
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the on-device approval screen: shows pending requests and approves/denies them. */
@HiltViewModel
class ApprovalViewModel
    @Inject
    constructor(
        private val coordinator: OAuthApprovalCoordinator,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val pending: StateFlow<List<PendingApproval>> = coordinator.observePending()

        fun approve(id: String) {
            viewModelScope.launch(ioDispatcher) { coordinator.approve(id, System.currentTimeMillis()) }
        }

        fun deny(id: String) {
            viewModelScope.launch(ioDispatcher) { coordinator.deny(id) }
        }
    }
