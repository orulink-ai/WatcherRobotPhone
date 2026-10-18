package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.graphics.Bitmap
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotBounds
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotCaptureMetadata
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ScreenCaptureProviderImplTest")
class ScreenCaptureProviderImplTest {
    private lateinit var screenshotEncoder: ScreenshotEncoder
    private lateinit var mockApiLevelProvider: ApiLevelProvider
    private lateinit var mockAccessibilityServiceProvider: AccessibilityServiceProvider
    private lateinit var provider: ScreenCaptureProviderImpl
    private lateinit var mockService: McpAccessibilityService

    @BeforeEach
    fun setup() {
        screenshotEncoder = mockk(relaxed = true)
        mockApiLevelProvider = mockk()
        mockAccessibilityServiceProvider = mockk()
        // Return API 30 (Android R) — minimum for screenshot capability
        every { mockApiLevelProvider.getSdkInt() } returns 30

        mockService = mockk(relaxed = true)
        every { mockService.canTakeScreenshot() } returns true

        // Configure the AccessibilityServiceProvider to report ready and return the mock service
        every { mockAccessibilityServiceProvider.isReady() } returns true
        every { mockAccessibilityServiceProvider.getContext() } returns mockService

        provider = ScreenCaptureProviderImpl(screenshotEncoder, mockApiLevelProvider, mockAccessibilityServiceProvider)
    }

    @Nested
    @DisplayName("captureScreenshotBitmap")
    inner class CaptureScreenshotBitmapTests {
        @Test
        fun `returns resized bitmap on success`() =
            runTest {
                val originalBitmap = mockk<Bitmap>(relaxed = true)
                val resizedBitmap = mockk<Bitmap>(relaxed = true)
                coEvery { mockService.takeScreenshotBitmap() } returns originalBitmap
                every {
                    screenshotEncoder.resizeBitmapProportional(originalBitmap, 700, 700)
                } returns resizedBitmap

                val result = provider.captureScreenshotBitmap(maxWidth = 700, maxHeight = 700)

                assertTrue(result.isSuccess)
                assertEquals(resizedBitmap, result.getOrNull())
                // Original bitmap recycled because a new (different) one was produced
                verify(exactly = 1) { originalBitmap.recycle() }
            }

        @Test
        fun `does not recycle bitmap when resize returns same instance`() =
            runTest {
                val bitmap = mockk<Bitmap>(relaxed = true)
                coEvery { mockService.takeScreenshotBitmap() } returns bitmap
                every {
                    screenshotEncoder.resizeBitmapProportional(bitmap, 700, 700)
                } returns bitmap // same instance

                val result = provider.captureScreenshotBitmap(maxWidth = 700, maxHeight = 700)

                assertTrue(result.isSuccess)
                verify(exactly = 0) { bitmap.recycle() }
            }

        @Test
        fun `returns failure when takeScreenshotBitmap returns null`() =
            runTest {
                coEvery { mockService.takeScreenshotBitmap() } returns null

                val result = provider.captureScreenshotBitmap(maxWidth = 700, maxHeight = 700)

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull()?.message?.contains("Screenshot capture failed") == true)
            }

        @Test
        fun `returns failure when service not available`() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns false

                val result = provider.captureScreenshotBitmap(maxWidth = 700, maxHeight = 700)

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull()?.message?.contains("Accessibility service not enabled") == true)
            }

        @Test
        fun `recycles original bitmap when resize throws`() =
            runTest {
                val bitmap = mockk<Bitmap>(relaxed = true)
                coEvery { mockService.takeScreenshotBitmap() } returns bitmap
                every {
                    screenshotEncoder.resizeBitmapProportional(bitmap, any(), any())
                } throws RuntimeException("Resize failed")

                val result = provider.captureScreenshotBitmap(maxWidth = 700, maxHeight = 700)

                assertTrue(result.isFailure)
                // Error message is generic (does not leak internal exception details)
                assertTrue(result.exceptionOrNull()?.message == "Screenshot resize failed")
                // Original bitmap must be recycled even on resize failure
                verify(exactly = 1) { bitmap.recycle() }
            }

        @Test
        fun `returns failure when API level below 30`() =
            runTest {
                every { mockApiLevelProvider.getSdkInt() } returns 29

                val result = provider.captureScreenshotBitmap(maxWidth = 700, maxHeight = 700)

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull()?.message?.contains("Android 11") == true)
            }

        @Test
        fun `passes through null maxWidth and maxHeight`() =
            runTest {
                val bitmap = mockk<Bitmap>(relaxed = true)
                coEvery { mockService.takeScreenshotBitmap() } returns bitmap
                every {
                    screenshotEncoder.resizeBitmapProportional(bitmap, null, null)
                } returns bitmap // no resize needed

                val result = provider.captureScreenshotBitmap(maxWidth = null, maxHeight = null)

                assertTrue(result.isSuccess)
                verify(exactly = 0) { bitmap.recycle() }
            }
    }

    @Test
    fun `resizing a captured frame preserves its original screen coordinates`() =
        runTest {
            val original = mockk<Bitmap>(relaxed = true)
            val resized = mockk<Bitmap>(relaxed = true)
            val metadata = ScreenshotCaptureMetadata(1440, 3200, ScreenshotBounds(48, 2800, 1392, 3060))
            coEvery { mockService.takeScreenshotFrame() } returns CapturedScreenshot(original, metadata)
            every { screenshotEncoder.resizeBitmapProportional(original, 700, 700) } returns resized

            val result = provider.captureScreenshotFrame(700, 700).getOrThrow()

            assertEquals(resized, result.bitmap)
            assertEquals(metadata, result.captureMetadata)
            verify(exactly = 1) { original.recycle() }
            verify(exactly = 0) { resized.recycle() }
        }

    @Test
    fun `encoded screenshot retains metadata from the same native frame`() =
        runTest {
            val original = mockk<Bitmap>(relaxed = true)
            val resized = mockk<Bitmap>(relaxed = true)
            val metadata = ScreenshotCaptureMetadata(1440, 3200, ScreenshotBounds(48, 2800, 1392, 3060))
            coEvery { mockService.takeScreenshotFrame() } returns CapturedScreenshot(original, metadata)
            every { screenshotEncoder.resizeBitmapProportional(original, 700, 700) } returns resized
            every { screenshotEncoder.bitmapToScreenshotData(resized, 80) } returns
                ScreenshotData(data = "pixels", width = 315, height = 700)

            val result = provider.captureScreenshot(80, 700, 700).getOrThrow()

            assertEquals("pixels", result.data)
            assertEquals(metadata, result.captureMetadata)
            assertEquals(315, result.width)
            assertEquals(700, result.height)
            verify(exactly = 1) { original.recycle() }
            verify(exactly = 1) { resized.recycle() }
        }
}
