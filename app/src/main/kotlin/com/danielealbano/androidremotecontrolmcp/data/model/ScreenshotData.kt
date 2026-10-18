package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.Serializable

/**
 * Represents a captured screenshot ready for MCP response serialization.
 *
 * @property format The image encoding format (always "jpeg").
 * @property data The base64-encoded JPEG image data.
 * @property width The screenshot width in pixels.
 * @property height The screenshot height in pixels.
 * @property captureMetadata Original display coordinates and local automation UI for this capture, when available.
 */
@Serializable
data class ScreenshotData(
    val format: String = FORMAT_JPEG,
    val data: String,
    val width: Int,
    val height: Int,
    val captureMetadata: ScreenshotCaptureMetadata? = null,
) {
    companion object {
        const val FORMAT_JPEG = "jpeg"
    }
}
