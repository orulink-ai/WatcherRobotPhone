package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Auth Failure Logging Integration Tests")
class AuthFailureLoggingIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    @Test
    fun `request with wrong bearer token records AUTH entry`() =
        runTest {
            McpIntegrationTestHelper.withRawTestApplication { deps ->
                val body =
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 1)
                        put("method", "initialize")
                    }

                val response =
                    client.post("/mcp") {
                        header("Authorization", "Bearer wrong-token")
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(JsonObject.serializer(), body))
                    }

                assertEquals(HttpStatusCode.Unauthorized, response.status)
                val authEntries = deps.serverLog.ofType(ServerLogEntry.Type.AUTH)
                assertEquals(1, authEntries.size)
                assertTrue(authEntries.first().message.contains("Authentication failed from"))
            }
        }

    @Test
    fun `request with valid token records no AUTH entry`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, deps ->
                client.listTools()
                assertTrue(deps.serverLog.ofType(ServerLogEntry.Type.AUTH).isEmpty())
            }
        }
}
