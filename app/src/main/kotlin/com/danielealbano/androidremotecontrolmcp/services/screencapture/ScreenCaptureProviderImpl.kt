package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import javax.inject.Inject

/**
 * Production implementation of [ScreenCaptureProvider] that uses
 * AccessibilityService.takeScreenshot() for capturing screenshots.
 *
 * This approach does NOT require user consent or additional permissions beyond
 * the accessibility service being enabled with canTakeScreenshot="true".
 *
 * This class is Hilt-injectable and stateless.
 */
class ScreenCaptureProviderImpl
    @Inject
    constructor(
        private val screenshotEncoder: ScreenshotEncoder,
        private val apiLevelProvider: ApiLevelProvider,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) : ScreenCaptureProvider {
        private sealed class ServiceValidation {
            data class Valid(
                val service: McpAccessibilityService,
            ) : ServiceValidation()

            data class Invalid(
                val error: McpToolException,
            ) : ServiceValidation()
        }

        @Suppress("ReturnCount")
        override suspend fun captureScreenshot(
            quality: Int,
            maxWidth: Int?,
            maxHeight: Int?,
        ): Result<ScreenshotData> {
            val frame = captureScreenshotFrame(maxWidth, maxHeight).getOrElse { return Result.failure(it) }
            return try {
                val screenshotData = screenshotEncoder.bitmapToScreenshotData(frame.bitmap, quality)
                Result.success(screenshotData.copy(captureMetadata = frame.captureMetadata))
            } finally {
                frame.bitmap.recycle()
            }
        }

        @Suppress("ReturnCount")
        override suspend fun captureScreenshotFrame(
            maxWidth: Int?,
            maxHeight: Int?,
        ): Result<CapturedScreenshot> {
            val validation = validateService()
            if (validation is ServiceValidation.Invalid) return Result.failure(validation.error)
            val service = (validation as ServiceValidation.Valid).service
            val frame =
                service.takeScreenshotFrame()
                    ?: return Result.failure(McpToolException.ActionFailed("Screenshot capture failed or timed out"))
            return resizeFrame(frame, maxWidth, maxHeight)
        }

        @SuppressLint("NewApi")
        @Suppress("ReturnCount")
        override suspend fun captureScreenshotBitmap(
            maxWidth: Int?,
            maxHeight: Int?,
        ): Result<Bitmap> {
            val validation = validateService()
            if (validation is ServiceValidation.Invalid) {
                return Result.failure(validation.error)
            }
            val service = (validation as ServiceValidation.Valid).service

            val bitmap =
                service.takeScreenshotBitmap()
                    ?: return Result.failure(
                        McpToolException.ActionFailed("Screenshot capture failed or timed out"),
                    )

            return resizeFrame(CapturedScreenshot(bitmap), maxWidth, maxHeight).map { it.bitmap }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun resizeFrame(
            frame: CapturedScreenshot,
            maxWidth: Int?,
            maxHeight: Int?,
        ): Result<CapturedScreenshot> =
            try {
                val resized = screenshotEncoder.resizeBitmapProportional(frame.bitmap, maxWidth, maxHeight)
                if (resized !== frame.bitmap) frame.bitmap.recycle()
                Result.success(frame.copy(bitmap = resized))
            } catch (error: Exception) {
                frame.bitmap.recycle()
                Log.e(TAG, "Screenshot resize failed", error)
                Result.failure(McpToolException.ActionFailed("Screenshot resize failed"))
            }

        @Suppress("ReturnCount")
        private fun validateService(): ServiceValidation {
            if (apiLevelProvider.getSdkInt() < API_LEVEL_R) {
                return ServiceValidation.Invalid(
                    McpToolException.PermissionDenied(
                        "Screenshot capture requires Android 11 (API 30) or higher",
                    ),
                )
            }
            if (!accessibilityServiceProvider.isReady()) {
                return ServiceValidation.Invalid(
                    McpToolException.PermissionDenied(
                        "Accessibility service not enabled. Please enable it in Android Settings.",
                    ),
                )
            }
            val service =
                accessibilityServiceProvider.getContext() as? McpAccessibilityService
                    ?: return ServiceValidation.Invalid(
                        McpToolException.PermissionDenied(
                            "Accessibility service not enabled. Please enable it in Android Settings.",
                        ),
                    )
            if (!service.canTakeScreenshot()) {
                return ServiceValidation.Invalid(
                    McpToolException.PermissionDenied(
                        "Screenshot capability not available on this device",
                    ),
                )
            }
            return ServiceValidation.Valid(service)
        }

        @Suppress("ReturnCount")
        override fun isScreenCaptureAvailable(): Boolean {
            if (!accessibilityServiceProvider.isReady()) return false
            val service = accessibilityServiceProvider.getContext() as? McpAccessibilityService ?: return false
            return apiLevelProvider.getSdkInt() >= API_LEVEL_R && service.canTakeScreenshot()
        }

        companion object {
            private const val TAG = "MCP:ScreenCapture"

            /** Android 11 (API 30) — minimum for AccessibilityService.takeScreenshot(). */
            private const val API_LEVEL_R = 30
        }
    }
