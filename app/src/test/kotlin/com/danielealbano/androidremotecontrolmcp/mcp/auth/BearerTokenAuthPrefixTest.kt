package com.danielealbano.androidremotecontrolmcp.mcp.auth

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("BearerTokenAuth /s/ prefix exemption")
class BearerTokenAuthPrefixTest {
    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    private fun Application.installStubs(
        token: String,
        bearerTokenEnabled: Boolean = true,
    ) {
        install(ContentNegotiation) { json() }
        install(McpAuthPlugin) {
            this.bearerTokenEnabled = bearerTokenEnabled
            expectedToken = token
            excludedPaths = setOf("/health")
            excludedPathPrefixes = setOf("/s/")
        }
        routing {
            get("/health") { call.respondText("HEALTH") }
            get("/mcp") { call.respondText("MCP-OK") }
            get("/s/{token}") { call.respondText("S:${call.parameters["token"]}") }
        }
    }

    @Test
    @DisplayName("/s/<token> reachable without Authorization")
    fun sRouteNoAuth() =
        testApplication {
            application { installStubs(TOKEN) }
            val response = client.get("/s/abc123")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("S:abc123", response.bodyAsText())
        }

    @Test
    @DisplayName("/health reachable without Authorization (exact match)")
    fun healthNoAuth() =
        testApplication {
            application { installStubs(TOKEN) }
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
        }

    @Test
    @DisplayName("/mcp requires a valid token")
    fun mcpRequiresToken() =
        testApplication {
            application { installStubs(TOKEN) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/mcp").status)
            val ok = client.get("/mcp") { header("Authorization", "Bearer $TOKEN") }
            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals("MCP-OK", ok.bodyAsText())
        }

    @Test
    @DisplayName("/mcp is not exempted by the /s/ prefix")
    fun mcpNotExemptedByPrefix() =
        testApplication {
            application { installStubs(TOKEN) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/mcp").status)
        }

    @Test
    @DisplayName("encoded traversal under /s/ cannot reach the /mcp handler unauthenticated")
    fun encodedTraversalDoesNotReachMcp() =
        testApplication {
            application { installStubs(TOKEN) }
            val response = client.get("/s/..%2F..%2Fmcp")
            assertNotEquals("MCP-OK", response.bodyAsText(), "must not reach the /mcp handler")
        }

    @Test
    @DisplayName("disabling bearer (both methods off) makes all paths reachable")
    fun bothMethodsOffAllowsAll() =
        testApplication {
            application { installStubs("", bearerTokenEnabled = false) }
            assertEquals(HttpStatusCode.OK, client.get("/s/x").status)
            assertEquals(HttpStatusCode.OK, client.get("/mcp").status)
        }

    private companion object {
        const val TOKEN = "test-token"
    }
}
