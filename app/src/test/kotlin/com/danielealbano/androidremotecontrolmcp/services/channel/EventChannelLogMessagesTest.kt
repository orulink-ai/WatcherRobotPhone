package com.danielealbano.androidremotecontrolmcp.services.channel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Event channel log messages")
class EventChannelLogMessagesTest {
    @Test
    fun `started message contains the endpoint url`() {
        val message = channelStartedLogMessage("http://host:9090")
        assertTrue(message.contains("http://host:9090"))
    }

    @Test
    fun `stopped and failed-start messages are the shared constants`() {
        assertEquals("Event channel stopped", CHANNEL_STOPPED_LOG_MESSAGE)
        assertEquals("Event channel failed to start: endpoint URL is empty", CHANNEL_START_FAILED_LOG_MESSAGE)
    }
}
