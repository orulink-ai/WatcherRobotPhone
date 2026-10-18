package com.danielealbano.androidremotecontrolmcp.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TunnelStatus")
class TunnelStatusTest {
    @Nested
    @DisplayName("Disconnected")
    inner class DisconnectedTest {
        @Test
        fun `Disconnected is a singleton`() {
            assertSame(TunnelStatus.Disconnected, TunnelStatus.Disconnected)
        }

        @Test
        fun `Disconnected is a TunnelStatus`() {
            assertInstanceOf(TunnelStatus::class.java, TunnelStatus.Disconnected)
        }
    }

    @Nested
    @DisplayName("Connecting")
    inner class ConnectingTest {
        @Test
        fun `Connecting is a singleton`() {
            assertSame(TunnelStatus.Connecting, TunnelStatus.Connecting)
        }

        @Test
        fun `Connecting is a TunnelStatus`() {
            assertInstanceOf(TunnelStatus::class.java, TunnelStatus.Connecting)
        }
    }

    @Nested
    @DisplayName("Connected")
    inner class ConnectedTest {
        @Test
        fun `Connected carries endpoints and providerType`() {
            val status =
                TunnelStatus.Connected(
                    endpoints = listOf(TunnelEndpoint("https://test.trycloudflare.com", valid = true)),
                    providerType = TunnelProviderType.CLOUDFLARE,
                )
            assertEquals(
                listOf(TunnelEndpoint("https://test.trycloudflare.com", valid = true)),
                status.endpoints,
            )
            assertEquals(TunnelProviderType.CLOUDFLARE, status.providerType)
        }

        @Test
        fun `Connected holds multiple endpoints in order with validity`() {
            val status =
                TunnelStatus.Connected(
                    endpoints =
                        listOf(
                            TunnelEndpoint("https://a.example.com", valid = true),
                            TunnelEndpoint("https://b.example.com", valid = false),
                        ),
                    providerType = TunnelProviderType.CLOUDFLARE,
                )
            assertEquals(
                listOf(
                    TunnelEndpoint("https://a.example.com", valid = true),
                    TunnelEndpoint("https://b.example.com", valid = false),
                ),
                status.endpoints,
            )
        }

        @Test
        fun `Connected can hold no endpoints`() {
            val status =
                TunnelStatus.Connected(
                    endpoints = emptyList(),
                    providerType = TunnelProviderType.CLOUDFLARE,
                )
            assertEquals(emptyList<TunnelEndpoint>(), status.endpoints)
        }

        @Test
        fun `Connected equality is based on fields`() {
            val a =
                TunnelStatus.Connected(
                    endpoints = listOf(TunnelEndpoint("https://test.trycloudflare.com", valid = true)),
                    providerType = TunnelProviderType.CLOUDFLARE,
                )
            val b =
                TunnelStatus.Connected(
                    endpoints = listOf(TunnelEndpoint("https://test.trycloudflare.com", valid = true)),
                    providerType = TunnelProviderType.CLOUDFLARE,
                )
            assertEquals(a, b)
        }

        @Test
        fun `Connected with different endpoints are not equal`() {
            val a =
                TunnelStatus.Connected(
                    endpoints = listOf(TunnelEndpoint("https://aaa.trycloudflare.com", valid = true)),
                    providerType = TunnelProviderType.CLOUDFLARE,
                )
            val b =
                TunnelStatus.Connected(
                    endpoints = listOf(TunnelEndpoint("https://bbb.trycloudflare.com", valid = true)),
                    providerType = TunnelProviderType.CLOUDFLARE,
                )
            assertNotEquals(a, b)
        }

        @Test
        fun `Connected with different endpoint validity are not equal`() {
            val a =
                TunnelStatus.Connected(
                    endpoints = listOf(TunnelEndpoint("https://test.example.com", valid = true)),
                    providerType = TunnelProviderType.CLOUDFLARE,
                )
            val b =
                TunnelStatus.Connected(
                    endpoints = listOf(TunnelEndpoint("https://test.example.com", valid = false)),
                    providerType = TunnelProviderType.CLOUDFLARE,
                )
            assertNotEquals(a, b)
        }

        @Test
        fun `Connected with different provider are not equal`() {
            val a =
                TunnelStatus.Connected(
                    endpoints = listOf(TunnelEndpoint("https://test.example.com", valid = true)),
                    providerType = TunnelProviderType.CLOUDFLARE,
                )
            val b =
                TunnelStatus.Connected(
                    endpoints = listOf(TunnelEndpoint("https://test.example.com", valid = true)),
                    providerType = TunnelProviderType.NGROK,
                )
            assertNotEquals(a, b)
        }
    }

    @Nested
    @DisplayName("Error")
    inner class ErrorTest {
        @Test
        fun `Error carries message`() {
            val status = TunnelStatus.Error(message = "Connection refused")
            assertEquals("Connection refused", status.message)
        }

        @Test
        fun `Error equality is based on message`() {
            val a = TunnelStatus.Error(message = "Connection refused")
            val b = TunnelStatus.Error(message = "Connection refused")
            assertEquals(a, b)
        }

        @Test
        fun `Errors with different messages are not equal`() {
            val a = TunnelStatus.Error(message = "Connection refused")
            val b = TunnelStatus.Error(message = "Timeout")
            assertNotEquals(a, b)
        }
    }
}
