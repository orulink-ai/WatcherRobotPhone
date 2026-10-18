package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Abstracts access to the Android accessibility service singleton.
 *
 * Tools use this interface instead of accessing [McpAccessibilityService.instance] directly,
 * enabling JVM-based testing with mock implementations.
 */
interface AccessibilityServiceProvider {
    fun getRootNode(): AccessibilityNodeInfo?

    fun getAccessibilityWindows(): List<AccessibilityWindowInfo>

    fun getCurrentPackageName(): String?

    fun getCurrentActivityName(): String?

    fun getScreenInfo(): ScreenInfo

    fun isReady(): Boolean

    fun getContext(): Context?

    /**
     * Drops the Android accessibility framework's node cache held for this service (the
     * `AccessibilityInteractionClient` cache that backs [getRootNode] / [getAccessibilityWindows]
     * and node traversal). No-op when the service is not connected.
     *
     * The framework invalidates this cache only on incoming `AccessibilityEvent`s. Chromium WebView
     * suppresses/throttles the `TYPE_WINDOW_CONTENT_CHANGED` events that would invalidate it
     * (on-demand event dispatch, content-change throttling, auto-disable of the a11y engine), so a
     * JavaScript-driven DOM text change can leave the cache — and every subsequent read — stale.
     * Callers clear the cache immediately before a fresh tree read so the reads round-trip live to
     * the app, mirroring Appium UiAutomator2's "reset accessibility cache before page source".
     */
    fun clearFrameworkNodeCache()
}
