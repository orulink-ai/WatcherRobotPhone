package com.danielealbano.androidremotecontrolmcp.benchmark

import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelAssets.ModelAsset
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("BenchmarkDownloader")
class BenchmarkDownloaderTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var server: HttpServer
    private val hits = AtomicInteger(0)
    private val payload = "benchmark fixture bytes".toByteArray()

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/asset") { exchange ->
            hits.incrementAndGet()
            exchange.sendResponseHeaders(HTTP_OK, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

    private fun asset(sha256: String = sha256Hex(payload)): ModelAsset =
        ModelAsset(
            fileName = "fixture.bin",
            url = "http://127.0.0.1:${server.address.port}/asset",
            sha256 = sha256,
            sizeBytes = payload.size.toLong(),
        )

    @Test
    fun `downloads and verifies asset`() {
        val target = BenchmarkDownloader().ensure(asset(), tempDir)

        assertTrue(target.exists())
        assertArrayEquals(payload, target.readBytes())
        assertFalse(File(tempDir, "fixture.bin.part").exists())
    }

    @Test
    fun `rejects checksum mismatch`() {
        val bad = asset(sha256 = sha256Hex("different".toByteArray()))

        assertThrows(IllegalStateException::class.java) { BenchmarkDownloader().ensure(bad, tempDir) }

        assertFalse(File(tempDir, "fixture.bin").exists())
        assertFalse(File(tempDir, "fixture.bin.part").exists())
    }

    @Test
    fun `skips existing verified file`() {
        File(tempDir, "fixture.bin").writeBytes(payload)

        val target = BenchmarkDownloader().ensure(asset(), tempDir)

        assertTrue(target.exists())
        assertEquals(0, hits.get())
    }

    private companion object {
        const val HTTP_OK = 200
        const val BYTE_MASK = 0xFF
    }
}
