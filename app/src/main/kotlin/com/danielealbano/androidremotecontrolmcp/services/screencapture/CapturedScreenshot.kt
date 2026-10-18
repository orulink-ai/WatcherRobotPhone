package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.graphics.Bitmap
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotCaptureMetadata

/** The caller owns [bitmap]; metadata belongs to this frame rather than a shared latest-capture slot. */
data class CapturedScreenshot(
    val bitmap: Bitmap,
    val captureMetadata: ScreenshotCaptureMetadata? = null,
)
