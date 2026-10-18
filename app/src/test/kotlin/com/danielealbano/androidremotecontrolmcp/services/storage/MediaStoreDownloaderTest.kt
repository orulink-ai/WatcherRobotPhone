package com.danielealbano.androidremotecontrolmcp.services.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler

@ExtendWith(MockKExtension::class)
@DisplayName("MediaStoreDownloader")
class MediaStoreDownloaderTest {
    @MockK(relaxed = true)
    private lateinit var mockContext: Context

    @MockK(relaxed = true)
    private lateinit var mockContentResolver: ContentResolver

    @MockK(relaxed = true)
    private lateinit var mockConnection: HttpURLConnection

    private val insertUri: Uri = mockk(relaxed = true)

    private lateinit var downloader: MediaStoreDownloader

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        every { mockContext.contentResolver } returns mockContentResolver
        every { mockContentResolver.delete(any(), any(), any()) } returns 1
        every { mockContentResolver.update(any(), any(), any(), any()) } returns 1

        downloader = MediaStoreDownloader(mockContext)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    /** Builds a real URL whose openConnection() returns the given mock (URL is not mockable). */
    private fun urlFor(connection: HttpURLConnection): URL =
        URL(
            "http",
            "example.com",
            80,
            "/file.bin",
            object : URLStreamHandler() {
                override fun openConnection(u: URL): URLConnection = connection
            },
        )

    private fun download(config: ServerConfig = ServerConfig()): Long =
        downloader.downloadToPendingUri(
            insertUri,
            urlFor(mockConnection),
            "http://example.com/file.bin",
            config,
            "builtin:downloads/file.bin",
        )

    @Test
    fun `downloadToPendingUri streams bytes and clears IS_PENDING`() {
        every { mockConnection.responseCode } returns 200
        every { mockConnection.contentLengthLong } returns 5L
        every { mockConnection.inputStream } returns ByteArrayInputStream("hello".toByteArray())
        val output = ByteArrayOutputStream()
        every { mockContentResolver.openOutputStream(eq(insertUri), eq("wt")) } returns output

        val result = download()

        assertEquals(5L, result)
        assertEquals("hello", output.toString(Charsets.UTF_8.name()))
        verify { mockContentResolver.update(eq(insertUri), any(), any(), any()) }
        verify(exactly = 0) { mockContentResolver.delete(any(), any(), any()) }
        verify { mockConnection.disconnect() }
    }

    @Test
    fun `downloadToPendingUri deletes entry on http error status`() {
        every { mockConnection.responseCode } returns 404

        val exception = assertThrows<McpToolException.ActionFailed> { download() }

        assertTrue(exception.message!!.contains("HTTP status 404"))
        verify { mockContentResolver.delete(eq(insertUri), any(), any()) }
        verify(exactly = 0) { mockContentResolver.update(any(), any(), any(), any()) }
        verify { mockConnection.disconnect() }
    }

    @Test
    fun `downloadToPendingUri rejects oversized reported content length`() {
        every { mockConnection.responseCode } returns 200
        every { mockConnection.contentLengthLong } returns 2L * 1024 * 1024

        val exception =
            assertThrows<McpToolException.ActionFailed> {
                download(ServerConfig(fileSizeLimitMb = 1))
            }

        assertTrue(exception.message!!.contains("exceeds limit"))
        verify { mockContentResolver.delete(eq(insertUri), any(), any()) }
    }

    @Test
    fun `downloadToPendingUri rejects stream exceeding size limit`() {
        every { mockConnection.responseCode } returns 200
        every { mockConnection.contentLengthLong } returns -1L
        every { mockConnection.inputStream } returns
            ByteArrayInputStream(ByteArray(1024 * 1024 + 1))
        every { mockContentResolver.openOutputStream(eq(insertUri), eq("wt")) } returns
            ByteArrayOutputStream()

        val exception =
            assertThrows<McpToolException.ActionFailed> {
                download(ServerConfig(fileSizeLimitMb = 1))
            }

        assertTrue(exception.message!!.contains("file size limit"))
        verify { mockContentResolver.delete(eq(insertUri), any(), any()) }
        verify(exactly = 0) { mockContentResolver.update(any(), any(), any(), any()) }
    }

    @Test
    fun `downloadToPendingUri throws when destination cannot be opened`() {
        every { mockConnection.responseCode } returns 200
        every { mockConnection.contentLengthLong } returns 5L
        every { mockContentResolver.openOutputStream(eq(insertUri), eq("wt")) } returns null

        val exception = assertThrows<McpToolException.ActionFailed> { download() }

        assertTrue(exception.message!!.contains("Failed to open download destination"))
        verify { mockContentResolver.delete(eq(insertUri), any(), any()) }
    }

    @Test
    fun `downloadToPendingUri wraps connect failure with cause and deletes entry`() {
        every { mockConnection.connect() } throws IOException("boom")

        val exception = assertThrows<McpToolException.ActionFailed> { download() }

        assertTrue(exception.message!!.contains("Download failed: boom"))
        assertTrue(exception.cause is IOException)
        verify { mockContentResolver.delete(eq(insertUri), any(), any()) }
        verify { mockConnection.disconnect() }
    }
}
