package com.danielealbano.androidremotecontrolmcp.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("EventChannelConfig")
class EventChannelConfigTest {
    @Nested
    @DisplayName("default values")
    inner class DefaultValues {
        @Test
        fun `default config has channel disabled`() {
            val config = EventChannelConfig()
            assertFalse(config.enabled)
        }

        @Test
        fun `default config has empty endpoint url`() {
            val config = EventChannelConfig()
            assertEquals("", config.endpointUrl)
        }

        @Test
        fun `default config has empty auth token`() {
            val config = EventChannelConfig()
            assertEquals("", config.authToken)
        }

        @Test
        fun `default notification config has ALL filter mode`() {
            val config = EventChannelConfig()
            assertEquals(NotificationFilterMode.ALL, config.notifications.filterMode)
        }

        @Test
        fun `default wifi config has empty ssids`() {
            val config = EventChannelConfig()
            assertTrue(config.wifi.ssids.isEmpty())
        }
    }

    @Nested
    @DisplayName("serialization")
    inner class Serialization {
        @Test
        fun `toJson and fromJson round-trip`() {
            val config =
                EventChannelConfig(
                    enabled = true,
                    endpointUrl = "http://localhost:9090",
                    authToken = "test-token",
                    notifications =
                        NotificationChannelConfig(
                            enabled = true,
                            filterMode = NotificationFilterMode.WHITELIST,
                            filterApps = setOf("com.example.app"),
                        ),
                    wifi =
                        WifiChannelConfig(
                            enabled = true,
                            ssids = setOf("MyWiFi"),
                            notifyOnDiscovered = true,
                            notifyOnLost = false,
                            notifyOnConnected = true,
                            notifyOnDisconnected = false,
                        ),
                )

            val json = config.toJson()
            val deserialized = EventChannelConfig.fromJson(json)
            assertEquals(config, deserialized)
        }

        @Test
        fun `fromJsonOrDefault returns default on invalid JSON`() {
            val config = EventChannelConfig.fromJsonOrDefault("invalid json {{{")
            assertEquals(EventChannelConfig(), config)
        }

        @Test
        fun `notification filter mode serialization`() {
            for (mode in NotificationFilterMode.entries) {
                val config =
                    EventChannelConfig(
                        notifications = NotificationChannelConfig(filterMode = mode),
                    )
                val json = config.toJson()
                val deserialized = EventChannelConfig.fromJson(json)
                assertEquals(mode, deserialized.notifications.filterMode)
            }
        }
    }
}
