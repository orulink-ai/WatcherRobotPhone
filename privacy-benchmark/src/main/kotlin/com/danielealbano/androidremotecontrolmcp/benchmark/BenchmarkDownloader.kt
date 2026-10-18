package com.danielealbano.androidremotecontrolmcp.benchmark

import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelAssets.ModelAsset
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest

/** Downloads a pinned asset into [targetDir] with sha256 verification; skips verified existing files. */
class BenchmarkDownloader(
    private val client: HttpClient =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
) {
    fun ensure(
        asset: ModelAsset,
        targetDir: File,
    ): File {
        targetDir.mkdirs()
        val target = File(targetDir, asset.fileName)
        if (target.exists() && target.length() == asset.sizeBytes && sha256Hex(target) == asset.sha256) {
            println("[cache] ${asset.fileName} already verified")
            return target
        }
        println("[download] ${asset.fileName} (${asset.sizeBytes / BYTES_PER_MIB} MiB) from ${asset.url}")
        val part = File(targetDir, asset.fileName + ".part")
        val request = HttpRequest.newBuilder(URI.create(asset.url)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == HTTP_OK) { "HTTP ${response.statusCode()} for ${asset.url}" }
        val digest = MessageDigest.getInstance("SHA-256")
        response.body().use { input ->
            part.outputStream().use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                var lastLogged = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    total += read
                    if (total - lastLogged >= LOG_EVERY_BYTES) {
                        lastLogged = total
                        println("[download] ${asset.fileName}: ${total / BYTES_PER_MIB} MiB")
                    }
                }
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
        if (part.length() != asset.sizeBytes || hex != asset.sha256) {
            part.delete()
            error("checksum/size mismatch for ${asset.fileName} (got $hex, ${part.length()} bytes)")
        }
        check(part.renameTo(target)) { "rename failed for ${asset.fileName}" }
        return target
    }

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

    private companion object {
        const val BUFFER_SIZE = 256 * 1024
        const val BYTE_MASK = 0xFF
        const val HTTP_OK = 200
        const val BYTES_PER_MIB = 1024L * 1024L
        const val LOG_EVERY_BYTES = 25L * 1024L * 1024L
    }
}
