package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.Serializable

/** Coordinates always refer to the original display, regardless of encoded image dimensions. */
@Serializable
data class ScreenshotBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    companion object {
        /** Includes a panel laid out or moved while the asynchronous native request was in flight. */
        fun enclosing(
            before: ScreenshotBounds?,
            after: ScreenshotBounds?,
        ): ScreenshotBounds? {
            val first = before?.takeIf { it.right > it.left && it.bottom > it.top }
            val second = after?.takeIf { it.right > it.left && it.bottom > it.top }
            return when {
                first == null -> {
                    second
                }

                second == null -> {
                    first
                }

                else -> {
                    ScreenshotBounds(
                        minOf(first.left, second.left),
                        minOf(first.top, second.top),
                        maxOf(first.right, second.right),
                        maxOf(first.bottom, second.bottom),
                    )
                }
            }
        }
    }
}

/** Server-owned capture information; panel bounds enclose the samples immediately around native capture. */
@Serializable
data class ScreenshotCaptureMetadata(
    val screenWidth: Int,
    val screenHeight: Int,
    val panelBounds: ScreenshotBounds? = null,
)
