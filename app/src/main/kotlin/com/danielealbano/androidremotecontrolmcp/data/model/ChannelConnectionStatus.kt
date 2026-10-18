package com.danielealbano.androidremotecontrolmcp.data.model

sealed class ChannelConnectionStatus {
    data object Idle : ChannelConnectionStatus()

    data object Active : ChannelConnectionStatus()

    data class Error(
        val message: String,
    ) : ChannelConnectionStatus()
}
