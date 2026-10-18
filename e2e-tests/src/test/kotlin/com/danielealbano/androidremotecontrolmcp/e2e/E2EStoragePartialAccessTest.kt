package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E suite for Android 14+ partial photo access ("Allow limited access").
 *
 * Drives the app through the full access-level state machine via `pm grant`/`pm revoke`
 * of READ_MEDIA_IMAGES / READ_MEDIA_VIDEO / READ_MEDIA_VISUAL_USER_SELECTED and verifies
 * `list_storage_locations` access_level + display names, MediaProvider row filtering
 * under partial access (owned + user-selected rows only; no picker selections exist in
 * this suite, so partial access resolves to owned rows), and that writes/reads of owned
 * files keep working in every state.
 *
 * Requires API 34+ (READ_MEDIA_VISUAL_USER_SELECTED does not exist on API 33); the whole
 * class is skipped via [Assumptions.assumeTrue] on older container images.
 *
 * Tests are ordered: each test transitions the permission state machine and later tests
 * depend on the state left by earlier ones. Permission revokes KILL the app process, so
 * transitions use waitForAppProcessDeath + restartServerAndRefreshClient; the baseline
 * state (all media permissions granted, USER_SELECTED revoked) is restored in [tearDown].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class E2EStoragePartialAccessTest {

    companion object {
        private const val TOOL_PREFIX = AndroidContainerSetup.TOOL_NAME_PREFIX
        private const val PICTURES = "builtin:pictures"
        private const val MOVIES = "builtin:movies"
        private const val MUSIC = "builtin:music"
        private const val DCIM = "builtin:dcim"
        private const val DOWNLOADS = "builtin:downloads"
        private const val FIXTURE_DIR = "partial"
        private const val MIN_PARTIAL_ACCESS_API = 34
        private const val TEARDOWN_DEATH_POLL_MS = 5_000L
    }

    private val mcpClient get() = SharedAndroidContainer.mcpClient

    /** True only when the API gate passed and fixtures were set up (guards tearDown). */
    private var active = false

    @BeforeAll
    fun setUpPartialAccessFixtures() {
        // Force shared-container initialization before any adb-based helper below.
        SharedAndroidContainer.ensureAccessibilityService()

        val sdk = AndroidContainerSetup.execAdb("shell", "getprop", "ro.build.version.sdk").trim().toInt()
        Assumptions.assumeTrue(
            sdk >= MIN_PARTIAL_ACCESS_API,
            "Skipping partial-access tests: READ_MEDIA_VISUAL_USER_SELECTED requires API 34+" +
                " (container is API $sdk)",
        )
        active = true

        StorageE2E.grantAllMediaPermissions()
        StorageE2E.configureStorageLocation(PICTURES, allowWrite = true, allowDelete = true)

        // Non-owned fixture: seeded via adb shell (no OWNER_PACKAGE_NAME).
        StorageE2E.seedFile("Pictures/$FIXTURE_DIR/nonowned.jpg", "nonowned-content")
        StorageE2E.scanPath("Pictures/$FIXTURE_DIR/nonowned.jpg")

        // Owned fixture: written through the app while full access is granted.
        writeOwnedFile("$FIXTURE_DIR/owned.jpg", "owned-content")
    }

    @AfterAll
    fun tearDown() {
        if (!active) return
        // Restore the suite baseline: all media permissions granted, USER_SELECTED revoked
        // (a lingering USER_SELECTED grant changes partial-state display names asserted by
        // other test classes). The revoke kills the app process when the permission was
        // granted and is a no-op otherwise, so the death wait below is tolerant.
        StorageE2E.grantAllMediaPermissions()
        StorageE2E.revokeMediaPermission(StorageE2E.PERM_VISUAL_USER_SELECTED)
        waitForPossibleProcessDeath()
        StorageE2E.restartServerAndRefreshClient()
        StorageE2E.removeFixtureTree("Pictures/$FIXTURE_DIR")
        StorageE2E.scanVolume()
        StorageE2E.configureStorageLocation(PICTURES, allowWrite = false, allowDelete = false)
    }

    // ─── shared helpers ─────────────────────────────────────────────────────

    private fun toolText(result: CallToolResult): String =
        (result.content.first() as TextContent).text

    private fun writeOwnedFile(path: String, content: String) = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}write_file",
            mapOf("location_id" to PICTURES, "path" to path, "content" to content),
        )
        check(result.isError != true) { "write_file failed: ${toolText(result)}" }
    }

    private fun storageLocationsById(): Map<String, JsonObject> = runBlocking {
        val result = mcpClient.callTool("${TOOL_PREFIX}list_storage_locations", emptyMap())
        assertNotEquals(true, result.isError, "list_storage_locations failed")
        Json.parseToJsonElement(stripUntrustedWarning(toolText(result)))
            .jsonArray.map { it.jsonObject }
            .associateBy { it["id"]!!.jsonPrimitive.content }
    }

    private fun assertLocationState(
        locations: Map<String, JsonObject>,
        locationId: String,
        expectedAccessLevel: String,
        expectedName: String,
    ) {
        val location = locations[locationId] ?: error("location $locationId missing from listing")
        assertEquals(expectedAccessLevel, location["access_level"]!!.jsonPrimitive.content, locationId)
        assertEquals(expectedName, location["name"]!!.jsonPrimitive.content, locationId)
    }

    private fun listFixtureDirNames(): List<String> = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}list_files",
            mapOf("location_id" to PICTURES, "path" to FIXTURE_DIR, "offset" to 0, "limit" to 200),
        )
        assertNotEquals(true, result.isError, "list_files failed: ${toolText(result)}")
        Json.parseToJsonElement(stripUntrustedWarning(toolText(result))).jsonObject["files"]!!
            .jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
    }

    private fun readFileContent(path: String): String = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}read_file",
            mapOf("location_id" to PICTURES, "path" to path),
        )
        assertNotEquals(true, result.isError, "read_file failed: ${toolText(result)}")
        stripUntrustedWarning(toolText(result))
            .lines()
            .filterNot { it.startsWith("--- More lines available") }
            .joinToString("\n") { it.replace(Regex("^\\d+\\| "), "") }
    }

    /**
     * Tolerant variant of [StorageE2E.waitForAppProcessDeath] for teardown: polls until
     * the app process is gone but does NOT fail if it stays alive (the teardown revoke
     * is a no-op when USER_SELECTED was already revoked, so no kill happens).
     */
    private fun waitForPossibleProcessDeath() {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < TEARDOWN_DEATH_POLL_MS) {
            val pid = try {
                StorageE2E.shell("pidof ${StorageE2E.APP_PACKAGE} || true")
            } catch (_: Exception) {
                ""
            }
            if (pid.isBlank()) return
            Thread.sleep(500)
        }
    }

    // ─── tests ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `baseline full access reports full everywhere`() {
        val locations = storageLocationsById()
        assertLocationState(locations, PICTURES, "full", "Pictures - All files")
        assertLocationState(locations, DCIM, "full", "Camera (DCIM) - All files")
        assertLocationState(locations, DOWNLOADS, "owned_only", "Downloads - Only owned files")
    }

    @Test
    @Order(2)
    fun `lingering user selected grant with full access stays full`() {
        // After "Allow all" in the system dialog, USER_SELECTED can remain granted
        // alongside the full permissions; FULL must win (checked before partial).
        StorageE2E.grantMediaPermission(StorageE2E.PERM_VISUAL_USER_SELECTED)
        val locations = storageLocationsById()
        assertLocationState(locations, PICTURES, "full", "Pictures - All files")
        assertLocationState(locations, DCIM, "full", "Camera (DCIM) - All files")
    }

    @Test
    @Order(3)
    fun `revoking visual permissions with user selected yields partial`() {
        StorageE2E.revokeMediaPermission(StorageE2E.PERM_IMAGES)
        StorageE2E.revokeMediaPermission(StorageE2E.PERM_VIDEO)
        StorageE2E.waitForAppProcessDeath()
        StorageE2E.restartServerAndRefreshClient()

        val locations = storageLocationsById()
        assertLocationState(locations, PICTURES, "partial", "Pictures - Selected files only")
        assertLocationState(locations, DCIM, "partial", "Camera (DCIM) - Selected files only")
        assertLocationState(locations, MOVIES, "partial", "Movies - Selected files only")
        // Non-visual location is untouched by the visual selection state.
        assertLocationState(locations, MUSIC, "full", "Music - All files")
    }

    @Test
    @Order(4)
    fun `partial access lists only owned rows`() {
        // No picker selections exist in this suite, so MediaProvider limits the
        // (owner-unfiltered) query to rows owned by the app.
        val names = listFixtureDirNames()
        assertTrue(names.contains("owned.jpg"), "names=$names")
        assertFalse(names.contains("nonowned.jpg"), "names=$names")
        assertEquals("owned-content", readFileContent("$FIXTURE_DIR/owned.jpg"))
    }

    @Test
    @Order(5)
    fun `partial access read of non owned file fails cleanly`() = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}read_file",
            mapOf("location_id" to PICTURES, "path" to "$FIXTURE_DIR/nonowned.jpg"),
        )
        assertEquals(true, result.isError, "non-owned read must fail under partial access")
        assertTrue(toolText(result).isNotBlank())
    }

    @Test
    @Order(6)
    fun `write works under partial access`() {
        writeOwnedFile("$FIXTURE_DIR/owned2.jpg", "owned-2")
        assertTrue(listFixtureDirNames().contains("owned2.jpg"))
        assertEquals("owned-2", readFileContent("$FIXTURE_DIR/owned2.jpg"))
    }

    @Test
    @Order(7)
    fun `revoking user selected yields owned only`() {
        StorageE2E.revokeMediaPermission(StorageE2E.PERM_VISUAL_USER_SELECTED)
        StorageE2E.waitForAppProcessDeath()
        StorageE2E.restartServerAndRefreshClient()

        val locations = storageLocationsById()
        assertLocationState(locations, PICTURES, "owned_only", "Pictures - Only owned files")
        assertLocationState(locations, MOVIES, "owned_only", "Movies - Only owned files")
        // Owner-filtered query path (includeNonOwned=false) still shows owned rows only.
        val names = listFixtureDirNames()
        assertTrue(names.contains("owned.jpg"), "names=$names")
        assertFalse(names.contains("nonowned.jpg"), "names=$names")
    }

    @Test
    @Order(8)
    fun `regranting full permissions restores non owned visibility`() {
        // pm grant does not kill the process and the app checks permissions live,
        // so no server restart is needed.
        StorageE2E.grantMediaPermission(StorageE2E.PERM_IMAGES)
        StorageE2E.grantMediaPermission(StorageE2E.PERM_VIDEO)

        val locations = storageLocationsById()
        assertLocationState(locations, PICTURES, "full", "Pictures - All files")
        val names = listFixtureDirNames()
        assertTrue(names.contains("nonowned.jpg"), "names=$names")
        assertTrue(names.contains("owned.jpg"), "names=$names")
        assertEquals("nonowned-content", readFileContent("$FIXTURE_DIR/nonowned.jpg"))
    }
}
