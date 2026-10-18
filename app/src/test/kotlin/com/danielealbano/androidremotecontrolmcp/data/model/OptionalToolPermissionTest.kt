package com.danielealbano.androidremotecontrolmcp.data.model

import com.danielealbano.androidremotecontrolmcp.data.model.OptionalToolPermissions.grantedPermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OptionalToolPermissions")
class OptionalToolPermissionTest {
    private val cameraTools =
        setOf(
            "list_cameras",
            "list_camera_photo_resolutions",
            "list_camera_video_resolutions",
            "take_camera_photo",
            "save_camera_photo",
            "save_camera_video",
        )
    private val notificationTools =
        setOf(
            "notification_list",
            "notification_open",
            "notification_dismiss",
            "notification_snooze",
            "notification_action",
            "notification_reply",
        )
    private val all: Set<OptionalToolPermission> = OptionalToolPermission.entries.toSet()

    @Test
    fun `toolsMissingPermission returns all gated tools when none granted`() {
        val missing = OptionalToolPermissions.toolsMissingPermission(emptySet())
        assertEquals(cameraTools + setOf("get_location") + notificationTools, missing)
        assertEquals(13, missing.size)
    }

    @Test
    fun `toolsMissingPermission returns empty when all granted`() {
        assertTrue(OptionalToolPermissions.toolsMissingPermission(all).isEmpty())
    }

    @Test
    fun `missing camera hides only the 6 camera tools`() {
        val missing = OptionalToolPermissions.toolsMissingPermission(all - OptionalToolPermission.CAMERA)
        assertEquals(cameraTools, missing)
    }

    @Test
    fun `missing location hides only get_location`() {
        val missing = OptionalToolPermissions.toolsMissingPermission(all - OptionalToolPermission.LOCATION)
        assertEquals(setOf("get_location"), missing)
    }

    @Test
    fun `missing notification listener hides only the 6 notification tools`() {
        val missing =
            OptionalToolPermissions.toolsMissingPermission(all - OptionalToolPermission.NOTIFICATION_LISTENER)
        assertEquals(notificationTools, missing)
    }

    @Test
    fun `MICROPHONE gates no tools`() {
        // Removing only MICROPHONE from the granted set must hide nothing at the tool level.
        val missing =
            OptionalToolPermissions.toolsMissingPermission(all - OptionalToolPermission.MICROPHONE)
        assertTrue(missing.isEmpty(), "MICROPHONE must not gate any tool")
        assertFalse(
            OptionalToolPermissions.TOOLS_BY_PERMISSION.containsKey(OptionalToolPermission.MICROPHONE),
            "MICROPHONE must be absent from TOOLS_BY_PERMISSION",
        )
    }

    @Test
    fun `tool names are unique across permissions`() {
        val flattened = OptionalToolPermissions.TOOLS_BY_PERMISSION.values.flatten()
        assertEquals(flattened.size, flattened.toSet().size, "No tool name may appear under two permissions")
    }

    @Test
    fun `paramsMissingPermission returns audio when mic missing`() {
        val missing = OptionalToolPermissions.paramsMissingPermission(all - OptionalToolPermission.MICROPHONE)
        assertEquals(mapOf("save_camera_video" to setOf("audio")), missing)
    }

    @Test
    fun `paramsMissingPermission empty when mic granted`() {
        assertTrue(OptionalToolPermissions.paramsMissingPermission(all).isEmpty())
    }

    @Test
    fun `permissionForTool maps known tools and null otherwise`() {
        assertNull(OptionalToolPermissions.permissionForTool("tap"))
        assertEquals(OptionalToolPermission.CAMERA, OptionalToolPermissions.permissionForTool("take_camera_photo"))
        assertEquals(OptionalToolPermission.LOCATION, OptionalToolPermissions.permissionForTool("get_location"))
        assertEquals(
            OptionalToolPermission.NOTIFICATION_LISTENER,
            OptionalToolPermissions.permissionForTool("notification_list"),
        )
    }

    @Test
    fun `permissionForParam maps audio and null otherwise`() {
        assertEquals(
            OptionalToolPermission.MICROPHONE,
            OptionalToolPermissions.permissionForParam("save_camera_video", "audio"),
        )
        assertNull(OptionalToolPermissions.permissionForParam("save_camera_video", "resolution"))
    }

    private fun granted(
        camera: Boolean = false,
        microphone: Boolean = false,
        location: Boolean = false,
        notificationListener: Boolean = false,
    ): Set<OptionalToolPermission> = grantedPermissions(camera, microphone, location, notificationListener)

    @Test
    fun `grantedPermissions maps booleans to enum set`() {
        assertEquals(setOf(OptionalToolPermission.CAMERA), granted(camera = true))
        assertEquals(setOf(OptionalToolPermission.MICROPHONE), granted(microphone = true))
        assertEquals(setOf(OptionalToolPermission.LOCATION), granted(location = true))
        assertEquals(setOf(OptionalToolPermission.NOTIFICATION_LISTENER), granted(notificationListener = true))
        assertEquals(all, granted(camera = true, microphone = true, location = true, notificationListener = true))
        assertTrue(granted().isEmpty())
    }

    @Test
    fun `effectivePermissions unions disabled tools and merges params`() {
        val stored =
            ToolPermissionsConfig(
                disabledTools = setOf("tap"),
                disabledParams = mapOf("get_screen_state" to setOf("include_screenshot")),
            )
        val granted = all - OptionalToolPermission.CAMERA - OptionalToolPermission.MICROPHONE

        val effective = OptionalToolPermissions.effectivePermissions(stored, granted)

        assertTrue(effective.disabledTools.contains("tap"))
        assertTrue(effective.disabledTools.containsAll(cameraTools))
        assertEquals(setOf("include_screenshot"), effective.disabledParams["get_screen_state"])
        assertEquals(setOf("audio"), effective.disabledParams["save_camera_video"])
    }

    @Test
    fun `effectivePermissions does not mutate stored`() {
        val stored =
            ToolPermissionsConfig(
                disabledTools = setOf("tap"),
                disabledParams = mapOf("get_screen_state" to setOf("include_screenshot")),
            )

        OptionalToolPermissions.effectivePermissions(stored, emptySet())

        assertEquals(setOf("tap"), stored.disabledTools)
        assertEquals(mapOf("get_screen_state" to setOf("include_screenshot")), stored.disabledParams)
    }
}
