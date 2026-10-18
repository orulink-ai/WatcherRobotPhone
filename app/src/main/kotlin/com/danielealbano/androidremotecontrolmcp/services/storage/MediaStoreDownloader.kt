package com.danielealbano.androidremotecontrolmcp.services.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Streams an HTTP(S) download into a pending MediaStore entry, clearing IS_PENDING on
 * success and deleting the entry on any failure.
 */
internal class MediaStoreDownloader(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    fun downloadToPendingUri(
        insertUri: Uri,
        parsedUrl: URL,
        url: String,
        config: ServerConfig,
        logContext: String,
    ): Long {
        var connection: HttpURLConnection? = null
        try {
            connection = prepareConnection(parsedUrl, config)
            connection.connect()
            validateResponse(connection, url, config)
            val totalBytesWritten = streamToDestination(connection, insertUri, config)
            markDownloadComplete(insertUri)
            Log.i(TAG, "Downloaded $totalBytesWritten bytes from $url to $logContext")
            return totalBytesWritten
        } catch (e: McpToolException) {
            context.contentResolver.delete(insertUri, null, null)
            throw e
        } catch (e: Exception) {
            context.contentResolver.delete(insertUri, null, null)
            throw McpToolException.ActionFailed(
                "Download failed: ${e.message ?: "Unknown error"}",
                e,
            )
        } finally {
            connection?.disconnect()
        }
    }

    /** Configures (but does not connect) so a failing connect() can still be disconnected. */
    private fun prepareConnection(
        parsedUrl: URL,
        config: ServerConfig,
    ): HttpURLConnection {
        val timeoutMs = (config.downloadTimeoutSeconds * MILLIS_PER_SECOND).toInt()
        val connection = parsedUrl.openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = true

        if (config.allowUnverifiedHttpsCerts && connection is HttpsURLConnection) {
            SslUtils.configurePermissiveSsl(connection)
        }

        return connection
    }

    private fun validateResponse(
        connection: HttpURLConnection,
        url: String,
        config: ServerConfig,
    ) {
        val responseCode = connection.responseCode
        if (responseCode !in HTTP_SUCCESS_RANGE) {
            throw McpToolException.ActionFailed(
                "Download failed with HTTP status $responseCode for URL: $url",
            )
        }

        val limitBytes = config.fileSizeLimitMb.toLong() * BYTES_PER_MB
        val contentLength = connection.contentLengthLong
        if (contentLength > 0 && contentLength > limitBytes) {
            throw McpToolException.ActionFailed(
                "Server reports file size of $contentLength bytes, exceeds limit of " +
                    "${config.fileSizeLimitMb} MB.",
            )
        }
    }

    private fun streamToDestination(
        connection: HttpURLConnection,
        insertUri: Uri,
        config: ServerConfig,
    ): Long {
        val limitBytes = config.fileSizeLimitMb.toLong() * BYTES_PER_MB
        var totalBytesWritten = 0L
        context.contentResolver.openOutputStream(insertUri, "wt")?.use { outputStream ->
            connection.inputStream.use { inputStream ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    totalBytesWritten += bytesRead
                    if (totalBytesWritten > limitBytes) {
                        throw McpToolException.ActionFailed(
                            "Download exceeds the configured file size limit of " +
                                "${config.fileSizeLimitMb} MB.",
                        )
                    }
                    outputStream.write(buffer, 0, bytesRead)
                }
            }
        } ?: throw McpToolException.ActionFailed(
            "Failed to open download destination for writing",
        )
        return totalBytesWritten
    }

    private fun markDownloadComplete(insertUri: Uri) {
        // Clear IS_PENDING on success
        val updateValues =
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
        context.contentResolver.update(insertUri, updateValues, null, null)
    }

    companion object {
        private const val TAG = "MCP:MediaStoreDownload"
        private const val BYTES_PER_MB = 1024L * 1024L
        private const val MILLIS_PER_SECOND = 1000L
        private const val DOWNLOAD_BUFFER_SIZE = 8192
        private val HTTP_SUCCESS_RANGE = 200..299
    }
}
