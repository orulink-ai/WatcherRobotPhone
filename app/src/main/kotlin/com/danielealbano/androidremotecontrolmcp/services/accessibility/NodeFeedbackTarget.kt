package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** A pre-action identity snapshot, revalidated after the visible pointer approach. Never logged. */
internal data class NodeFeedbackTarget(
    val bounds: BoundsData,
    val windowId: Int,
    val viewId: String?,
    val uniqueId: String?,
    val className: String?,
    val text: String?,
    val description: String?,
) {
    companion object {
        fun from(node: AccessibilityNodeInfo): NodeFeedbackTarget {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            return NodeFeedbackTarget(
                BoundsData(bounds.left, bounds.top, bounds.right, bounds.bottom),
                node.windowId,
                node.viewIdResourceName,
                node.uniqueId,
                node.className?.toString(),
                node.text?.toString(),
                node.contentDescription?.toString(),
            )
        }
    }
}
