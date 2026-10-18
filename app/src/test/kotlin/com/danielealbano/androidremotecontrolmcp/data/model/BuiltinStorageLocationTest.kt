package com.danielealbano.androidremotecontrolmcp.data.model

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("BuiltinStorageLocation")
class BuiltinStorageLocationTest {
    @Nested
    @DisplayName("fromLocationId")
    inner class FromLocationIdTest {
        @Test
        fun `fromLocationId returns correct entry for each builtin`() {
            assertEquals(
                BuiltinStorageLocation.DOWNLOADS,
                BuiltinStorageLocation.fromLocationId("builtin:downloads"),
            )
            assertEquals(
                BuiltinStorageLocation.PICTURES,
                BuiltinStorageLocation.fromLocationId("builtin:pictures"),
            )
            assertEquals(
                BuiltinStorageLocation.MOVIES,
                BuiltinStorageLocation.fromLocationId("builtin:movies"),
            )
            assertEquals(
                BuiltinStorageLocation.MUSIC,
                BuiltinStorageLocation.fromLocationId("builtin:music"),
            )
        }

        @Test
        fun `fromLocationId returns DCIM for dcim builtin id`() {
            assertEquals(
                BuiltinStorageLocation.DCIM,
                BuiltinStorageLocation.fromLocationId("builtin:dcim"),
            )
        }

        @Test
        fun `fromLocationId returns RECORDINGS for recordings builtin id`() {
            assertEquals(
                BuiltinStorageLocation.RECORDINGS,
                BuiltinStorageLocation.fromLocationId("builtin:recordings"),
            )
        }

        @Test
        fun `fromLocationId returns null for unknown ID`() {
            assertNull(BuiltinStorageLocation.fromLocationId("builtin:documents"))
        }
    }

    @Nested
    @DisplayName("isBuiltinId")
    inner class IsBuiltinIdTest {
        @Test
        fun `isBuiltinId returns true for builtin prefix`() {
            assertTrue(BuiltinStorageLocation.isBuiltinId("builtin:downloads"))
        }

        @Test
        fun `isBuiltinId returns false for non-builtin ID`() {
            assertFalse(BuiltinStorageLocation.isBuiltinId("saf:some-uri"))
        }
    }

    @Nested
    @DisplayName("entries")
    inner class EntriesTest {
        @Test
        fun `all entries have unique locationIds`() {
            val locationIds = BuiltinStorageLocation.entries.map { it.locationId }
            assertEquals(locationIds.size, locationIds.toSet().size)
        }
    }

    @Nested
    @DisplayName("collections")
    inner class CollectionsTest {
        @Test
        fun `DCIM entry has DCIM base path and Camera display name`() {
            assertEquals("DCIM/", BuiltinStorageLocation.DCIM.baseRelativePath)
            assertEquals("Camera (DCIM)", BuiltinStorageLocation.DCIM.displayBaseName)
        }

        @Test
        fun `DCIM and PICTURES have images then videos collections`() {
            for (entry in listOf(BuiltinStorageLocation.DCIM, BuiltinStorageLocation.PICTURES)) {
                assertEquals(2, entry.collections.size)
                assertEquals(
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    entry.collections[0].readMediaPermission,
                )
                assertEquals("image/", entry.collections[0].mimeTypePrefix)
                assertEquals(
                    android.Manifest.permission.READ_MEDIA_VIDEO,
                    entry.collections[1].readMediaPermission,
                )
                assertEquals("video/", entry.collections[1].mimeTypePrefix)
            }
        }

        @Test
        fun `DOWNLOADS collection accepts any mime and has no permission`() {
            assertEquals(1, BuiltinStorageLocation.DOWNLOADS.collections.size)
            assertNull(BuiltinStorageLocation.DOWNLOADS.collections[0].mimeTypePrefix)
            assertNull(BuiltinStorageLocation.DOWNLOADS.collections[0].readMediaPermission)
        }

        @Test
        fun `pictures movies music collections have readMediaPermission`() {
            assertNotNull(BuiltinStorageLocation.PICTURES.collections[0].readMediaPermission)
            assertNotNull(BuiltinStorageLocation.MOVIES.collections[0].readMediaPermission)
            assertNotNull(BuiltinStorageLocation.MUSIC.collections[0].readMediaPermission)
        }

        @Test
        fun `RECORDINGS entry has Recordings base path and display name`() {
            assertEquals("Recordings/", BuiltinStorageLocation.RECORDINGS.baseRelativePath)
            assertEquals("Recordings", BuiltinStorageLocation.RECORDINGS.displayBaseName)
        }

        @Test
        fun `RECORDINGS has single audio collection`() {
            assertEquals(1, BuiltinStorageLocation.RECORDINGS.collections.size)
            assertEquals(
                android.Manifest.permission.READ_MEDIA_AUDIO,
                BuiltinStorageLocation.RECORDINGS.collections[0].readMediaPermission,
            )
            assertEquals("audio/", BuiltinStorageLocation.RECORDINGS.collections[0].mimeTypePrefix)
            assertEquals("audio", BuiltinStorageLocation.RECORDINGS.collections[0].typeLabel)
        }

        @Test
        fun `isVisual is true for images and video collections`() {
            assertTrue(BuiltinStorageLocation.PICTURES.collections[0].isVisual)
            assertTrue(BuiltinStorageLocation.PICTURES.collections[1].isVisual)
        }

        @Test
        fun `isVisual is false for audio and permissionless collections`() {
            assertFalse(BuiltinStorageLocation.MUSIC.collections[0].isVisual)
            assertFalse(BuiltinStorageLocation.DOWNLOADS.collections[0].isVisual)
        }
    }

    @Nested
    @DisplayName("validatePath")
    inner class ValidatePathTest {
        @Test
        fun `validatePath accepts valid relative path`() {
            BuiltinStorageLocation.validatePath("subdir/file.txt")
        }

        @Test
        fun `validatePath accepts empty path`() {
            BuiltinStorageLocation.validatePath("")
        }

        @Test
        fun `validatePath rejects double-dot segments`() {
            assertThrows(McpToolException.InvalidParams::class.java) {
                BuiltinStorageLocation.validatePath("../secret")
            }
        }

        @Test
        fun `validatePath rejects single-dot segments`() {
            assertThrows(McpToolException.InvalidParams::class.java) {
                BuiltinStorageLocation.validatePath("./file")
            }
        }

        @Test
        fun `validatePath rejects absolute paths`() {
            assertThrows(McpToolException.InvalidParams::class.java) {
                BuiltinStorageLocation.validatePath("/etc/passwd")
            }
        }

        @Test
        fun `validatePath rejects control characters`() {
            assertThrows(McpToolException.InvalidParams::class.java) {
                BuiltinStorageLocation.validatePath("file\nname")
            }
        }

        @Test
        fun `validatePath rejects nested traversal`() {
            assertThrows(McpToolException.InvalidParams::class.java) {
                BuiltinStorageLocation.validatePath("subdir/../../etc")
            }
        }
    }
}
