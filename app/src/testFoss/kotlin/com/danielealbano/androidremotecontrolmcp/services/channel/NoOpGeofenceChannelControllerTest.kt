package com.danielealbano.androidremotecontrolmcp.services.channel

import android.content.Intent
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("NoOpGeofenceChannelController")
class NoOpGeofenceChannelControllerTest {
    @Test
    fun `all methods are inert`() {
        val controller = NoOpGeofenceChannelController()

        assertDoesNotThrow {
            controller.onChannelStarted(mockk(relaxed = true), CoroutineScope(Dispatchers.Unconfined))
            controller.handleGeofenceIntent(mockk<Intent>(relaxed = true))
            controller.onChannelStopped()
        }
    }
}
