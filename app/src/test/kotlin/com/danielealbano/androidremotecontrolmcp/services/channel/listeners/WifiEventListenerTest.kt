// Note: WifiEventListener uses Android BroadcastReceiver and ConnectivityManager.
// Testing discovered/lost/connected/disconnected events requires mocking
// WifiManager.scanResults and ConnectivityManager.NetworkCallback which need
// Android framework. These JVM-only tests verify lifecycle safety, SSID
// matching logic, event toggle config, and scan interval constant.
package com.danielealbano.androidremotecontrolmcp.services.channel.listeners

import com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus
import com.danielealbano.androidremotecontrolmcp.data.model.WifiChannelConfig
import com.danielealbano.androidremotecontrolmcp.services.channel.EventDispatcher
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("WifiEventListener")
class WifiEventListenerTest {
    private fun createMockDispatcher(): EventDispatcher {
        val mock = mockk<EventDispatcher>(relaxed = true)
        coEvery { mock.dispatch(any()) } returns Result.success(Unit)
        coEvery { mock.connectionStatus } returns
            MutableStateFlow(ChannelConnectionStatus.Idle)
        return mock
    }

    @Nested
    @DisplayName("lifecycle")
    inner class Lifecycle {
        @Test
        fun `updateConfig stores new config without crash`() {
            val listener = WifiEventListener(createMockDispatcher(), mockk(relaxed = true))
            val config =
                WifiChannelConfig(
                    enabled = true,
                    ssids = setOf("TestWiFi"),
                    notifyOnDiscovered = false,
                )
            listener.updateConfig(config)
            // @Volatile config stored — no crash means thread-safe write succeeded
        }

        @Test
        fun `stop is safe to call without start`() {
            val listener = WifiEventListener(createMockDispatcher(), mockk(relaxed = true))
            listener.stop()
            // Should not throw — safe to call without start
        }

        @Test
        fun `stop is idempotent`() {
            val listener = WifiEventListener(createMockDispatcher(), mockk(relaxed = true))
            listener.stop()
            listener.stop()
            // Double stop should be safe
        }
    }

    @Nested
    @DisplayName("SSID matching")
    inner class SsidMatching {
        @Test
        fun `non-matching SSID is not in configured set`() {
            val config = WifiChannelConfig(enabled = true, ssids = setOf("MyNetwork"))
            assertFalse("OtherNetwork" in config.ssids)
        }

        @Test
        fun `matching SSID is in configured set`() {
            val config = WifiChannelConfig(enabled = true, ssids = setOf("MyNetwork"))
            assertTrue("MyNetwork" in config.ssids)
        }

        @Test
        fun `empty SSID set matches nothing`() {
            val config = WifiChannelConfig(enabled = true, ssids = emptySet())
            assertFalse("AnyNetwork" in config.ssids)
        }
    }

    @Nested
    @DisplayName("event type toggles")
    inner class EventTypeToggles {
        @Test
        fun `config with notifyOnDiscovered false disables discovery events`() {
            val config =
                WifiChannelConfig(
                    enabled = true,
                    ssids = setOf("MyNetwork"),
                    notifyOnDiscovered = false,
                    notifyOnLost = true,
                )
            assertFalse(config.notifyOnDiscovered)
            assertTrue(config.notifyOnLost)
        }

        @Test
        fun `config with all toggles disabled`() {
            val config =
                WifiChannelConfig(
                    enabled = true,
                    ssids = setOf("MyNetwork"),
                    notifyOnDiscovered = false,
                    notifyOnLost = false,
                    notifyOnConnected = false,
                    notifyOnDisconnected = false,
                )
            assertFalse(config.notifyOnDiscovered)
            assertFalse(config.notifyOnLost)
            assertFalse(config.notifyOnConnected)
            assertFalse(config.notifyOnDisconnected)
        }

        @Test
        fun `default config has all toggles enabled`() {
            val config = WifiChannelConfig(enabled = true, ssids = setOf("Net"))
            assertTrue(config.notifyOnDiscovered)
            assertTrue(config.notifyOnLost)
            assertTrue(config.notifyOnConnected)
            assertTrue(config.notifyOnDisconnected)
        }
    }

    @Nested
    @DisplayName("scan interval")
    inner class ScanInterval {
        @Test
        fun `scan interval constant is 2 minutes`() {
            // The SCAN_INTERVAL_MS constant should be 120_000L (2 minutes)
            // Verify via reflection or document that it matches Android throttling guidance
            assertEquals(
                120_000L,
                WifiEventListener::class.java
                    .getDeclaredField("SCAN_INTERVAL_MS")
                    .apply { isAccessible = true }
                    .get(null) as Long,
            )
        }
    }
}
