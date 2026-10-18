package com.danielealbano.androidremotecontrolmcp.services.accessibility

import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolCallIndicator

/** Displays a touch-through accessibility overlay while an MCP tool is executing. */
class AccessibilityToolCallIndicator : ToolCallIndicator {
    @Volatile
    private var enabled = true

    override fun onToolCallStarted(toolName: String) {
        if (enabled) McpAccessibilityService.showToolCallIndicator(toolName)
    }

    override fun onToolCallFinished(toolName: String) {
        McpAccessibilityService.hideToolCallIndicator()
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) McpAccessibilityService.hideToolCallIndicator()
    }
}
