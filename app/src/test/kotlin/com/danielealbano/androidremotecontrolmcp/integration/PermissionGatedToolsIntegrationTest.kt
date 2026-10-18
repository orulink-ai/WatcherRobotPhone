package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.data.model.OptionalToolPermission
import com.danielealbano.androidremotecontrolmcp.data.model.OptionalToolPermissions
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Permission-gated Tools Integration Tests")
class PermissionGatedToolsIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    /** Effective config with only the given optional permissions granted (nothing disabled by the user). */
    private fun permsFor(granted: Set<OptionalToolPermission>): ToolPermissionsConfig =
        OptionalToolPermissions.effectivePermissions(ToolPermissionsConfig(), granted)

    @Test
    fun `camera tools absent from tool list when camera not granted`() =
        runTest {
            val granted = setOf(OptionalToolPermission.LOCATION, OptionalToolPermission.NOTIFICATION_LISTENER)

            McpIntegrationTestHelper.withTestApplication(perms = permsFor(granted)) { client, _ ->
                val toolNames =
                    client
                        .listTools()
                        .tools
                        .map { it.name }
                        .toSet()
                PREFIXED_CAMERA_TOOLS.forEach { name ->
                    assertFalse(toolNames.contains(name), "$name should be hidden when camera not granted")
                }
            }
        }

    @Test
    fun `location tool absent when location not granted`() =
        runTest {
            val granted = ALL - OptionalToolPermission.LOCATION

            McpIntegrationTestHelper.withTestApplication(perms = permsFor(granted)) { client, _ ->
                val toolNames =
                    client
                        .listTools()
                        .tools
                        .map { it.name }
                        .toSet()
                assertFalse(
                    toolNames.contains("android_get_location"),
                    "android_get_location should be hidden when location not granted",
                )
            }
        }

    @Test
    fun `notification tools absent when listener not granted`() =
        runTest {
            val granted = ALL - OptionalToolPermission.NOTIFICATION_LISTENER

            McpIntegrationTestHelper.withTestApplication(perms = permsFor(granted)) { client, _ ->
                val toolNames =
                    client
                        .listTools()
                        .tools
                        .map { it.name }
                        .toSet()
                PREFIXED_NOTIFICATION_TOOLS.forEach { name ->
                    assertFalse(toolNames.contains(name), "$name should be hidden when listener not granted")
                }
            }
        }

    @Test
    fun `all optional tools present when all granted`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication(perms = permsFor(ALL)) { client, _ ->
                val toolNames =
                    client
                        .listTools()
                        .tools
                        .map { it.name }
                        .toSet()
                (PREFIXED_CAMERA_TOOLS + "android_get_location" + PREFIXED_NOTIFICATION_TOOLS).forEach { name ->
                    assertTrue(toolNames.contains(name), "$name should be present when all optional perms granted")
                }
            }
        }

    @Test
    fun `save_camera_video lists audio param when mic granted`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication(perms = permsFor(ALL)) { client, _ ->
                val tool = client.listTools().tools.find { it.name == "android_save_camera_video" }
                assertTrue(tool != null, "android_save_camera_video should be registered")
                assertTrue(
                    tool!!.inputSchema.properties?.containsKey("audio") == true,
                    "audio should be present when mic granted",
                )
            }
        }

    @Test
    fun `save_camera_video omits audio param when mic missing`() =
        runTest {
            val granted =
                setOf(
                    OptionalToolPermission.CAMERA,
                    OptionalToolPermission.LOCATION,
                    OptionalToolPermission.NOTIFICATION_LISTENER,
                )

            McpIntegrationTestHelper.withTestApplication(perms = permsFor(granted)) { client, _ ->
                val tool = client.listTools().tools.find { it.name == "android_save_camera_video" }
                assertTrue(tool != null, "save_camera_video should still be registered when only mic missing")
                assertFalse(
                    tool!!.inputSchema.properties?.containsKey("audio") == true,
                    "audio should be absent from schema when mic not granted",
                )
            }
        }

    companion object {
        private val ALL: Set<OptionalToolPermission> = OptionalToolPermission.entries.toSet()

        private val PREFIXED_CAMERA_TOOLS =
            setOf(
                "android_list_cameras",
                "android_list_camera_photo_resolutions",
                "android_list_camera_video_resolutions",
                "android_take_camera_photo",
                "android_save_camera_photo",
                "android_save_camera_video",
            )

        private val PREFIXED_NOTIFICATION_TOOLS =
            setOf(
                "android_notification_list",
                "android_notification_open",
                "android_notification_dismiss",
                "android_notification_snooze",
                "android_notification_action",
                "android_notification_reply",
            )
    }
}
