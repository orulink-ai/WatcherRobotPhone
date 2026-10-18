package com.danielealbano.androidremotecontrolmcp.data.model

import android.net.Uri
import android.provider.MediaStore
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException

/**
 * A MediaStore collection backing (part of) a built-in storage location.
 *
 * @property readMediaPermission Runtime permission for "all files" access to this
 *   collection's rows, or null if unavailable (owned files only).
 * @property mimeTypePrefix MIME prefix accepted for writes routed to this collection
 *   (e.g. "image/"), or null to accept any MIME type.
 * @property typeLabel Human-readable plural label used in display names and error messages.
 */
class MediaCollection(
    private val collectionUriProvider: () -> Uri,
    val readMediaPermission: String?,
    val mimeTypePrefix: String?,
    val typeLabel: String,
) {
    /** MediaStore collection content URI for queries/inserts. Resolved lazily. */
    val uri: Uri by lazy { collectionUriProvider() }

    /** True when this collection is covered by Android's visual-media selection (partial access). */
    val isVisual: Boolean
        get() =
            readMediaPermission == android.Manifest.permission.READ_MEDIA_IMAGES ||
                readMediaPermission == android.Manifest.permission.READ_MEDIA_VIDEO
}

/**
 * Defines the built-in storage locations backed by MediaStore.
 *
 * These are always available without user setup (no SAF picker needed).
 * The app can write to these locations without any runtime permissions.
 * Reading non-owned files requires the corresponding per-collection
 * [MediaCollection.readMediaPermission].
 *
 * Each location maps to one physical top-level directory and is backed by the
 * MediaStore collections whose content can live there (e.g. `DCIM/` holds both
 * images and videos). Collection URIs are resolved lazily to avoid loading
 * Android framework classes during enum initialization (which would fail in
 * JVM unit tests).
 *
 * @property locationId Stable identifier used in MCP tool calls.
 * @property displayBaseName Base display name; access-level suffix is appended at runtime.
 * @property baseRelativePath The MediaStore RELATIVE_PATH prefix (e.g., "Download/").
 * @property collections Backing MediaStore collections, in read-resolution and
 *   MIME-routing order.
 */
enum class BuiltinStorageLocation(
    val locationId: String,
    val displayBaseName: String,
    val baseRelativePath: String,
    val collections: List<MediaCollection>,
) {
    DOWNLOADS(
        locationId = "builtin:downloads",
        displayBaseName = "Downloads",
        baseRelativePath = "Download/",
        collections =
            listOf(
                MediaCollection(
                    collectionUriProvider = { MediaStore.Downloads.EXTERNAL_CONTENT_URI },
                    readMediaPermission = null,
                    mimeTypePrefix = null,
                    typeLabel = "files",
                ),
            ),
    ),
    PICTURES(
        locationId = "builtin:pictures",
        displayBaseName = "Pictures",
        baseRelativePath = "Pictures/",
        collections =
            listOf(
                MediaCollection(
                    collectionUriProvider = { MediaStore.Images.Media.EXTERNAL_CONTENT_URI },
                    readMediaPermission = android.Manifest.permission.READ_MEDIA_IMAGES,
                    mimeTypePrefix = "image/",
                    typeLabel = "images",
                ),
                MediaCollection(
                    collectionUriProvider = { MediaStore.Video.Media.EXTERNAL_CONTENT_URI },
                    readMediaPermission = android.Manifest.permission.READ_MEDIA_VIDEO,
                    mimeTypePrefix = "video/",
                    typeLabel = "videos",
                ),
            ),
    ),
    MOVIES(
        locationId = "builtin:movies",
        displayBaseName = "Movies",
        baseRelativePath = "Movies/",
        collections =
            listOf(
                MediaCollection(
                    collectionUriProvider = { MediaStore.Video.Media.EXTERNAL_CONTENT_URI },
                    readMediaPermission = android.Manifest.permission.READ_MEDIA_VIDEO,
                    mimeTypePrefix = "video/",
                    typeLabel = "videos",
                ),
            ),
    ),
    MUSIC(
        locationId = "builtin:music",
        displayBaseName = "Music",
        baseRelativePath = "Music/",
        collections =
            listOf(
                MediaCollection(
                    collectionUriProvider = { MediaStore.Audio.Media.EXTERNAL_CONTENT_URI },
                    readMediaPermission = android.Manifest.permission.READ_MEDIA_AUDIO,
                    mimeTypePrefix = "audio/",
                    typeLabel = "audio",
                ),
            ),
    ),
    DCIM(
        locationId = "builtin:dcim",
        displayBaseName = "Camera (DCIM)",
        baseRelativePath = "DCIM/",
        collections =
            listOf(
                MediaCollection(
                    collectionUriProvider = { MediaStore.Images.Media.EXTERNAL_CONTENT_URI },
                    readMediaPermission = android.Manifest.permission.READ_MEDIA_IMAGES,
                    mimeTypePrefix = "image/",
                    typeLabel = "images",
                ),
                MediaCollection(
                    collectionUriProvider = { MediaStore.Video.Media.EXTERNAL_CONTENT_URI },
                    readMediaPermission = android.Manifest.permission.READ_MEDIA_VIDEO,
                    mimeTypePrefix = "video/",
                    typeLabel = "videos",
                ),
            ),
    ),
    RECORDINGS(
        locationId = "builtin:recordings",
        displayBaseName = "Recordings",
        baseRelativePath = "Recordings/",
        collections =
            listOf(
                MediaCollection(
                    collectionUriProvider = { MediaStore.Audio.Media.EXTERNAL_CONTENT_URI },
                    readMediaPermission = android.Manifest.permission.READ_MEDIA_AUDIO,
                    mimeTypePrefix = "audio/",
                    typeLabel = "audio",
                ),
            ),
    ),
    ;

    companion object {
        /** Prefix for all built-in location IDs. */
        const val ID_PREFIX = "builtin:"

        /** Returns the [BuiltinStorageLocation] for a given location ID, or null. */
        fun fromLocationId(locationId: String): BuiltinStorageLocation? = entries.find { it.locationId == locationId }

        /** Returns true if the given location ID is a built-in location. */
        fun isBuiltinId(locationId: String): Boolean = locationId.startsWith(ID_PREFIX)

        /**
         * Validates a relative path for use with built-in locations.
         * Rejects path traversal attempts (`..`), absolute paths, and control characters.
         *
         * @throws McpToolException.InvalidParams if the path is invalid.
         */
        fun validatePath(path: String) {
            val error = findPathValidationError(path)
            if (error != null) {
                throw McpToolException.InvalidParams(error)
            }
        }

        private fun findPathValidationError(path: String): String? =
            when {
                path.startsWith("/") -> {
                    "Path must be relative, not absolute"
                }

                else -> {
                    path.split("/").filter { it.isNotEmpty() }.firstNotNullOfOrNull { segment ->
                        when {
                            segment == ".." -> {
                                "Path must not contain '..' segments"
                            }

                            segment == "." -> {
                                "Path must not contain '.' segments"
                            }

                            CONTROL_CHAR_REGEX.containsMatchIn(segment) -> {
                                "Path must not contain control characters"
                            }

                            else -> {
                                null
                            }
                        }
                    }
                }
            }

        private val CONTROL_CHAR_REGEX = Regex("[\\p{Cntrl}]")
    }
}
