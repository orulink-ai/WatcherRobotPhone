package com.danielealbano.androidremotecontrolmcp.services.channel

import com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus
import com.danielealbano.androidremotecontrolmcp.data.model.ChannelEvent
import kotlinx.coroutines.flow.StateFlow

interface EventDispatcher {
    val connectionStatus: StateFlow<ChannelConnectionStatus>

    suspend fun dispatch(event: ChannelEvent): Result<Unit>

    suspend fun healthCheck(): Result<Unit>

    fun start(
        endpointUrl: String,
        authToken: String,
    )

    fun stop()
}
