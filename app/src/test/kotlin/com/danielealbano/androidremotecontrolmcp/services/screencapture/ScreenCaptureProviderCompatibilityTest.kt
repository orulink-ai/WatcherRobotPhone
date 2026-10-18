package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.graphics.Bitmap
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ScreenCaptureProviderCompatibilityTest {
    @Test
    fun `a bitmap only provider supplies a frame without inventing capture metadata`() =
        runTest {
            val bitmap = mockk<Bitmap>()
            val provider = BitmapOnlyProvider(Result.success(bitmap))

            val frame = provider.captureScreenshotFrame(315, 700).getOrThrow()

            assertSame(bitmap, frame.bitmap)
            assertNull(frame.captureMetadata)
            assertEquals(315 to 700, provider.requestedSize)
        }

    @Test
    fun `legacy provider failures remain failures through the frame adapter`() =
        runTest {
            val failure = IllegalStateException("native capture unavailable")
            val provider = BitmapOnlyProvider(Result.failure(failure))
            assertSame(failure, provider.captureScreenshotFrame().exceptionOrNull())
        }

    private class BitmapOnlyProvider(
        private val result: Result<Bitmap>,
    ) : ScreenCaptureProvider {
        var requestedSize: Pair<Int?, Int?>? = null

        override suspend fun captureScreenshotBitmap(
            maxWidth: Int?,
            maxHeight: Int?,
        ): Result<Bitmap> {
            requestedSize = maxWidth to maxHeight
            return result
        }

        override suspend fun captureScreenshot(
            quality: Int,
            maxWidth: Int?,
            maxHeight: Int?,
        ): Result<ScreenshotData> = Result.failure(UnsupportedOperationException("Encoding is outside this legacy provider"))

        override fun isScreenCaptureAvailable(): Boolean = true
    }
}
