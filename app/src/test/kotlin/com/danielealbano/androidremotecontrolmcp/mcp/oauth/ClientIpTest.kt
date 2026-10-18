package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import io.ktor.http.RequestConnectionPoint
import io.ktor.http.headersOf
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.ApplicationRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ApplicationCall.clientIp")
class ClientIpTest {
    @BeforeEach
    fun setUp() {
        mockkStatic("io.ktor.server.plugins.OriginConnectionPointKt")
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("io.ktor.server.plugins.OriginConnectionPointKt")
    }

    private fun call(
        cfConnectingIp: String? = null,
        xForwardedFor: String? = null,
        remoteAddress: String = "9.9.9.9",
    ): ApplicationCall {
        val pairs = mutableListOf<Pair<String, List<String>>>()
        cfConnectingIp?.let { pairs += "CF-Connecting-IP" to listOf(it) }
        xForwardedFor?.let { pairs += "X-Forwarded-For" to listOf(it) }

        val request = mockk<ApplicationRequest>()
        every { request.headers } returns headersOf(*pairs.toTypedArray())
        val connectionPoint = mockk<RequestConnectionPoint>()
        every { connectionPoint.remoteAddress } returns remoteAddress
        every { request.origin } returns connectionPoint

        return mockk<ApplicationCall> { every { this@mockk.request } returns request }
    }

    @Test
    @DisplayName("prefers CF-Connecting-IP over X-Forwarded-For and the socket peer")
    fun cfHeaderWins() {
        assertEquals("1.1.1.1", call(cfConnectingIp = "1.1.1.1", xForwardedFor = "2.2.2.2").clientIp())
    }

    @Test
    @DisplayName("uses the first X-Forwarded-For hop when no CF header is present")
    fun xffFirstHop() {
        assertEquals("2.2.2.2", call(xForwardedFor = "2.2.2.2, 3.3.3.3").clientIp())
    }

    @Test
    @DisplayName("falls back to the socket peer when no forwarded header is present")
    fun fallbackToSocket() {
        assertEquals("9.9.9.9", call(remoteAddress = "9.9.9.9").clientIp())
    }

    @Test
    @DisplayName("trims values and ignores blank headers")
    fun trimsAndSkipsBlank() {
        assertEquals("1.1.1.1", call(cfConnectingIp = "  1.1.1.1  ").clientIp())
        // blank forwarded headers are skipped, falling through to the socket peer
        assertEquals("9.9.9.9", call(cfConnectingIp = "  ", xForwardedFor = "  ").clientIp())
    }

    @Test
    @DisplayName("returns null when no header and no socket address are available")
    fun nullWhenNothing() {
        assertNull(call(remoteAddress = "").clientIp())
    }
}
