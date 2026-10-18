package com.danielealbano.androidremotecontrolmcp.privacy.model

import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelAssets.ModelAsset
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Observable state of the Privacy Mode model download. */
sealed class DownloadState {
    data object Idle : DownloadState()

    data class Downloading(
        val progressPercent: Int,
        val assetName: String,
    ) : DownloadState()

    data object Verifying : DownloadState()

    data object Completed : DownloadState()

    data class Failed(
        val reason: String,
    ) : DownloadState()
}

/**
 * Downloads the Privacy Mode model assets to the [PrivacyModelStore], streaming each to a `.part`
 * file while computing SHA-256 incrementally, then verifying against the pinned hash before an atomic
 * rename. Idempotent: already-present (correct-size) assets are skipped.
 */
@Singleton
class PrivacyModelDownloader
    @Inject
    constructor(
        private val store: PrivacyModelStore,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val mutableState = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val state: StateFlow<DownloadState> = mutableState.asStateFlow()

        // Overridable for tests (MockEngine + fake assets/hashes).
        internal var assets: List<ModelAsset> = PrivacyModelAssets.ALL
        internal var clientProvider: () -> HttpClient = { HttpClient(OkHttp) }

        suspend fun download(): Result<Unit> =
            withContext(ioDispatcher) {
                if (store.isReady()) {
                    mutableState.value = DownloadState.Completed
                    return@withContext Result.success(Unit)
                }
                store.clearPartialFiles()
                val client = clientProvider()
                try {
                    for (asset in assets) {
                        val target = store.fileFor(asset)
                        // Skip only if the existing file's CONTENT verifies — matching size alone must not
                        // let a corrupted/foreign file be blessed by the "verified" marker written below.
                        val alreadyValid =
                            target.exists() && target.length() == asset.sizeBytes && sha256Hex(target) == asset.sha256
                        if (alreadyValid) continue
                        val result = downloadAsset(client, asset, target)
                        if (result.isFailure) {
                            val reason = result.exceptionOrNull()?.message ?: "download failed"
                            mutableState.value = DownloadState.Failed(reason)
                            return@withContext Result.failure(result.exceptionOrNull() ?: IllegalStateException(reason))
                        }
                    }
                    mutableState.value = DownloadState.Verifying
                    store.writeVerifiedMarker()
                    mutableState.value = DownloadState.Completed
                    Result.success(Unit)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    mutableState.value = DownloadState.Failed(e.message ?: "download failed")
                    Result.failure(e)
                } finally {
                    client.close()
                }
            }

        private suspend fun downloadAsset(
            client: HttpClient,
            asset: ModelAsset,
            target: File,
        ): Result<Unit> {
            val part = File(target.parentFile, target.name + PrivacyModelStore.PART_SUFFIX)
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                mutableState.value = DownloadState.Downloading(0, asset.fileName)
                client.prepareGet(asset.url).execute { response ->
                    val total = response.contentLength() ?: asset.sizeBytes
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L
                    part.outputStream().use { out ->
                        while (!channel.isClosedForRead) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read <= 0) continue
                            out.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read
                            val percent = if (total > 0) (downloaded * PERCENT / total).toInt() else 0
                            mutableState.value = DownloadState.Downloading(percent, asset.fileName)
                        }
                    }
                }
                finalizeDownload(part, target, digest, asset)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                part.delete()
                Result.failure(e)
            }
        }

        /** Verifies the downloaded [part]'s checksum and atomically promotes it to [target]. */
        private fun finalizeDownload(
            part: File,
            target: File,
            digest: MessageDigest,
            asset: ModelAsset,
        ): Result<Unit> {
            mutableState.value = DownloadState.Verifying
            val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
            return when {
                hex != asset.sha256 -> {
                    part.delete()
                    Result.failure(IllegalStateException("checksum mismatch for ${asset.fileName}"))
                }

                !part.renameTo(target) -> {
                    part.delete()
                    Result.failure(IllegalStateException("rename failed for ${asset.fileName}"))
                }

                else -> {
                    Result.success(Unit)
                }
            }
        }

        /** Streams [file] through SHA-256 and returns the lowercase hex digest. */
        private fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
        }

        companion object {
            private const val BUFFER_SIZE = 64 * 1024
            private const val PERCENT = 100
            private const val BYTE_MASK = 0xFF
        }
    }
