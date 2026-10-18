<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 62 — Add `builtin:recordings` storage location

**Issue**: #156. The Audio collection spans `Music/`, `Podcasts/`, `Ringtones/`, `Alarms/`, `Notifications/`, and `Recordings/` (API 31+), but `builtin:music` is scoped to `Music/` only. Voice recordings land in `Recordings/` and are invisible to the builtin locations.

**Scope decision (user-confirmed)**: `Recordings/` only. The remaining Audio directories (`Podcasts/`, `Ringtones/`, `Alarms/`, `Notifications/`) are NOT added.

**Mechanism**: builds on the multi-collection location mechanism from #155 (PR #157). All consumers (`StorageLocationProviderImpl`, `MediaStoreFileOperationsImpl`, `FileOperationProviderImpl`, `StorageSettingsScreen`, DataStore builtin toggles) are generic over `BuiltinStorageLocation.entries` — the ONLY production-code change is one enum entry. `READ_MEDIA_AUDIO` is already declared in the manifest. `minSdk = 33` satisfies the API 31+ requirement for `Recordings/` as an Audio primary directory; MediaStore itself validates the primary directory on insert, so no extra validation code is needed.

---

## User Story 1: Add `RECORDINGS` builtin location

Why: make `Recordings/` audio visible/writable through the builtin MediaStore locations. Appended after `DCIM` so existing entry ordering (and index-based assertions) stay stable.

**Acceptance criteria**:
- [x] `BuiltinStorageLocation.fromLocationId("builtin:recordings")` resolves to `RECORDINGS`
- [x] `getAllLocations()` returns 6 builtin locations, `builtin:recordings` at index 5, before SAF locations
- [x] Display name follows the existing pattern: "Recordings - Only owned files" without `READ_MEDIA_AUDIO`, "Recordings - All files" with it (single collection — no partial-grant variant possible)
- [x] Writes through `builtin:recordings` accept only `audio/*` MIME types; other MIME types rejected with `InvalidParams` listing "audio"

### Task 1.1: Add enum entry

**Action 1.1.1** — Modify `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/BuiltinStorageLocation.kt`: append `RECORDINGS` entry after `DCIM` (before the `;`):

```kotlin
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
```

**Definition of Done**:
- [x] `RECORDINGS` is the last enum entry, after `DCIM`
- [x] No other production code modified

### Task 1.2: Model tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/BuiltinStorageLocationTest.kt` (modify)

| Test | Verifies | Nested class |
|------|----------|--------------|
| `fromLocationId returns RECORDINGS for recordings builtin id` | `fromLocationId("builtin:recordings")` == `RECORDINGS` | `FromLocationIdTest` |
| `RECORDINGS entry has Recordings base path and display name` | `baseRelativePath` == `"Recordings/"`, `displayBaseName` == `"Recordings"` | `CollectionsTest` |
| `RECORDINGS has single audio collection` | `collections.size` == 1; `collections[0]`: `readMediaPermission` == `READ_MEDIA_AUDIO`, `mimeTypePrefix` == `"audio/"`, `typeLabel` == `"audio"` | `CollectionsTest` |

**Definition of Done**:
- [x] 3 new tests added in the existing nested classes, following existing assertion style

### Task 1.3: Provider tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/StorageLocationProviderTest.kt` (modify)

Update existing assertions (builtins count 5 → 6):

| Location | Change |
|----------|--------|
| `getAllLocations returns stored locations with enriched metadata` (assert at former line 148) | `assertEquals(6, result.size)` → `assertEquals(7, result.size)`; comment "5 builtins + 1 SAF location" → "6 builtins + 1 SAF location" |
| `getAllLocations returns empty list when no locations stored` (former line 173) | `assertEquals(5, ...)` → `assertEquals(6, ...)`; comment "only 5 builtins" → "only 6 builtins" |
| `getAllLocations returns locations with null availableBytes when queryAvailableBytes fails` (former line 214) | `assertEquals(6, result.size)` → `assertEquals(7, result.size)`; comment → "6 builtins + 1 SAF location" |
| `getAllLocations returns builtins before SAF locations` (former line 1192) | `assertEquals(6, ...)` → `assertEquals(7, ...)`; add `assertTrue(result[5].isBuiltin)`; SAF assertions shift `result[5]` → `result[6]` |
| `getAllLocations returns five builtin locations` (former line 1205) | Rename to `getAllLocations returns six builtin locations`; `assertEquals(5, ...)` → `assertEquals(6, ...)`; add `assertEquals("builtin:recordings", result[5].id)` |

New tests (same nested class as the existing builtin naming tests; same arrange pattern — `mockSettingsRepository.getStoredLocations()` returns emptyList, `mockPermissionChecker` stubs):

| Test | Verifies | Setup |
|------|----------|-------|
| `recordings name is Only owned files when audio not granted` | `result.find { it.id == "builtin:recordings" }!!.name` == `"Recordings - Only owned files"` | `hasPermission(any())` returns false |
| `recordings name is All files when audio granted` | name == `"Recordings - All files"` | `hasPermission(READ_MEDIA_AUDIO)` returns true |
| `recordings location present with Recordings path` | location found; `path` == `"/Recordings"` | mirror of `dcim location present with Camera display base` |

**Definition of Done**:
- [x] All 5 existing assertion groups updated, 3 new tests added

### Task 1.4: File-operations tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/MediaStoreFileOperationsTest.kt` (modify)

Setup: stub `RECORDINGS` via the existing pattern — `mockkObject(BuiltinStorageLocation.RECORDINGS)` + `every { ...collections } returns listOf(testCollection(fakeCollectionUri, permission = READ_MEDIA_AUDIO, mimePrefix = "audio/", label = "audio"))`. Add `unmockkObject(BuiltinStorageLocation.RECORDINGS)` to `tearDown()`.

| Test | Verifies | Setup |
|------|----------|-------|
| `writeFile rejects non-audio on recordings` | `writeFile("builtin:recordings", "x.txt", "text")` throws `InvalidParams`, message contains "audio", no insert | mirror of `writeFile rejects non-audio on music` |
| `writeFile accepts audio mime on recordings` | write of an `.mp3` file inserts into the stubbed audio collection URI | mirror of `writeFile routes image mime to images collection`, single collection |
| `listFiles lists recordings root` | `listFiles("builtin:recordings", "", 0, 200)` queries the stubbed audio collection and returns rows; RELATIVE_PATH filter arg is `"Recordings/%"` | cursor rows with `RELATIVE_PATH = "Recordings/"`; owner filter active (`hasPermission` false by default) |
| `listFiles returns all recordings in all-files mode` | with `READ_MEDIA_AUDIO` granted, non-owned rows are returned (no owner filter) | `hasPermission(READ_MEDIA_AUDIO)` returns true; mirror of `listFiles returns all files in all-files mode` |
| `readFile reads owned recording content` | `readFile("builtin:recordings", "memo.txt", 1, 200)` returns the stubbed content | mirror of `readFile reads owned file content` (`stubFindOwnedFile` + `stubFileSizeQuery` are URI-agnostic and work with the `RECORDINGS` stub) |

**Definition of Done**:
- [x] 5 new tests added, `tearDown()` unmocks `RECORDINGS`

---

## User Story 2: Document `builtin:recordings`

Why: `docs/MCP_TOOLS.md` publishes the builtin locations table used by MCP clients.

**Acceptance criteria**:
- [x] Built-in locations table lists `builtin:recordings`

### Task 2.1: Update MCP_TOOLS.md

**Action 2.1.1** — Modify `docs/MCP_TOOLS.md`: in the `android_list_storage_locations` "Built-in locations" table, append after the `builtin:dcim` row:

```markdown
| `builtin:recordings` | `Recordings/` | audio | `READ_MEDIA_AUDIO` |
```

No other documentation changes — the MIME-rejection error bullets on write/download/camera tools are already generic.

**Definition of Done**:
- [x] Row added; table renders with consistent columns

---

## User Story 3: Final verification — ground-up double check

Why: mandatory last step; quality gates run only here (never during implementation).

**Acceptance criteria**:
- [x] Every action of this plan re-verified against the actual code from the ground up
- [x] All quality gates pass

### Task 3.1: Ground-up double check

- [x] Re-read this plan from disk, action by action, and verify each change exists in the code exactly as specified (enum entry content and position, every test listed in the tables, every count-assertion update, docs table row)
- [x] Verify NO file outside this plan's scope was modified (`git status` / `git diff --stat`)
- [x] Verify no TODOs, placeholders, or commented-out code were introduced

### Task 3.2: Quality gates

- [x] `make lint` — zero warnings/errors (pipe through `tee` to `/tmp/p62-lint.log`)
- [x] `make test-unit` — full suite passes (pipe through `tee` to `/tmp/p62-test-unit.log`)
- [x] `./gradlew build` — builds without errors or warnings (pipe through `tee` to `/tmp/p62-build.log`)

**Definition of Done**:
- [x] All checks above pass; any failure fixed at the root cause and gates re-run
