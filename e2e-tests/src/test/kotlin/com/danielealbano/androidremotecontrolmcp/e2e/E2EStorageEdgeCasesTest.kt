package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E Tier 2 edge-case suite: adversarial file/directory-name fixtures, MediaStore
 * provider states (pending/stale/unscanned/duplicate rows), protocol-boundary
 * rejections over real JSON-RPC, concurrency via a second MCP session, and the
 * mid-session permission-revoke lifecycle.
 *
 * Tests are ordered (@Order = plan table row); the revoke test MUST run last
 * because it kills the app process.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class E2EStorageEdgeCasesTest {

    companion object {
        private const val TOOL_PREFIX = AndroidContainerSetup.TOOL_NAME_PREFIX
        private const val DOWNLOADS = "builtin:downloads"
        private const val PICTURES = "builtin:pictures"
    }

    private val mcpClient get() = SharedAndroidContainer.mcpClient

    @BeforeAll
    fun setUpEdgeFixtures() {
        // Force shared-container initialization: the container boots lazily on first
        // access, and every StorageE2E call below requires a booted container (adb).
        SharedAndroidContainer.ensureAccessibilityService()

        StorageE2E.grantAllMediaPermissions()
        StorageE2E.configureStorageLocation(DOWNLOADS, allowWrite = true, allowDelete = true)
        StorageE2E.configureStorageLocation(PICTURES, allowWrite = true, allowDelete = true)

        StorageE2E.seedFile("Pictures/edge/a_b/f1.jpg", "underscore-dir")
        StorageE2E.seedFile("Pictures/edge/a%b/f2.jpg", "percent-dir")
        StorageE2E.seedFile("Pictures/edge/axb/f3.jpg", "x-dir")
        StorageE2E.seedFile("Pictures/edge/sp ace/s.jpg", "space-dir")
        StorageE2E.seedFile("Pictures/edge/ünïcødé/u1.jpg", "unicode-latin")
        StorageE2E.seedFile("Pictures/edge/照片/c1.jpg", "unicode-cjk")
        StorageE2E.seedFile("Pictures/edge/Case/x.jpg", "upper-case-dir")
        StorageE2E.seedFile("Pictures/edge/case/y.jpg", "lower-case-dir")
        StorageE2E.seedFile("Pictures/edge/a/b/c/d/e/deep.jpg", "deep")
        StorageE2E.seedFile("Pictures/edge/.hidden.jpg", "hidden")
        StorageE2E.seedFile("Pictures/edge/report.v2.final.jpg", "multi-dot")
        StorageE2E.seedFile("Pictures/edge/empty.jpg", "")
        StorageE2E.scanVolume()
    }

    @AfterAll
    fun tearDownEdgeFixtures() {
        StorageE2E.grantAllMediaPermissions()
        StorageE2E.restartServerAndRefreshClient()
        StorageE2E.removeFixtureTree("Pictures/edge")
        // Owned leftovers from write/concurrency tests (best-effort)
        StorageE2E.removeFromDisk("Download/out.v1.draft.txt")
        StorageE2E.removeFromDisk("Download/race.txt")
        StorageE2E.removeFromDisk("Download/race (1).txt")
        for (i in 0 until 10) {
            StorageE2E.removeFromDisk("Download/consist$i.txt")
        }
        StorageE2E.scanVolume()
        StorageE2E.configureStorageLocation(DOWNLOADS, allowWrite = false, allowDelete = false)
        StorageE2E.configureStorageLocation(PICTURES, allowWrite = false, allowDelete = false)
    }

    // ─── shared helpers ─────────────────────────────────────────────────────

    private fun toolText(result: CallToolResult): String =
        (result.content.first() as TextContent).text

    private fun callListFiles(
        locationId: String,
        path: String = "",
        offset: Int = 0,
        limit: Int = 200,
    ): JsonObject = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}list_files",
            mapOf("location_id" to locationId, "path" to path, "offset" to offset, "limit" to limit),
        )
        assertNotEquals(true, result.isError, "list_files failed: ${toolText(result)}")
        Json.parseToJsonElement(stripUntrustedWarning(toolText(result))).jsonObject
    }

    private fun fileNames(listing: JsonObject): List<String> =
        listing["files"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    private fun dirNames(listing: JsonObject): List<String> =
        listing["files"]!!.jsonArray
            .filter { it.jsonObject["is_directory"]!!.jsonPrimitive.boolean }
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }

    private fun totalCount(listing: JsonObject): Int = listing["total_count"]!!.jsonPrimitive.int

    private fun readFileContent(locationId: String, path: String): String = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}read_file",
            mapOf("location_id" to locationId, "path" to path),
        )
        assertNotEquals(true, result.isError, "read_file failed: ${toolText(result)}")
        stripUntrustedWarning(toolText(result))
            .lines()
            .filterNot { it.startsWith("--- More lines available") }
            .joinToString("\n") { it.replace(Regex("^\\d+\\| "), "") }
    }

    private fun newSecondClient(): McpClient {
        val client = McpClient(SharedAndroidContainer.mcpServerUrl, AndroidContainerSetup.E2E_BEARER_TOKEN)
        runBlocking { client.connect() }
        return client
    }

    // ─── tests ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `underscore dir does not match percent dir`() {
        assertEquals(listOf("f1.jpg"), fileNames(callListFiles(PICTURES, "edge/a_b")))
        assertEquals(listOf("f2.jpg"), fileNames(callListFiles(PICTURES, "edge/a%b")))
        assertEquals(listOf("f3.jpg"), fileNames(callListFiles(PICTURES, "edge/axb")))
    }

    @Test
    @Order(2)
    fun `directory with space lists and reads`() {
        assertEquals(listOf("s.jpg"), fileNames(callListFiles(PICTURES, "edge/sp ace")))
        assertEquals("space-dir", readFileContent(PICTURES, "edge/sp ace/s.jpg"))
    }

    @Test
    @Order(3)
    fun `unicode directories list and read`() {
        assertEquals(listOf("u1.jpg"), fileNames(callListFiles(PICTURES, "edge/ünïcødé")))
        assertEquals(listOf("c1.jpg"), fileNames(callListFiles(PICTURES, "edge/照片")))
        assertEquals("unicode-cjk", readFileContent(PICTURES, "edge/照片/c1.jpg"))
    }

    @Test
    @Order(4)
    fun `ascii case pair listing`() {
        val upper = fileNames(callListFiles(PICTURES, "edge/Case"))
        val lower = fileNames(callListFiles(PICTURES, "edge/case"))
        // Observed redroid 13 (API 33) and 14 (API 34) behavior — documents the platform, revisit on image upgrade:
        // although SQLite LIKE is ASCII case-insensitive, the listing's exact relative-path
        // comparison keeps case-distinct sibling directories separate — no bleed between them.
        assertEquals(listOf("x.jpg"), upper)
        assertEquals(listOf("y.jpg"), lower)
    }

    @Test
    @Order(5)
    fun `deep nested path resolves`() {
        assertEquals(listOf("deep.jpg"), fileNames(callListFiles(PICTURES, "edge/a/b/c/d/e")))
        assertTrue(dirNames(callListFiles(PICTURES, "edge/a")).contains("b"))
    }

    @Test
    @Order(6)
    fun `hidden dotfile listing`() {
        val names = fileNames(callListFiles(PICTURES, "edge"))
        // Observed redroid 13 (API 33) and 14 (API 34) behavior — documents the platform, revisit on image upgrade:
        // the media scanner skips dotfiles entirely, so .hidden.jpg is never indexed or listed.
        assertFalse(names.contains(".hidden.jpg"), "names=$names")
    }

    @Test
    @Order(7)
    fun `multi dot name round trips`() = runBlocking {
        assertEquals("multi-dot", readFileContent(PICTURES, "edge/report.v2.final.jpg"))
        val write = mcpClient.callTool(
            "${TOOL_PREFIX}write_file",
            mapOf("location_id" to DOWNLOADS, "path" to "out.v1.draft.txt", "content" to "draft-1"),
        )
        assertNotEquals(true, write.isError, toolText(write))
        assertEquals("draft-1", readFileContent(DOWNLOADS, "out.v1.draft.txt"))
    }

    @Test
    @Order(8)
    fun `zero byte file lists and reads empty`() {
        val listing = callListFiles(PICTURES, "edge")
        val entry = listing["files"]!!.jsonArray
            .map { it.jsonObject }
            .find { it["name"]!!.jsonPrimitive.content == "empty.jpg" }
        // Observed redroid 13 (API 33) and 14 (API 34) behavior — documents the platform, revisit on image upgrade:
        // zero-byte files ARE indexed (size=0, mime derived from the extension) and read back empty.
        assertTrue(entry != null, "empty.jpg must be listed")
        assertEquals(0, entry!!["size"]!!.jsonPrimitive.int)
        assertEquals("", readFileContent(PICTURES, "edge/empty.jpg"))
    }

    @Test
    @Order(9)
    fun `duplicate display name resolution`() {
        StorageE2E.insertDuplicateRow("f1.jpg", "Pictures/edge/a_b/")
        // Observed redroid 13 (API 33) and 14 (API 34) behavior — documents the platform, revisit on image upgrade:
        // MediaProvider auto-renames the conflicting insert to "f1 (1).jpg" — true duplicate
        // display names are not creatable; the original row still resolves with its content.
        assertEquals("underscore-dir", readFileContent(PICTURES, "edge/a_b/f1.jpg"))
        val names = fileNames(callListFiles(PICTURES, "edge/a_b"))
        assertTrue(names.contains("f1.jpg"), "names=$names")
        assertTrue(names.contains("f1 (1).jpg"), "names=$names")
    }

    @Test
    @Order(10)
    fun `pending row invisible to listing`() {
        StorageE2E.insertPendingRow("pending.jpg", "Pictures/edge/")
        assertFalse(fileNames(callListFiles(PICTURES, "edge")).contains("pending.jpg"))
    }

    @Test
    @Order(11)
    fun `stale row read fails cleanly`() = runBlocking {
        StorageE2E.removeFromDisk("Pictures/edge/report.v2.final.jpg")
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}read_file",
            mapOf("location_id" to PICTURES, "path" to "edge/report.v2.final.jpg"),
        )
        assertEquals(true, result.isError, "reading a stale row must fail cleanly")
        assertTrue(toolText(result).isNotBlank())
        StorageE2E.scanPath("Pictures/edge/report.v2.final.jpg")
        assertFalse(fileNames(callListFiles(PICTURES, "edge")).contains("report.v2.final.jpg"))
    }

    @Test
    @Order(12)
    fun `unscanned file invisible until scan`() {
        StorageE2E.seedFile("Pictures/edge/unscanned.jpg", "unscanned")
        assertFalse(fileNames(callListFiles(PICTURES, "edge")).contains("unscanned.jpg"))
        StorageE2E.scanPath("Pictures/edge/unscanned.jpg")
        assertTrue(fileNames(callListFiles(PICTURES, "edge")).contains("unscanned.jpg"))
    }

    @Test
    @Order(13)
    fun `path traversal rejected end to end`() = runBlocking {
        for (badPath in listOf("../Music", "/etc", "bad\npath")) {
            val result = mcpClient.callTool(
                "${TOOL_PREFIX}list_files",
                mapOf("location_id" to PICTURES, "path" to badPath),
            )
            assertEquals(true, result.isError, "path '$badPath' must be rejected")
        }
    }

    @Test
    @Order(14)
    fun `missing or wrongly typed params rejected`() = runBlocking {
        val missing = mcpClient.callTool("${TOOL_PREFIX}list_files", mapOf("path" to ""))
        assertEquals(true, missing.isError)
        val wrongType = mcpClient.callTool(
            "${TOOL_PREFIX}list_files",
            mapOf("location_id" to PICTURES, "path" to 42),
        )
        assertEquals(true, wrongType.isError)
    }

    @Test
    @Order(15)
    fun `unknown location id rejected`() = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}list_files",
            mapOf("location_id" to "builtin:nope", "path" to ""),
        )
        assertEquals(true, result.isError)
        assertTrue(toolText(result).contains("not found", ignoreCase = true), toolText(result))
    }

    @Test
    @Order(16)
    fun `negative offset and zero limit`() = runBlocking<Unit> {
        val negOffset = mcpClient.callTool(
            "${TOOL_PREFIX}list_files",
            mapOf("location_id" to PICTURES, "path" to "edge", "offset" to -1),
        )
        val zeroLimit = mcpClient.callTool(
            "${TOOL_PREFIX}list_files",
            mapOf("location_id" to PICTURES, "path" to "edge", "limit" to 0),
        )
        // Observed redroid 13 (API 33) and 14 (API 34) behavior — documents the platform, revisit on image upgrade:
        // a negative offset fails cleanly ("Requested element count -1 is less than zero");
        // limit=0 succeeds and returns an empty page with has_more=true.
        assertEquals(true, negOffset.isError)
        assertTrue(toolText(negOffset).contains("less than zero"), toolText(negOffset))
        assertNotEquals(true, zeroLimit.isError)
        val listing = Json.parseToJsonElement(stripUntrustedWarning(toolText(zeroLimit))).jsonObject
        assertEquals(0, fileNames(listing).size)
        assertTrue(listing["has_more"]!!.jsonPrimitive.boolean)
    }

    @Test
    @Order(17)
    fun `parallel writes to same filename`() = runBlocking<Unit> {
        val second = newSecondClient()
        try {
            val a = async(Dispatchers.IO) {
                mcpClient.callTool(
                    "${TOOL_PREFIX}write_file",
                    mapOf("location_id" to DOWNLOADS, "path" to "race.txt", "content" to "writer-a"),
                )
            }
            val b = async(Dispatchers.IO) {
                second.callTool(
                    "${TOOL_PREFIX}write_file",
                    mapOf("location_id" to DOWNLOADS, "path" to "race.txt", "content" to "writer-b"),
                )
            }
            val resultA = a.await()
            val resultB = b.await()
            // Observed redroid 13 (API 33) and 14 (API 34) behavior — documents the platform, revisit on image upgrade:
            // both writers succeed without transport errors; depending on interleaving the second
            // writer either overwrites the first's owned row (1 file) or MediaStore auto-renames
            // its insert (2 files) — never a crash or corrupted listing.
            assertNotEquals(true, resultA.isError, toolText(resultA))
            assertNotEquals(true, resultB.isError, toolText(resultB))
            val raceCount = fileNames(callListFiles(DOWNLOADS)).count { it.startsWith("race") }
            assertTrue(raceCount in 1..2, "raceCount=$raceCount")
        } finally {
            second.close()
        }
    }

    @Test
    @Order(18)
    fun `delete during read does not break server`() = runBlocking {
        val write = mcpClient.callTool(
            "${TOOL_PREFIX}write_file",
            mapOf("location_id" to DOWNLOADS, "path" to "victim.txt", "content" to "short-lived"),
        )
        assertNotEquals(true, write.isError, toolText(write))
        val second = newSecondClient()
        try {
            val reads = async(Dispatchers.IO) {
                (1..5).map {
                    mcpClient.callTool(
                        "${TOOL_PREFIX}read_file",
                        mapOf("location_id" to DOWNLOADS, "path" to "victim.txt"),
                    )
                }
            }
            val delete = async(Dispatchers.IO) {
                second.callTool(
                    "${TOOL_PREFIX}delete_file",
                    mapOf("location_id" to DOWNLOADS, "path" to "victim.txt"),
                )
            }
            val readResults = reads.await()
            val deleteResult = delete.await()
            // Every call must complete with success or a clean tool error (never a transport failure).
            for (read in readResults) {
                if (read.isError == true) {
                    assertTrue(toolText(read).isNotBlank())
                }
            }
            assertNotEquals(true, deleteResult.isError, toolText(deleteResult))
            // Server healthy afterwards
            assertTrue(totalCount(callListFiles(DOWNLOADS)) >= 0)
        } finally {
            second.close()
        }
    }

    @Test
    @Order(19)
    fun `list during writes stays consistent`() = runBlocking {
        val second = newSecondClient()
        try {
            val writer = async(Dispatchers.IO) {
                for (i in 0 until 10) {
                    val result = second.callTool(
                        "${TOOL_PREFIX}write_file",
                        mapOf("location_id" to DOWNLOADS, "path" to "consist$i.txt", "content" to "c$i"),
                    )
                    assertNotEquals(true, result.isError, toolText(result))
                }
            }
            var previousCount = -1
            while (writer.isActive) {
                val listing = callListFiles(DOWNLOADS)
                val count = totalCount(listing)
                assertTrue(
                    count >= previousCount,
                    "total_count must be monotonically non-decreasing ($previousCount -> $count)",
                )
                previousCount = count
            }
            writer.await()
        } finally {
            second.close()
        }
    }

    @Test
    @Order(20)
    fun `revoke mid session kills process and server recovers`() = runBlocking {
        StorageE2E.revokeMediaPermission(StorageE2E.PERM_IMAGES)
        StorageE2E.waitForAppProcessDeath()
        val refreshed = StorageE2E.restartServerAndRefreshClient()

        val partial = refreshed.callTool("${TOOL_PREFIX}list_storage_locations", emptyMap())
        assertNotEquals(true, partial.isError)
        val partialPictures = Json
            .parseToJsonElement(stripUntrustedWarning((partial.content.first() as TextContent).text))
            .jsonArray.map { it.jsonObject }
            .first { it["id"]!!.jsonPrimitive.content == PICTURES }
        assertEquals("Pictures - All videos, owned images", partialPictures["name"]!!.jsonPrimitive.content)
        assertEquals("partial", partialPictures["access_level"]!!.jsonPrimitive.content)

        StorageE2E.grantMediaPermission(StorageE2E.PERM_IMAGES)
        val restored = StorageE2E.restartServerAndRefreshClient()
        val full = restored.callTool("${TOOL_PREFIX}list_storage_locations", emptyMap())
        assertNotEquals(true, full.isError)
        val fullPictures = Json
            .parseToJsonElement(stripUntrustedWarning((full.content.first() as TextContent).text))
            .jsonArray.map { it.jsonObject }
            .first { it["id"]!!.jsonPrimitive.content == PICTURES }
        assertEquals("Pictures - All files", fullPictures["name"]!!.jsonPrimitive.content)
        assertEquals("full", fullPictures["access_level"]!!.jsonPrimitive.content)
    }
}
