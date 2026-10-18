<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 63 — Handle Android 14+ partial photo access (limited selection)

**Bug (reproduced on-device)**: when the user answers the photo-permission dialog with "Allow limited access", Android grants `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` as compat session permissions (`checkSelfPermission` = GRANTED, flags `REVOKED_COMPAT|ONE_TIME`) while MediaProvider silently filters every query to the user-selected items + app-owned files. The app therefore displays "All files" while `list_files` returns only the frozen selection. Reported against v1.11.1 ("DCIM rows frozen at a past date"); reproduced on Pixel 8 Pro / Android 16 stable.

**Key platform facts (non-derivable)**:
- Once the app declares `READ_MEDIA_VISUAL_USER_SELECTED`, the compat auto-grant stops: under partial access `checkSelfPermission(READ_MEDIA_IMAGES/VIDEO)` returns DENIED and `READ_MEDIA_VISUAL_USER_SELECTED` returns GRANTED (persistently). Detection is therefore: `USER_SELECTED` granted → partial.
- On API 33 (minSdk) the permission does not exist; its check returns denied → behavior degrades to the current full/owned logic.
- Under partial access MediaStore returns user-selected + owned rows. The app-side owner filter MUST be dropped for visual collections in that state, otherwise the user's selected (non-owned) files become unreadable/unlistable.
- Android's selection covers photos AND videos jointly — no per-type split is possible for visual media. Audio has no partial mode.
- The platform grants/revokes the visual permissions jointly, so a per-type mixed grant (e.g. images granted, videos not) can only arise WITHOUT `USER_SELECTED` (e.g. via adb `pm grant`). After "Allow all", `USER_SELECTED` may remain granted alongside the full permissions — which is why both display-name and access-level logic check the all-granted state BEFORE `hasPartialVisualAccess`. Consequently the enumerated mixed-grant display name is reachable only when `USER_SELECTED` is not granted; if `USER_SELECTED` is granted without full access, "Selected files only"/PARTIAL deliberately takes precedence.

**User decisions (confirmed)**:
- Display wording for partial state: `"<Base> - Selected files only"`.
- Grant button under partial access: single button "Manage access", re-launching the system permission sheet.
- `android_list_storage_locations` gains an `access_level` field on built-in locations only: `"full" | "partial" | "owned_only"`. The pre-existing mixed per-type grant state (e.g. images granted, videos not) reports `"partial"` (display name keeps the existing enumerated form).

---

## User Story 1: Model + manifest — partial-access primitives

Why: `READ_MEDIA_VISUAL_USER_SELECTED` must be declared for the deterministic (non-compat) permission state, and the model needs the access-level vocabulary shared by provider, file-ops, UI, and MCP output.

**Acceptance criteria**:
- [x] Manifest declares `READ_MEDIA_VISUAL_USER_SELECTED`
- [x] `MediaCollection.isVisual` is true exactly for images/video collections
- [x] `BuiltinAccessLevel` enum exists with JSON values `full`/`partial`/`owned_only`
- [x] `StorageLocation.accessLevel` exists, `null` by default (SAF locations)

### Task 1.1: Manifest

**Action 1.1.1** — Modify `app/src/main/AndroidManifest.xml`: after the `READ_MEDIA_AUDIO` uses-permission line, add:

```xml
    <uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />
```

Verification: manifest content has no automated-test infrastructure in this repository (no Robolectric manifest assertions; adding such infrastructure is out of scope) — this action is verified by the Task 6.1 ground-up check.

**Definition of Done**:
- [x] Permission declared in the media-permissions block

### Task 1.2: Model changes

**Action 1.2.1** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/BuiltinStorageLocation.kt`: add to the `MediaCollection` class body (after the `uri` property):

```kotlin
    /** True when this collection is covered by Android's visual-media selection (partial access). */
    val isVisual: Boolean
        get() =
            readMediaPermission == android.Manifest.permission.READ_MEDIA_IMAGES ||
                readMediaPermission == android.Manifest.permission.READ_MEDIA_VIDEO
```

**Action 1.2.2** — Create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/BuiltinAccessLevel.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Read-access level of a built-in MediaStore location, exposed as `access_level`
 * in the `list_storage_locations` MCP output.
 *
 * PARTIAL covers both the Android 14+ visual-media selection (limited access) and
 * a per-type mixed grant (e.g. images granted, videos not).
 */
enum class BuiltinAccessLevel(val jsonValue: String) {
    FULL("full"),
    PARTIAL("partial"),
    OWNED_ONLY("owned_only"),
}
```

**Action 1.2.3** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/StorageLocation.kt`: add a last constructor property and KDoc line:

```kotlin
 * @property accessLevel Read-access level for built-in MediaStore locations; null for SAF locations.
```

```kotlin
    val accessLevel: BuiltinAccessLevel? = null,
```

**Definition of Done**:
- [x] All three model changes in place; existing `StorageLocation` constructions compile unchanged (new property defaulted)

### Task 1.3: Model tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/BuiltinStorageLocationTest.kt` (modify)

| Test | Verifies | Nested class |
|------|----------|--------------|
| `isVisual is true for images and video collections` | `PICTURES.collections[0].isVisual` and `PICTURES.collections[1].isVisual` are true | `CollectionsTest` |
| `isVisual is false for audio and permissionless collections` | `MUSIC.collections[0].isVisual` false; `DOWNLOADS.collections[0].isVisual` false | `CollectionsTest` |

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/BuiltinAccessLevelTest.kt` (create; plain JUnit 5)

| Test | Verifies |
|------|----------|
| `json values match the MCP contract` | `FULL.jsonValue` == `"full"`, `PARTIAL.jsonValue` == `"partial"`, `OWNED_ONLY.jsonValue` == `"owned_only"` |

**Definition of Done**:
- [x] 3 new tests added across the two files

---

## User Story 2: Provider — access level + honest naming

Why: the provider computes the access level for the `StorageLocation` model, alongside the display naming, so UI and MCP output consume one populated value.

**Acceptance criteria**:
- [x] Visual locations show `"<Base> - Selected files only"` when only `READ_MEDIA_VISUAL_USER_SELECTED` is granted
- [x] Audio-only and permissionless locations are unaffected by `USER_SELECTED`
- [x] `StorageLocation.accessLevel` populated for every builtin: FULL / PARTIAL (selection or per-type mix) / OWNED_ONLY; null for SAF

### Task 2.1: StorageLocationProviderImpl

**Action 2.1.1** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/StorageLocationProviderImpl.kt`:

1. Add `import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinAccessLevel`.
2. In `buildBuiltinLocations()`, add `accessLevel = computeAccessLevel(entry),` to the `StorageLocation(...)` construction.
3. Replace `buildBuiltinDisplayName` and add the two new helpers:

```kotlin
        private fun buildBuiltinDisplayName(entry: BuiltinStorageLocation): String {
            val permissioned = entry.collections.filter { it.readMediaPermission != null }
            if (permissioned.isEmpty()) return "${entry.displayBaseName} - Only owned files"
            val granted =
                permissioned.filter { collection ->
                    collection.readMediaPermission?.let(permissionChecker::hasPermission) == true
                }
            return when {
                granted.size == permissioned.size -> {
                    "${entry.displayBaseName} - All files"
                }

                hasPartialVisualAccess(entry) -> {
                    "${entry.displayBaseName} - Selected files only"
                }

                granted.isEmpty() -> {
                    "${entry.displayBaseName} - Only owned files"
                }

                else -> {
                    val grantedLabels = granted.joinToString(", ") { it.typeLabel }
                    val ownedLabels = (permissioned - granted.toSet()).joinToString(", ") { it.typeLabel }
                    "${entry.displayBaseName} - All $grantedLabels, owned $ownedLabels"
                }
            }
        }

        private fun computeAccessLevel(entry: BuiltinStorageLocation): BuiltinAccessLevel {
            val permissioned = entry.collections.filter { it.readMediaPermission != null }
            if (permissioned.isEmpty()) return BuiltinAccessLevel.OWNED_ONLY
            val granted =
                permissioned.filter { collection ->
                    collection.readMediaPermission?.let(permissionChecker::hasPermission) == true
                }
            return when {
                granted.size == permissioned.size -> BuiltinAccessLevel.FULL
                hasPartialVisualAccess(entry) -> BuiltinAccessLevel.PARTIAL
                granted.isEmpty() -> BuiltinAccessLevel.OWNED_ONLY
                else -> BuiltinAccessLevel.PARTIAL
            }
        }

        private fun hasPartialVisualAccess(entry: BuiltinStorageLocation): Boolean =
            entry.collections.any { it.isVisual } &&
                permissionChecker.hasPermission(
                    android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                )
```

Constraint: SAF locations (both construction sites in `getAllLocations`/`getLocationById`) MUST NOT set `accessLevel` (default null).

**Definition of Done**:
- [x] Display name and access level are consistent for every grant state; SAF untouched

### Task 2.2: Provider tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/StorageLocationProviderTest.kt` (modify; same nested class and arrange pattern as the existing builtin naming tests — `getStoredLocations()` returns emptyList, `mockPermissionChecker` stubs; `setUp()` already defaults `hasPermission(any())` to false)

| Test | Verifies | Setup |
|------|----------|-------|
| `pictures name is Selected files only under partial access` | name == `"Pictures - Selected files only"` | `hasPermission(READ_MEDIA_VISUAL_USER_SELECTED)` returns true |
| `dcim name is Selected files only under partial access` | name == `"Camera (DCIM) - Selected files only"` | same |
| `music name unaffected by partial visual access` | name == `"Music - Only owned files"` | `hasPermission(READ_MEDIA_VISUAL_USER_SELECTED)` returns true, audio not granted |
| `access level is full when all permissions granted` | pictures `accessLevel` == `FULL` | `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` granted |
| `full access wins over lingering user selected grant` | pictures `accessLevel` == `FULL` AND name == `"Pictures - All files"` (branch ordering: all-granted checked before `hasPartialVisualAccess`) | `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` + `READ_MEDIA_VISUAL_USER_SELECTED` all granted |
| `access level is partial under visual selection` | pictures `accessLevel` == `PARTIAL` | only `USER_SELECTED` granted |
| `access level is partial on per-type mixed grant` | pictures `accessLevel` == `PARTIAL` | only `READ_MEDIA_IMAGES` granted |
| `access level is owned only without grants` | pictures `accessLevel` == `OWNED_ONLY`; downloads == `OWNED_ONLY` | nothing granted |
| `downloads access level ignores visual selection` | downloads `accessLevel` == `OWNED_ONLY` | `USER_SELECTED` granted |
| `saf location has null access level` | SAF entry `accessLevel` == null | mirror arrange of `getAllLocations returns stored locations with enriched metadata` |

**Definition of Done**:
- [x] 10 new tests added

---

## User Story 3: File operations — read the user's selection

Why: after the manifest change, `READ_MEDIA_IMAGES/VIDEO` report DENIED under partial access; without this change the owner filter would hide the user's selected files entirely.

**Acceptance criteria**:
- [x] Under partial access, listing/reading visual collections drops the app-side owner filter (MediaProvider already scopes rows to selection + owned)
- [x] Audio collections keep the owner filter when only `USER_SELECTED` is granted

### Task 3.1: MediaStoreFileOperationsImpl

**Action 3.1.1** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/MediaStoreFileOperationsImpl.kt`: rename `hasAllFilesAccess` to `hasNonOwnedReadAccess` and extend it (update its two call sites — the `listFiles` per-collection loop and the `findFile` resolution). To avoid a now-misleading name, also rename the `isAllFiles` local in `listFiles` and the `isAllFiles` parameters of `buildListSelection`/`buildListSelectionArgs` to `includeNonOwned` (pure renames, no logic change):

```kotlin
        /**
         * True when MediaStore itself scopes what this app can read in [collection]:
         * either the full read permission is granted, or the user granted a visual-media
         * selection (READ_MEDIA_VISUAL_USER_SELECTED). In both cases the app-side owner
         * filter must be dropped so provider-visible non-owned rows are returned.
         */
        private fun hasNonOwnedReadAccess(collection: MediaCollection): Boolean =
            collection.readMediaPermission?.let { permission ->
                permissionChecker.hasPermission(permission) ||
                    (
                        collection.isVisual &&
                            permissionChecker.hasPermission(
                                android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                            )
                    )
            } == true
```

**Definition of Done**:
- [x] Function renamed and extended; both call sites updated; `isAllFiles` local/parameters renamed to `includeNonOwned`; no logic changed beyond the extension

### Task 3.2: File-operations tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/MediaStoreFileOperationsTest.kt` (modify; reuse existing stubs — `testCollection`, `stubRecordingsCollections`, the all-files-mode capture pattern)

| Test | Verifies | Setup |
|------|----------|-------|
| `listFiles drops owner filter under partial visual access` | selection contains no `OWNER_PACKAGE_NAME` | DOWNLOADS stubbed with a `READ_MEDIA_IMAGES` collection (mirror of `listFiles returns all files in all-files mode`); only `hasPermission(READ_MEDIA_VISUAL_USER_SELECTED)` returns true |
| `listFiles keeps owner filter for audio under partial visual access` | selection contains `OWNER_PACKAGE_NAME` | `stubRecordingsCollections()`; only `hasPermission(READ_MEDIA_VISUAL_USER_SELECTED)` returns true |
| `readFile resolves non-owned file under partial visual access` | read succeeds without owner constraint | mirror of `readFile works in all-files mode for non-owned file`, but granting only `READ_MEDIA_VISUAL_USER_SELECTED` |

**Definition of Done**:
- [x] 3 new tests added

---

## User Story 4: Settings UI — Manage access

Why: under partial access the current button reads "Grant access to all files" against a name claiming "All files"; the user needs a way to expand the selection or upgrade to full access.

**Acceptance criteria**:
- [x] Partial state shows an enabled "Manage access" button; full state shows the disabled granted label; no-grant state shows "Grant access to all files"
- [x] The permission request for visual locations includes `READ_MEDIA_VISUAL_USER_SELECTED` so the system sheet offers selection management

### Task 4.1: String resource

**Action 4.1.1** — Modify `app/src/main/res/values/strings.xml`: after `storage_builtin_all_files_granted`, add:

```xml
    <string name="storage_builtin_manage_access">Manage access</string>
```

**Definition of Done**:
- [x] String resource added after `storage_builtin_all_files_granted`

### Task 4.2: StorageSettingsScreen

**Action 4.2.1** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/settings/StorageSettingsScreen.kt`:

1. Add imports: `androidx.annotation.StringRes`, `com.danielealbano.androidremotecontrolmcp.data.model.BuiltinAccessLevel`.
2. Add file-level (top-level `internal`) pure helpers so the button behavior is unit-testable without Compose test infrastructure:

```kotlin
/** Permissions to request for a builtin location's grant button. */
internal fun builtinRequestPermissions(builtin: BuiltinStorageLocation?): List<String> {
    val readMediaPermissions =
        builtin?.collections?.mapNotNull { it.readMediaPermission }?.distinct().orEmpty()
    return if (builtin?.collections?.any { it.isVisual } == true) {
        readMediaPermissions + android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    } else {
        readMediaPermissions
    }
}

/** The grant button is disabled only when full access is already granted. */
internal fun builtinGrantButtonEnabled(accessLevel: BuiltinAccessLevel?): Boolean = accessLevel != BuiltinAccessLevel.FULL

@StringRes
internal fun builtinGrantButtonLabelRes(accessLevel: BuiltinAccessLevel?): Int =
    when (accessLevel) {
        BuiltinAccessLevel.FULL -> R.string.storage_builtin_all_files_granted
        BuiltinAccessLevel.PARTIAL -> R.string.storage_builtin_manage_access
        else -> R.string.storage_builtin_grant_all_files
    }
```

3. In the `builtinLocations.forEach` block, delete the `hasAllFiles` computation (the direct `ContextCompat.checkSelfPermission` check) and add `val requestPermissions = builtinRequestPermissions(builtin)`.
4. Update the `BuiltinStorageLocationRow` call: replace `hasAllFilesPermission = hasAllFiles` with `accessLevel = location.accessLevel`, and pass `requestPermissions = requestPermissions` (keep `readMediaPermissions` for button visibility); the `onRequestPermission` callback launches `requestPermissions`.
5. Update `BuiltinStorageLocationRow`: replace parameter `hasAllFilesPermission: Boolean` with `accessLevel: BuiltinAccessLevel?`, add `requestPermissions: List<String>`, and replace the button block with:

```kotlin
        if (readMediaPermissions.isNotEmpty()) {
            OutlinedButton(
                onClick = { onRequestPermission(requestPermissions) },
                enabled = builtinGrantButtonEnabled(accessLevel),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(builtinGrantButtonLabelRes(accessLevel)))
            }
        }
```

6. Remove imports that become unused (`ContextCompat`, `PackageManager`) ONLY if no other usage remains in the file.

**Definition of Done**:
- [x] Button state and request list flow through the pure helpers; no unused imports

### Task 4.3: Helper tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/settings/StorageSettingsHelpersTest.kt` (create; plain JUnit 5, no Compose infrastructure — the helpers are pure functions and `R.string` constants are available in unit tests)

| Test | Verifies |
|------|----------|
| `request permissions include user selected for visual locations` | `builtinRequestPermissions(PICTURES)` and `(DCIM)` contain `READ_MEDIA_VISUAL_USER_SELECTED` plus the media permissions |
| `request permissions exclude user selected for non-visual locations` | `builtinRequestPermissions(MUSIC)` == `[READ_MEDIA_AUDIO]`; `(DOWNLOADS)` is empty; `(null)` is empty |
| `grant button enabled unless full access` | `FULL` → false; `PARTIAL`, `OWNED_ONLY`, null → true |
| `grant button label per access level` | `FULL` → `storage_builtin_all_files_granted`; `PARTIAL` → `storage_builtin_manage_access`; `OWNED_ONLY` and null → `storage_builtin_grant_all_files` |

**Definition of Done**:
- [x] 4 new tests added; US4 acceptance criteria covered by automated tests

---

## User Story 5: MCP output + documentation

Why: agents need to detect partial access programmatically (user decision: `access_level` field, builtin locations only).

**Acceptance criteria**:
- [x] `list_storage_locations` entries include `"access_level"` for builtin locations and omit it for SAF locations
- [x] `MCP_TOOLS.md` documents the field and the new name variant

### Task 5.1: FileTools serialization

**Action 5.1.1** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/FileTools.kt`, `ListStorageLocationsHandler.execute`: after `put("allow_delete", location.allowDelete)`, add:

```kotlin
                                    location.accessLevel?.let { level ->
                                        put("access_level", level.jsonValue)
                                    }
```

**Action 5.1.2** — Same file, `register()`: replace the tool description string with:

```kotlin
                description =
                    "Lists available storage locations. Includes built-in locations " +
                        "(always available, no setup required) and user-added locations. " +
                        "Use the location ID from this list for all file operations. " +
                        "Built-in locations report access_level: full, partial (user granted a " +
                        "limited photo/video selection or a per-type subset), or owned_only.",
```

**Definition of Done**:
- [x] `access_level` serialized for builtin locations only; tool description updated

### Task 5.2: Integration tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/FileToolsIntegrationTest.kt` (modify)

| Test | Change |
|------|--------|
| `list_storage_locations includes builtin locations` | Set `accessLevel = BuiltinAccessLevel.OWNED_ONLY` on the mocked builtin `StorageLocation`; add a second mocked SAF `StorageLocation` (no `accessLevel`); assert the response text contains `"access_level":"owned_only"` exactly once and the SAF entry's JSON object has no `access_level` key |

**Definition of Done**:
- [x] Test updated and asserting both presence (builtin) and absence (SAF)

### Task 5.3: MCP_TOOLS.md

**Action 5.3.1** — Modify `docs/MCP_TOOLS.md`, `android_list_storage_locations` section:

1. Extend the description paragraph's name examples with `"Pictures - Selected files only"` and this sentence: partial access (Android 14+ "Allow limited access") means only the user-selected photos/videos plus app-created files are visible.
2. Add to the Response Fields table:

```markdown
| `access_level` | string | Built-in locations only: `"full"`, `"partial"` (limited photo/video selection, or a per-type subset of permissions), or `"owned_only"`. Absent on user-added locations. |
```

**Definition of Done**:
- [x] Both documentation changes in place; table renders with consistent columns

---

## User Story 6: Final verification — ground-up double check

Why: mandatory last step; quality gates run only here (never during implementation).

**Acceptance criteria**:
- [x] Every action of this plan re-verified against the actual code from the ground up
- [x] All quality gates pass

### Task 6.1: Ground-up double check

- [x] Re-read this plan from disk, action by action, and verify each change exists in the code exactly as specified (manifest line, model additions, provider logic, file-ops rename + call sites, UI button states, MCP field, every test in the tables, docs changes)
- [x] Verify NO file outside this plan's scope was modified (`git status` / `git diff --stat`)
- [x] Verify no TODOs, placeholders, or commented-out code were introduced

### Task 6.2: Quality gates

- [x] `make lint` — zero warnings/errors (pipe through `tee` to `/tmp/p63-lint.log`)
- [x] `make test-unit` — full suite passes (pipe through `tee` to `/tmp/p63-test-unit.log`)
- [x] `./gradlew build` — builds without errors or warnings (pipe through `tee` to `/tmp/p63-build.log`)

**Definition of Done**:
- [x] All checks above pass; any failure fixed at the root cause and gates re-run
