package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinAccessLevel
import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinStorageLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("StorageSettings helpers")
class StorageSettingsHelpersTest {
    @Test
    fun `request permissions include user selected for visual locations`() {
        for (entry in listOf(BuiltinStorageLocation.PICTURES, BuiltinStorageLocation.DCIM)) {
            val permissions = builtinRequestPermissions(entry)
            assertTrue(permissions.contains(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
            assertTrue(permissions.contains(android.Manifest.permission.READ_MEDIA_IMAGES))
            assertTrue(permissions.contains(android.Manifest.permission.READ_MEDIA_VIDEO))
        }
    }

    @Test
    fun `request permissions exclude user selected for non-visual locations`() {
        assertEquals(
            listOf(android.Manifest.permission.READ_MEDIA_AUDIO),
            builtinRequestPermissions(BuiltinStorageLocation.MUSIC),
        )
        assertTrue(builtinRequestPermissions(BuiltinStorageLocation.DOWNLOADS).isEmpty())
        assertTrue(builtinRequestPermissions(null).isEmpty())
    }

    @Test
    fun `grant button enabled unless full access`() {
        assertFalse(builtinGrantButtonEnabled(BuiltinAccessLevel.FULL))
        assertTrue(builtinGrantButtonEnabled(BuiltinAccessLevel.PARTIAL))
        assertTrue(builtinGrantButtonEnabled(BuiltinAccessLevel.OWNED_ONLY))
        assertTrue(builtinGrantButtonEnabled(null))
    }

    @Test
    fun `grant button label per access level`() {
        assertEquals(
            R.string.storage_builtin_all_files_granted,
            builtinGrantButtonLabelRes(BuiltinAccessLevel.FULL),
        )
        assertEquals(
            R.string.storage_builtin_manage_access,
            builtinGrantButtonLabelRes(BuiltinAccessLevel.PARTIAL),
        )
        assertEquals(
            R.string.storage_builtin_grant_all_files,
            builtinGrantButtonLabelRes(BuiltinAccessLevel.OWNED_ONLY),
        )
        assertEquals(
            R.string.storage_builtin_grant_all_files,
            builtinGrantButtonLabelRes(null),
        )
    }
}
