package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.data.model.LocationData
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Location Tools Integration Tests")
class LocationToolsIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    @Test
    fun `get_location returns last known location with street`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.locationProvider.getLocation(false)
            } returns
                Result.success(
                    LocationData(
                        latitude = 37.7749,
                        longitude = -122.4194,
                        accuracyMeters = 10.5f,
                        street = "123 Main St, San Francisco, CA",
                    ),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_get_location",
                        arguments = emptyMap(),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("\"latitude\""))
                assertTrue(text.contains("37.7749"))
                assertTrue(text.contains("\"longitude\""))
                assertTrue(text.contains("-122.4194"))
                assertTrue(text.contains("\"accuracy_meters\""))
                assertTrue(text.contains("\"street\""))
                assertTrue(text.contains("123 Main St"))
            }
        }

    @Test
    fun `get_location returns location without street when geocoder fails`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.locationProvider.getLocation(false)
            } returns
                Result.success(
                    LocationData(
                        latitude = 37.7749,
                        longitude = -122.4194,
                        accuracyMeters = 10.5f,
                        street = null,
                    ),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_get_location",
                        arguments = emptyMap(),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("\"latitude\""))
                assertTrue(text.contains("\"street\":null"))
            }
        }

    @Test
    fun `get_location with fresh_fix true calls provider with freshFix true`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.locationProvider.getLocation(true)
            } returns
                Result.success(
                    LocationData(
                        latitude = 37.7749,
                        longitude = -122.4194,
                        accuracyMeters = 5.0f,
                        street = null,
                    ),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_get_location",
                        arguments = mapOf("fresh_fix" to true),
                    )
                assertNotEquals(true, result.isError)
                coVerify { deps.locationProvider.getLocation(true) }
            }
        }

    @Test
    fun `get_location returns permission denied when location permission not granted`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.locationProvider.getLocation(any())
            } returns
                Result.failure(
                    SecurityException("Location permission not granted"),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_get_location",
                        arguments = emptyMap(),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Location permission not granted"))
            }
        }

    @Test
    fun `get_location returns error when play services unavailable`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.locationProvider.getLocation(any())
            } returns
                Result.failure(
                    IllegalStateException("Google Play Services not available"),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_get_location",
                        arguments = emptyMap(),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Google Play Services not available"))
            }
        }

    @Test
    fun `get_location returns error when no location available`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.locationProvider.getLocation(any())
            } returns
                Result.failure(
                    IllegalStateException("No last known location available. Try with fresh_fix=true."),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_get_location",
                        arguments = emptyMap(),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("No last known location available"))
            }
        }

    @Test
    fun `get_location forces fresh_fix false when param disabled`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val perms =
                ToolPermissionsConfig(
                    disabledParams = mapOf("get_location" to setOf("fresh_fix")),
                )
            coEvery {
                deps.locationProvider.getLocation(false)
            } returns
                Result.success(
                    LocationData(
                        latitude = 37.7749,
                        longitude = -122.4194,
                        accuracyMeters = 10.5f,
                        street = null,
                    ),
                )

            McpIntegrationTestHelper.withTestApplication(deps, perms = perms) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_get_location",
                        arguments = mapOf("fresh_fix" to true),
                    )
                assertNotEquals(true, result.isError)
                coVerify { deps.locationProvider.getLocation(false) }
            }
        }

    @Test
    fun `get_location with invalid fresh_fix type returns error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_get_location",
                        arguments = mapOf("fresh_fix" to "yes"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("fresh_fix") || text.contains("invalid") || text.contains("Invalid"))
            }
        }
}
