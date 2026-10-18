package com.danielealbano.androidremotecontrolmcp.privacy.model

import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelAssets.ModelAsset
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("PrivacyModelDownloader")
class PrivacyModelDownloaderTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var store: PrivacyModelStore
    private lateinit var downloader: PrivacyModelDownloader

    private val modelData = "MODEL_PAYLOAD_BYTES".toByteArray()
    private val tokData = "TOKENIZER_PAYLOAD_BYTES".toByteArray()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun asset(
        name: String,
        path: String,
        data: ByteArray,
        hash: String = sha256(data),
    ) = ModelAsset(name, "https://test.example$path", hash, data.size.toLong())

    private fun okHandlerClient(onModel: (() -> Unit)? = null): () -> HttpClient =
        {
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/model" -> {
                                onModel?.invoke()
                                respond(
                                    ByteReadChannel(modelData),
                                    HttpStatusCode.OK,
                                    headersOf(HttpHeaders.ContentLength, modelData.size.toString()),
                                )
                            }

                            else -> {
                                respond(
                                    ByteReadChannel(tokData),
                                    HttpStatusCode.OK,
                                    headersOf(HttpHeaders.ContentLength, tokData.size.toString()),
                                )
                            }
                        }
                    }
                }
            }
        }

    @BeforeEach
    fun setUp() {
        store = PrivacyModelStore(tempDir)
        downloader = PrivacyModelDownloader(store, UnconfinedTestDispatcher())
        downloader.assets =
            listOf(asset("model.bin", "/model", modelData), asset("tok.bin", "/tok", tokData))
    }

    @Test
    fun `download success verifies hash and renames`() =
        runTest {
            downloader.clientProvider = okHandlerClient()

            val result = downloader.download()

            assertTrue(result.isSuccess)
            assertTrue(downloader.state.value is DownloadState.Completed)
            assertArrayEquals(modelData, store.fileFor(downloader.assets[0]).readBytes())
            assertArrayEquals(tokData, store.fileFor(downloader.assets[1]).readBytes())
            assertTrue(File(tempDir, "${PrivacyModelAssets.MODELS_DIR}/.verified").exists())
        }

    @Test
    fun `hash mismatch fails and deletes part`() =
        runTest {
            val wrongHash = "0".repeat(64)
            downloader.assets = listOf(asset("model.bin", "/model", modelData, hash = wrongHash))
            downloader.clientProvider = okHandlerClient()

            val result = downloader.download()

            assertTrue(result.isFailure)
            assertTrue(downloader.state.value is DownloadState.Failed)
            assertFalse(store.fileFor(downloader.assets[0]).exists())
            assertFalse(File(store.fileFor(downloader.assets[0]).parentFile, "model.bin.part").exists())
        }

    @Test
    fun `network error fails with reason then retry succeeds`() =
        runTest {
            downloader.assets = listOf(asset("model.bin", "/model", modelData))
            downloader.clientProvider = {
                HttpClient(MockEngine) { engine { addHandler { throw IOException("network down") } } }
            }

            val failed = downloader.download()
            assertTrue(failed.isFailure)
            assertTrue(downloader.state.value is DownloadState.Failed)

            downloader.clientProvider = okHandlerClient()
            val retried = downloader.download()
            assertTrue(retried.isSuccess)
            assertTrue(downloader.state.value is DownloadState.Completed)
        }

    @Test
    fun `skips already verified assets`() =
        runTest {
            // Pre-seed the model asset at the correct size so the downloader skips it.
            store.fileFor(downloader.assets[0]).writeBytes(modelData)
            var modelRequested = false
            downloader.clientProvider = okHandlerClient(onModel = { modelRequested = true })

            val result = downloader.download()

            assertTrue(result.isSuccess)
            assertFalse(modelRequested, "already-present model must not be re-requested")
            assertArrayEquals(tokData, store.fileFor(downloader.assets[1]).readBytes())
        }

    @Test
    fun `re-downloads size-matching file with wrong content`() =
        runTest {
            // Same byte length as the real asset but different content: the size-only skip must NOT
            // trust it (otherwise the ".verified" marker would bless an unverified file).
            val wrongBytes = ByteArray(modelData.size) { 'X'.code.toByte() }
            store.fileFor(downloader.assets[0]).writeBytes(wrongBytes)
            var modelRequested = false
            downloader.clientProvider = okHandlerClient(onModel = { modelRequested = true })

            val result = downloader.download()

            assertTrue(result.isSuccess)
            assertTrue(modelRequested, "wrong-content file must be re-downloaded")
            assertArrayEquals(modelData, store.fileFor(downloader.assets[0]).readBytes())
        }
}
