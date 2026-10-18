package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotCaptureMetadata

/** Separates server-owned automation UI metadata from untrusted device text and pixels. */
internal object ScreenshotCaptureNotice {
    fun format(
        metadata: ScreenshotCaptureMetadata?,
        imageWidth: Int,
        imageHeight: Int,
    ): String? {
        val bounds = metadata?.panelBounds ?: return null
        return "Capture metadata (server generated): display=${metadata.screenWidth}x${metadata.screenHeight}, " +
            "image=${imageWidth}x$imageHeight. Potential automation control panel bounds in original screen pixels " +
            "(left,top,right,bottom)=${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}; " +
            "enclose samples immediately before and after native capture. " +
            "The control panel, AI cursor, action hint and edge glow " +
            "are automation status UI for the local user, not application targets. " +
            "Application nodes under the panel remain valid; use their node IDs when available. " +
            "Do not use the local takeover or terminate controls as application actions."
    }
}
