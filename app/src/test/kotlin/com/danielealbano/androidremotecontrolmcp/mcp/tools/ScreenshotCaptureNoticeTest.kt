package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotBounds
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotCaptureMetadata
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScreenshotCaptureNoticeTest {
    @Test
    fun `notice labels the local control bar separately with unscaled screen coordinates`() {
        val metadata = ScreenshotCaptureMetadata(1440, 3200, ScreenshotBounds(48, 2800, 1392, 3060))
        val notice = ScreenshotCaptureNotice.format(metadata, imageWidth = 315, imageHeight = 700)!!
        assertTrue(notice.contains("1440x3200"))
        assertTrue(notice.contains("315x700"))
        assertTrue(notice.contains("48,2800,1392,3060"))
        assertTrue(notice.contains("local user"))
        assertTrue(notice.contains("not application targets"))
        assertTrue(notice.contains("Application nodes under the panel remain valid"))
    }

    @Test
    fun `absent control bars add no misleading overlay notice`() {
        assertNull(ScreenshotCaptureNotice.format(null, 315, 700))
        assertNull(ScreenshotCaptureNotice.format(ScreenshotCaptureMetadata(1440, 3200), 315, 700))
    }
}
