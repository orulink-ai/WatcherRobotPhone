package com.danielealbano.androidremotecontrolmcp.privacy.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile

@DisplayName("PrivacyModelStore")
class PrivacyModelStoreTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var store: PrivacyModelStore

    @BeforeEach
    fun setUp() {
        store = PrivacyModelStore(tempDir)
    }

    private fun sparse(
        file: File,
        size: Long,
    ) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(size) }
    }

    private fun seedBothFilesAtExpectedSizes() {
        sparse(store.modelFile(), PrivacyModelAssets.MODEL.sizeBytes)
        sparse(store.tokenizerFile(), PrivacyModelAssets.TOKENIZER.sizeBytes)
    }

    @Test
    fun `isReady false when files missing`() {
        assertFalse(store.isReady())
    }

    @Test
    fun `isReady false without marker or wrong size`() {
        seedBothFilesAtExpectedSizes()
        assertFalse(store.isReady()) // no marker yet

        store.writeVerifiedMarker()
        sparse(store.modelFile(), PrivacyModelAssets.MODEL.sizeBytes - 1) // wrong size
        assertFalse(store.isReady())
    }

    @Test
    fun `isReady true with files sizes and marker`() {
        seedBothFilesAtExpectedSizes()
        store.writeVerifiedMarker()

        assertTrue(store.isReady())
    }

    @Test
    fun `clearPartialFiles removes only part files`() {
        val real = store.modelFile()
        sparse(real, 10)
        val part = File(real.parentFile, "model_int8.onnx.part")
        part.writeText("partial")

        store.clearPartialFiles()

        assertFalse(part.exists())
        assertTrue(real.exists())
    }
}
