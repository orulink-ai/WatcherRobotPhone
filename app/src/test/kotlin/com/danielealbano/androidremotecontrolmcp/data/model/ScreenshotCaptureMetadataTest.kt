package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ScreenshotCaptureMetadataTest {
    @Test
    fun `old encoded screenshots remain compatible without metadata`() {
        val restored = Json.decodeFromString<ScreenshotData>("""{"data":"pixels","width":300,"height":700}""")
        assertNull(restored.captureMetadata)
    }

    @Test
    fun `screen pixel bounds survive serialization and image resizing unchanged`() {
        val metadata = ScreenshotCaptureMetadata(1440, 3200, ScreenshotBounds(48, 2800, 1392, 3060))
        val original = ScreenshotData(data = "pixels", width = 1440, height = 3200, captureMetadata = metadata)
        val resized = original.copy(width = 315, height = 700)

        assertEquals(metadata, Json.decodeFromString<ScreenshotData>(Json.encodeToString(resized)).captureMetadata)
        assertEquals(1440, resized.captureMetadata?.screenWidth)
        assertEquals(48, resized.captureMetadata?.panelBounds?.left)
    }

    @Test
    fun `first layout completing during capture still identifies the panel`() {
        val laidOut = ScreenshotBounds(48, 2800, 1392, 3060)
        assertEquals(laidOut, ScreenshotBounds.enclosing(null, laidOut))
        assertEquals(laidOut, ScreenshotBounds.enclosing(ScreenshotBounds(0, 0, 0, 0), laidOut))
        assertNull(ScreenshotBounds.enclosing(null, ScreenshotBounds(0, 0, 0, 0)))
    }

    @Test
    fun `moving or disappearing panels retain both potential captured regions`() {
        val before = ScreenshotBounds(48, 2800, 1392, 3060)
        val after = ScreenshotBounds(80, 1800, 1400, 2060)
        assertEquals(ScreenshotBounds(48, 1800, 1400, 3060), ScreenshotBounds.enclosing(before, after))
        assertEquals(before, ScreenshotBounds.enclosing(before, null))
    }
}
