package com.danielealbano.androidremotecontrolmcp.privacy.model

import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelAssets.ModelAsset
import java.io.File

/**
 * On-disk store for the downloaded Privacy Mode model assets. Readiness is reported from a small
 * `.verified` marker (written only after a successful checksum verification) plus file existence and
 * exact byte-size checks — so it never re-hashes the 151 MB model on every server start.
 * [baseDir] is the app's files directory in production (provided by the DI module) and a local cache
 * directory in the effectiveness benchmark.
 */
class PrivacyModelStore(
    private val baseDir: File,
) {
    private fun dir(): File = File(baseDir, PrivacyModelAssets.MODELS_DIR).apply { mkdirs() }

    fun fileFor(asset: ModelAsset): File = File(dir(), asset.fileName)

    fun modelFile(): File = fileFor(PrivacyModelAssets.MODEL)

    fun tokenizerFile(): File = fileFor(PrivacyModelAssets.TOKENIZER)

    private fun markerFile(): File = File(dir(), MARKER_NAME)

    fun isReady(): Boolean {
        val recorded =
            markerFile().takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
                ?: return false
        return PrivacyModelAssets.ALL.all { asset ->
            val file = fileFor(asset)
            file.exists() && file.length() == asset.sizeBytes && recorded.contains(asset.sha256)
        }
    }

    fun writeVerifiedMarker() {
        markerFile().writeText(PrivacyModelAssets.ALL.joinToString("\n") { it.sha256 })
    }

    fun clearPartialFiles() {
        dir().listFiles { _, name -> name.endsWith(PART_SUFFIX) }?.forEach { it.delete() }
    }

    companion object {
        const val PART_SUFFIX = ".part"
        private const val MARKER_NAME = ".verified"
    }
}
