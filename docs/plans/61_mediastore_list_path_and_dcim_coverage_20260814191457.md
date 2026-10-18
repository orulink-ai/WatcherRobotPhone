<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 61 — Fix MediaStore list_files path filtering (#154) and DCIM/multi-collection coverage (#155)

**Issues**: [#154](https://github.com/danielealbano/android-remote-control-mcp/issues/154), [#155](https://github.com/danielealbano/android-remote-control-mcp/issues/155)
**Branch**: `fix/mediastore-list-path-and-dcim-coverage` (from latest `main`). PR closes both issues.

**Agreed decisions (MUST NOT deviate)**:
- Separate builtin location per physical top-level directory; NO unified location.
- New `builtin:dcim` backed by Images + Video collections; `builtin:pictures` extended to Images + Video.
- Writes/creates routed by MIME type; MIME validation is UNIFORM across all builtin locations (Downloads accepts any type). Rejection error is `McpToolException.InvalidParams` listing accepted type labels.
- Read/delete/append/replace resolve files across the location's collections in declaration order (Images before Video).
- All-files visibility is per collection (its own `READ_MEDIA_*` permission). Partial grants degrade gracefully.
- Display names enumerate types on partial grant: `"<Base> - All files"` (all granted), `"<Base> - Only owned files"` (none granted or no permissioned collection), `"<Base> - All <granted labels>, owned <ungranted labels>"` (partial). DCIM base name is `"Camera (DCIM)"`.
- `Recordings/` coverage is OUT OF SCOPE (issue #156, separate PR).

---

## User Story 1 — Fix `list_files` directory path filtering (#154)

Why: `listFiles` reuses `buildRelativePathForDir` (a file-path helper that drops the last segment), so the `path` parameter is ignored for single-segment paths and off-by-one for deeper paths. The `LIKE` argument is also unescaped (`%`/`_` act as wildcards).

Acceptance criteria:
- [x] `listFiles` queries `RELATIVE_PATH LIKE '<base><full path>/%' ESCAPE '\'` with all path segments kept.
- [x] `%`, `_`, and `\` in the target path are escaped in the `LIKE` argument; the trailing `%` wildcard is NOT escaped.
- [x] `buildRelativePathForDir` is unchanged (still correct for its file-path callers).

### Task 1.1 — Listing path builder and LIKE escaping

- [x] **Action 1.1.1** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/MediaStoreFileOperationsImpl.kt`: add the two helpers next to `buildRelativePathForDir`:

```kotlin
/**
 * Builds the MediaStore RELATIVE_PATH for a directory itself (all segments kept).
 * E.g., builtin=PICTURES, path="DCIM/Camera" → "Pictures/DCIM/Camera/"
 * E.g., builtin=PICTURES, path="" → "Pictures/"
 */
private fun buildRelativePathForListing(
    builtin: BuiltinStorageLocation,
    path: String,
): String {
    if (path.isEmpty()) return builtin.baseRelativePath
    val segments = path.split("/").filter { it.isNotEmpty() }
    return "${builtin.baseRelativePath}${segments.joinToString("/")}/"
}

/** Escapes LIKE wildcards so the target path matches literally ('\' MUST be replaced first). */
private fun escapeLikePattern(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
```

- [x] **Action 1.1.2** — Modify same file, `listFiles`: replace `val targetRelativePath = buildRelativePathForDir(builtin, path)` with `val targetRelativePath = buildRelativePathForListing(builtin, path)`.

- [x] **Action 1.1.3** — Modify same file, `buildListSelection` and `buildListSelectionArgs`:

```kotlin
private fun buildListSelection(isAllFiles: Boolean): String {
    val pathFilter = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
    return if (isAllFiles) {
        pathFilter
    } else {
        "$pathFilter AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
    }
}

private fun buildListSelectionArgs(
    targetRelativePath: String,
    isAllFiles: Boolean,
): Array<String> {
    // LIKE pattern: match exact dir and all children; escape the literal prefix only
    val pattern = "${escapeLikePattern(targetRelativePath)}%"
    return if (isAllFiles) arrayOf(pattern) else arrayOf(pattern, context.packageName)
}
```

Definition of Done:
- [x] All three actions applied; no other behavior of `listFiles` changed in this task.

---

## User Story 2 — Multi-collection builtin locations + `builtin:dcim` (#155)

Why: each MediaStore collection spans multiple top-level directories and each directory (DCIM, Pictures) can hold multiple media types. One location per directory, each backed by the collections whose content can live there, makes the camera roll reachable and keeps writes routable.

Acceptance criteria:
- [x] `builtin:dcim` exists (base `DCIM/`, Images + Video, display base `"Camera (DCIM)"`).
- [x] `builtin:pictures` covers Images + Video; `builtin:downloads`/`builtin:movies`/`builtin:music` behavior preserved via single-entry collection lists.
- [x] Listing merges rows from all collections of the location; owner filter applied per collection based on its own permission.
- [x] Read/bytes/delete/append/replace resolve across collections in declaration order.
- [x] Write/create/download route by MIME with uniform validation (`InvalidParams` on mismatch, before any network I/O for downloads).
- [x] Display names follow the agreed three-state enumerated scheme.
- [x] Settings UI grant button requests all missing `READ_MEDIA_*` permissions of the location.

### Task 2.1 — Model: `MediaCollection` + `BuiltinStorageLocation` refactor

- [x] **Action 2.1.1** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/BuiltinStorageLocation.kt`: add `MediaCollection` (same file, above the enum) and replace the enum's `displayNameOwned`/`displayNameAll`/`collectionUriProvider`/`readMediaPermission` properties with `displayBaseName` and `collections`. The companion object (`ID_PREFIX`, `fromLocationId`, `isBuiltinId`, `validatePath`, `findPathValidationError`, `CONTROL_CHAR_REGEX`) is unchanged. Remove the now-unused `collectionUri` lazy val.

```kotlin
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
}
```

Enum entries (declaration order of `collections` is the read-resolution and MIME-routing order):

```kotlin
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
```

Definition of Done:
- [x] Enum has 5 entries; `collectionUri`, `displayNameOwned`, `displayNameAll`, `readMediaPermission` no longer exist on the enum; KDoc updated to describe the new properties.

### Task 2.2 — `MediaStoreFileOperationsImpl`: multi-collection queries and MIME routing

- [x] **Action 2.2.1** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/MediaStoreFileOperationsImpl.kt`, constructor: add `private val permissionChecker: PermissionChecker` parameter (Hilt constructor injection; binding already exists in `AppModule`). Keep `storageLocationProvider` (still used for `isWriteAllowed`/`isDeleteAllowed`). Add `import com.danielealbano.androidremotecontrolmcp.data.model.MediaCollection` to the file's imports.

- [x] **Action 2.2.2** — Add per-collection helpers:

```kotlin
private fun hasAllFilesAccess(collection: MediaCollection): Boolean =
    collection.readMediaPermission?.let { permissionChecker.hasPermission(it) } == true

private fun selectCollectionForMimeType(
    builtin: BuiltinStorageLocation,
    mimeType: String,
): MediaCollection =
    builtin.collections.firstOrNull { collection ->
        collection.mimeTypePrefix == null || mimeType.startsWith(collection.mimeTypePrefix)
    } ?: throw McpToolException.InvalidParams(
        "File type '$mimeType' is not supported by location '${builtin.locationId}'. " +
            "Accepted types: ${builtin.collections.joinToString(", ") { it.typeLabel }}.",
    )

private fun findFileInCollection(
    collection: MediaCollection,
    relativePath: String,
    displayName: String,
    ownedOnly: Boolean,
): Uri? {
    val selection =
        buildString {
            append("${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ")
            append("${MediaStore.MediaColumns.DISPLAY_NAME} = ?")
            if (ownedOnly) append(" AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
        }
    val args =
        if (ownedOnly) {
            arrayOf(relativePath, displayName, context.packageName)
        } else {
            arrayOf(relativePath, displayName)
        }
    return queryForUri(collection.uri, selection, args)
}
```

- [x] **Action 2.2.3** — Rework `listFiles`: remove the `isAllFiles = storageLocationProvider.isAllFilesMode(...)` line; wrap the query in a loop over `builtin.collections`, sharing `entries`/`seenDirs` across iterations (sort/pagination unchanged):

```kotlin
for (collection in builtin.collections) {
    val isAllFiles = hasAllFilesAccess(collection)
    val selection = buildListSelection(isAllFiles)
    val selectionArgs = buildListSelectionArgs(targetRelativePath, isAllFiles)
    context.contentResolver
        .query(collection.uri, projection, selection, selectionArgs, null)
        ?.use { cursor ->
            // existing column-index lookup + processCursorRow loop, unchanged
        }
}
```

- [x] **Action 2.2.4** — Replace the file-resolution helpers `findOwnedFile`/`findAnyFile`/`findFile`/`findFileOrThrow` with cross-collection versions (`processCursorRow`, `queryForUri`, `queryFileSize` unchanged):

```kotlin
private fun findOwnedFile(
    builtin: BuiltinStorageLocation,
    relativePath: String,
    displayName: String,
): Uri? =
    builtin.collections.firstNotNullOfOrNull { collection ->
        findFileInCollection(collection, relativePath, displayName, ownedOnly = true)
    }

private fun findFile(
    builtin: BuiltinStorageLocation,
    relativePath: String,
    displayName: String,
): Uri? =
    builtin.collections.firstNotNullOfOrNull { collection ->
        findFileInCollection(
            collection,
            relativePath,
            displayName,
            ownedOnly = !hasAllFilesAccess(collection),
        )
    }

private fun findFileOrThrow(
    builtin: BuiltinStorageLocation,
    path: String,
): Uri {
    val relativePath = buildRelativePathForDir(builtin, path)
    val displayName = extractDisplayName(path)
    return findFile(builtin, relativePath, displayName)
        ?: throw McpToolException.ActionFailed(
            "File not found: $path in location '${builtin.locationId}'",
        )
}
```

`findFile`/`findFileOrThrow` lose the `locationId` parameter and the `suspend` modifier (no repository call remains) — update call sites in `readFile` and `readFileBytes` to `findFileOrThrow(builtin, path)`. `findOwnedFileOrThrow` keeps its signature.

- [x] **Action 2.2.5** — `writeFile`: after computing `relativePath`/`displayName`, route and use the matched collection for both the existing-file lookup and the insert:

```kotlin
val mimeType = MimeTypeUtils.guessMimeType(displayName)
val collection = selectCollectionForMimeType(builtin, mimeType)
val existingUri = findFileInCollection(collection, relativePath, displayName, ownedOnly = true)
```

Insert path: `MIME_TYPE` value is `mimeType`; `context.contentResolver.insert(collection.uri, values)`.

- [x] **Action 2.2.6** — `createFileUri`: route by the explicit `mimeType` parameter: `val collection = selectCollectionForMimeType(builtin, mimeType)`; existing-file lookup via `findFileInCollection(collection, relativePath, displayName, ownedOnly = true)`; insert into `collection.uri`.

- [x] **Action 2.2.7** — `downloadFromUrl`: immediately after `checkWritePermission(locationId)` and computing `relativePath`/`displayName`, add `val collection = selectCollectionForMimeType(builtin, MimeTypeUtils.guessMimeType(displayName))` (validation fails BEFORE any MediaStore insert or network connection); insert into `collection.uri`; `MIME_TYPE` value from the same guessed type.

- [x] **Action 2.2.8** — `appendFile`, `replaceInFile`, `deleteFile`: no signature changes; they keep using `findOwnedFileOrThrow`, which now resolves across collections via Action 2.2.4.

Definition of Done:
- [x] No reference to `builtin.collectionUri` or `storageLocationProvider.isAllFilesMode` remains in `MediaStoreFileOperationsImpl`; every operation uses the multi-collection model; behavior for single-collection locations is identical except uniform MIME validation.

### Task 2.3 — `StorageLocationProvider`: display naming, remove `isAllFilesMode`

- [x] **Action 2.3.1** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/StorageLocationProvider.kt`: delete the `isAllFilesMode(locationId: String): Boolean` declaration and its KDoc (its only caller was removed in Task 2.2).

- [x] **Action 2.3.2** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/StorageLocationProviderImpl.kt`: delete the `isAllFilesMode` override; in `buildBuiltinLocations`, replace the `allFilesMode`/`displayName` computation with `name = buildBuiltinDisplayName(entry)` and add:

```kotlin
private fun buildBuiltinDisplayName(entry: BuiltinStorageLocation): String {
    val permissioned = entry.collections.filter { it.readMediaPermission != null }
    if (permissioned.isEmpty()) return "${entry.displayBaseName} - Only owned files"
    val granted =
        permissioned.filter { collection ->
            collection.readMediaPermission?.let(permissionChecker::hasPermission) == true
        }
    return when {
        granted.size == permissioned.size -> "${entry.displayBaseName} - All files"
        granted.isEmpty() -> "${entry.displayBaseName} - Only owned files"
        else -> {
            val grantedLabels = granted.joinToString(", ") { it.typeLabel }
            val ownedLabels = (permissioned - granted.toSet()).joinToString(", ") { it.typeLabel }
            "${entry.displayBaseName} - All $grantedLabels, owned $ownedLabels"
        }
    }
}
```

Definition of Done:
- [x] No `isAllFilesMode` anywhere in `main` sources; naming produces the three agreed states.

### Task 2.4 — Settings UI: multi-permission grant button

- [x] **Action 2.4.1** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/settings/StorageSettingsScreen.kt`, `permissionLauncher`: change contract to `ActivityResultContracts.RequestMultiplePermissions()` (callback body unchanged: `{ _ -> viewModel.refreshStorageLocations() }`).

- [x] **Action 2.4.2** — Same file, builtin rows loop: replace the single-permission computation with:

```kotlin
builtinLocations.forEach { location ->
    val builtin = BuiltinStorageLocation.fromLocationId(location.id)
    val readMediaPermissions =
        builtin?.collections?.mapNotNull { it.readMediaPermission }?.distinct().orEmpty()
    val hasAllFiles =
        readMediaPermissions.isNotEmpty() &&
            readMediaPermissions.all {
                ContextCompat.checkSelfPermission(context, it) ==
                    PackageManager.PERMISSION_GRANTED
            }
    BuiltinStorageLocationRow(
        location = location,
        hasAllFilesPermission = hasAllFiles,
        readMediaPermissions = readMediaPermissions,
        onAllowWriteChange = { enabled ->
            viewModel.updateLocationAllowWrite(location.id, enabled)
        },
        onAllowDeleteChange = { enabled ->
            viewModel.updateLocationAllowDelete(location.id, enabled)
        },
        onRequestPermission = { permissions ->
            permissionLauncher.launch(permissions.toTypedArray())
        },
    )
}
```

- [x] **Action 2.4.3** — Same file, `BuiltinStorageLocationRow`: change parameters `readMediaPermission: String?` → `readMediaPermissions: List<String>` and `onRequestPermission: (String) -> Unit` → `onRequestPermission: (List<String>) -> Unit`; button block becomes `if (readMediaPermissions.isNotEmpty())` with `onClick = { onRequestPermission(readMediaPermissions) }` (enabled/label logic unchanged — the button remains enabled until ALL permissions are granted, so partial grants can be completed).

Definition of Done:
- [x] Grant button requests every missing `READ_MEDIA_*` permission of the location in one launch; single-permission locations behave as before; `builtin:dcim` row appears automatically (read-only defaults from `BuiltinPermissions()`).

---

## User Story 3 — Tests

Why: #154 escaped because no listing test used a non-empty path; #155 needs coverage for merge, routing, and per-collection permissions.

Acceptance criteria:
- [x] Every new/changed behavior in US1–US2 has a unit test; all existing tests updated to the new model, none deleted without replacement.

### Task 3.1 — `BuiltinStorageLocationTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/BuiltinStorageLocationTest.kt`

**Setup**: update existing assertions from removed properties (`displayNameOwned`/`displayNameAll`/`readMediaPermission`) to `displayBaseName`/`collections`.

- [x] | Test | Verifies |
      |------|----------|
      | `fromLocationId returns DCIM for builtin:dcim` | New entry resolvable |
      | `DCIM entry has DCIM base path and Camera display name` | `baseRelativePath == "DCIM/"`, `displayBaseName == "Camera (DCIM)"` |
      | `DCIM and PICTURES have images then videos collections` | Order Images→Video; permissions `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`; mime prefixes `image/`/`video/` |
      | `DOWNLOADS collection accepts any mime and has no permission` | `mimeTypePrefix == null`, `readMediaPermission == null` |
      | `all entries have unique locationIds` | Existing test still passes with 5 entries |

### Task 3.2 — `StorageLocationProviderTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/StorageLocationProviderTest.kt`

**Setup**: remove the three `isAllFilesMode` tests (method deleted); mock `PermissionChecker.hasPermission` per permission string. Update the existing assertions that break with 5 builtins and the new naming:
- `getAllLocations returns stored locations with enriched metadata`: total count 5 → 6 (SAF entry remains last).
- `getAllLocations returns empty list when no locations stored`: count 4 → 5.
- `getAllLocations returns locations with null availableBytes...`: count 5 → 6.
- `getAllLocations returns builtins before SAF locations`: size 5 → 6; builtin/SAF boundary index 4 → 5 (`result[4]` is now the DCIM builtin, `result[5]` is SAF).
- `builtin name shows All files when permission granted`: grant BOTH `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` so the PICTURES name is `"Pictures - All files"` (single-permission grant is covered by the new partial-grant test below).

- [x] | Test | Verifies |
      |------|----------|
      | `builtin name is All files when all permissions granted` | PICTURES with both perms granted → `"Pictures - All files"` |
      | `builtin name is Only owned files when no permission granted` | PICTURES with none → `"Pictures - Only owned files"` |
      | `builtin name enumerates types on partial grant` | Images granted, Video not → `"Pictures - All images, owned videos"` |
      | `downloads name is always Only owned files` | No permissioned collection → owned-only name regardless of grants |
      | `dcim location present with Camera display base` | `getAllLocations` contains `builtin:dcim`; name starts with `"Camera (DCIM)"` |
      | `getAllLocations returns five builtin locations` | Count updated from 4 to 5 (plus SAF entries) |

### Task 3.3 — `MediaStoreFileOperationsTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/MediaStoreFileOperationsTest.kt`

**Setup**: add `@MockK PermissionChecker` (default `hasPermission(any()) returns false`) and pass to constructor; add `import com.danielealbano.androidremotecontrolmcp.data.model.MediaCollection`; remove `isAllFilesMode` stubs. Replace `every { BuiltinStorageLocation.DOWNLOADS.collectionUri } returns fakeCollectionUri` with stubbing `collections`; add a helper building real `MediaCollection` instances around fake URIs:

```kotlin
private fun testCollection(
    uri: Uri,
    permission: String? = null,
    mimePrefix: String? = null,
    label: String = "files",
) = MediaCollection({ uri }, permission, mimePrefix, label)
```

For multi-collection cases, `mockkObject(BuiltinStorageLocation.PICTURES)` with `every { BuiltinStorageLocation.PICTURES.collections } returns listOf(imagesCollection, videoCollection)` (two distinct fake URIs); capture `query(...)` selection/args per URI. Every additionally mocked enum entry (PICTURES, MUSIC) MUST be released with `unmockkObject` in `tearDown`. The existing tests `listFiles returns all files in all-files mode` AND `readFile works in all-files mode for non-owned file` (both previously driven by `isAllFilesMode` returning true) MUST be adapted to stub a collection with a `readMediaPermission` and `permissionChecker.hasPermission` returning true for it, so they keep exercising non-owned resolution (with the stub merely removed they would silently stop testing the all-files path).

- [x] Existing `listFiles`/read/write/append/replace/delete/`createFileUri`/`downloadFromUrl` tests: adapt setup only (same assertions).
- [x] | Test | Verifies |
      |------|----------|
      | `listFiles filters by single-segment path` | Captured LIKE arg == `"Download/subdir/%"` for `path="subdir"` (#154 core) |
      | `listFiles filters by nested path` | `path="a/b"` → LIKE arg `"Download/a/b/%"` |
      | `listFiles returns empty for non-matching directory` | Rows with `relPath` outside the target prefix are excluded by the row loop (defense in depth over the SQL filter); result empty. **Setup**: `path="subdir"`, cursor returns rows with `relPath="Download/"` and `relPath="Download/other/"` |
      | `listFiles synthesizes directories under non-root path` | `path="a"`, row `relPath="Download/a/b/"` → dir `b`, path `builtin:downloads/a/b` |
      | `listFiles escapes LIKE wildcards in path` | `path="My_Files"` → LIKE arg `"Download/My\_Files/%"`; selection contains `ESCAPE '\'` |
      | `listFiles escapes percent in path` | `path="100%"` → LIKE arg `"Download/100\%/%"` |
      | `listFiles merges rows from all collections` | PICTURES two-collection stub: file from each collection URI both present |
      | `listFiles dedupes synthesized dirs across collections` | Same subdir from both collections appears once |
      | `listFiles applies owner filter per collection` | Images perm granted, Video not → Images query has no `OWNER_PACKAGE_NAME`, Video query has it |
      | `readFile resolves file from second collection` | Lookup misses Images, hits Video → content read via Video URI |
      | `writeFile routes image mime to images collection` | `x.jpg` on PICTURES → insert on Images URI |
      | `writeFile routes video mime to video collection` | `x.mp4` on PICTURES → insert on Video URI |
      | `writeFile rejects unsupported mime with InvalidParams` | `x.txt` on PICTURES → `InvalidParams` listing `images, videos`; no insert |
      | `writeFile rejects non-audio on music` | Uniform validation: `x.txt` on MUSIC (stubbed audio collection) → `InvalidParams` |
      | `writeFile accepts any mime on downloads` | `x.txt` on DOWNLOADS → insert proceeds (null prefix) |
      | `createFileUri routes by explicit mime type` | `mimeType="video/mp4"` on PICTURES → insert on Video URI |
      | `downloadFromUrl rejects unsupported mime before insert` | Bad extension on PICTURES → `InvalidParams`, `insert` never called |
      | `deleteFile resolves owned file across collections` | Owned lookup misses Images, hits Video → delete on Video-derived URI |

### Task 3.4 — Integration test touch-up

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/FileToolsIntegrationTest.kt`

- [x] Verify (and only if needed, adapt) the builtin-location tests: they mock `FileOperationProvider`/`StorageLocationProvider` interfaces directly, so no change is expected; run confirms.

Definition of Done (US3):
- [x] All tables implemented; `FileOperationProviderTest` (routing-only, interface mocks) requires no changes — confirmed by the full suite run in US5.

---

## User Story 4 — Documentation (`docs/MCP_TOOLS.md`)

Why: the tool docs describe only user-added locations and don't state builtin scope or MIME acceptance.

Acceptance criteria:
- [x] Built-in locations documented with directory scope, content types, and permissions; MIME-rejection error cases documented for the affected tools.

### Task 4.1 — Update tool documentation

- [x] **Action 4.1.1** — `android_list_storage_locations` section: replace the description paragraph with:

> Lists all available storage locations: built-in MediaStore locations (always present, no setup required) and user-added locations granted via the app settings. Use the location ID from this list for all file operations. The `name` of a built-in location reflects the current read-access level per media type, e.g. `"Pictures - All files"`, `"Pictures - Only owned files"`, or `"Pictures - All images, owned videos"` when permissions are partially granted.

and add below it:

```markdown
**Built-in locations**:
| ID | Directory | Content types | "All files" permission(s) |
|----|-----------|---------------|---------------------------|
| `builtin:downloads` | `Download/` | any (owned files only) | — |
| `builtin:pictures` | `Pictures/` | images, videos | `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` |
| `builtin:movies` | `Movies/` | videos | `READ_MEDIA_VIDEO` |
| `builtin:music` | `Music/` | audio | `READ_MEDIA_AUDIO` |
| `builtin:dcim` | `DCIM/` | images, videos (camera roll) | `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` |
```

- [x] **Action 4.1.2** — Add to the **Error Cases** lists of `android_write_file`, `android_download_from_url`, `android_save_camera_photo`, and `android_save_camera_video`:

> - **Invalid params**: File MIME type not accepted by the built-in storage location (e.g. writing a text file to `builtin:pictures`; error lists the accepted types)

Definition of Done:
- [x] Both actions applied; no Mermaid charts involved; no other doc sections touched.

---

## User Story 5 — Quality gates and ground-up verification

Acceptance criteria:
- [x] Lint, full unit+integration test suite, and build pass with zero warnings/errors; entire implementation re-verified against this plan and issues #154/#155.

### Task 5.1 — Quality gates

- [x] **Action 5.1.1** — `make lint 2>&1 | tee /tmp/p61-lint.log | tail -20`; fix ALL findings (no suppressions).
- [x] **Action 5.1.2** — `make test-unit 2>&1 | tee /tmp/p61-test-unit.log | tail -20` and `make test-integration 2>&1 | tee /tmp/p61-test-integration.log | tail -20`; fix ALL failures (including pre-existing unrelated ones per project rules). Inspect the captured logs, never re-run to grep.
- [x] **Action 5.1.3** — `./gradlew build 2>&1 | tee /tmp/p61-build.log | tail -30`; zero errors and zero warnings.

### Task 5.2 — Double check everything implemented, from the ground up (LAST ITEM)

- [x] **Action 5.2.1** — Re-read the FULL diff (`git diff main...HEAD`) file by file and verify, from first principles: every action of this plan applied exactly as written; every acceptance criterion holds; each symptom from issues #154 and #155 is resolved by the code as written (path filtering per segment, LIKE escaping, DCIM reachable for photos AND videos, Pictures shows videos, MIME-routed writes, per-collection all-files, three-state naming, UI multi-permission grant); NO file outside this plan's scope was touched; no TODOs/placeholders/suppressions introduced; all plan checkboxes are `[x]`.

---

## Review Findings (post-implementation code review)

- [x] **WARNING — `MediaStoreDownloader` had no unit tests for its core logic** (HTTP status validation, Content-Length pre-check, mid-stream limit enforcement, IS_PENDING clearing, delete-on-failure cleanup). Resolved: added `MediaStoreDownloaderTest` covering successful stream + IS_PENDING clear, non-2xx status, oversized reported Content-Length, mid-stream limit breach, unopenable destination, and generic-failure wrapping with cause propagation — each verifying the pending-entry cleanup and disconnect behavior. The new test exposed and led to fixing a connection leak introduced by the decomposition (connect() moved back to the orchestrator so a failing connect is still disconnected).
- [x] **INFO — broad suppression cluster on `downloadToPendingUri`**. Resolved: decomposed into `prepareConnection`/`validateResponse`/`streamToDestination`/`markDownloadComplete`; only the justified `TooGenericExceptionCaught` suppression remains (cleanup-on-any-failure of the pending MediaStore entry). Also removed the stale class-level `TooGenericExceptionCaught` on `MediaStoreFileOperationsImpl` and corrected the camera-tool MIME-rejection doc examples in `MCP_TOOLS.md` to scenarios reachable by those tools.
