@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject

/**
 * Executes a system action via [ActionExecutor], with standard error handling.
 *
 * Checks accessibility service availability, executes the action, and returns
 * a text content response on success. Throws [McpToolException] on failure.
 *
 * @param actionName Human-readable name of the action (for error/success messages).
 * @param action Suspend function that performs the system action and returns [Result].
 * @return [CallToolResult] with confirmation message.
 */
private suspend fun executeSystemAction(
    accessibilityServiceProvider: AccessibilityServiceProvider,
    actionName: String,
    action: suspend () -> Result<Unit>,
): CallToolResult {
    if (!accessibilityServiceProvider.isReady()) {
        throw McpToolException.PermissionDenied(
            "Accessibility service not enabled. Please enable it in Android Settings > Accessibility.",
        )
    }

    val result = action()
    result.onFailure { exception ->
        throw McpToolException.ActionFailed(
            "$actionName failed: ${exception.message ?: "Unknown error"}",
        )
    }

    return McpToolUtils.textResult("$actionName executed successfully")
}

// ─────────────────────────────────────────────────────────────────────────────
// press_back
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `press_back`.
 *
 * Presses the system back button via accessibility global action.
 *
 * **Input**: `{}` (no parameters)
 * **Output**: `{ "content": [{ "type": "text", "text": "Back button press executed successfully" }] }`
 * **Errors**:
 *   - PermissionDenied if accessibility service is not enabled
 *   - ActionFailed if action execution failed
 */
class PressBackHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("UnusedParameter")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            executeSystemAction(accessibilityServiceProvider, "Back button press") {
                actionExecutor.pressBack()
            }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Presses the back button (global accessibility action). " +
                        "Returns after the action is performed.",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject {},
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "press_back"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// press_home
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `press_home`.
 *
 * Navigates to the home screen via accessibility global action.
 *
 * **Input**: `{}` (no parameters)
 * **Output**: `{ "content": [{ "type": "text", "text": "Home button press executed successfully" }] }`
 * **Errors**:
 *   - PermissionDenied if accessibility service is not enabled
 *   - ActionFailed if action execution failed
 */
class PressHomeHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("UnusedParameter")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            executeSystemAction(accessibilityServiceProvider, "Home button press") {
                actionExecutor.pressHome()
            }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description = "Navigates to the home screen. Returns after the action is performed.",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject {},
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "press_home"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// press_recents
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `press_recents`.
 *
 * Opens the recent apps screen via accessibility global action.
 *
 * **Input**: `{}` (no parameters)
 * **Output**: `{ "content": [{ "type": "text", "text": "Recents button press executed successfully" }] }`
 * **Errors**:
 *   - PermissionDenied if accessibility service is not enabled
 *   - ActionFailed if action execution failed
 */
class PressRecentsHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("UnusedParameter")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            executeSystemAction(accessibilityServiceProvider, "Recents button press") {
                actionExecutor.pressRecents()
            }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description = "Opens the recent apps screen. Returns after the action is performed.",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject {},
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "press_recents"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// open_notifications
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `open_notifications`.
 *
 * Pulls down the notification shade via accessibility global action.
 *
 * **Input**: `{}` (no parameters)
 * **Output**: `{ "content": [{ "type": "text", "text": "Open notifications executed successfully" }] }`
 * **Errors**:
 *   - PermissionDenied if accessibility service is not enabled
 *   - ActionFailed if action execution failed
 */
class OpenNotificationsHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("UnusedParameter")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            executeSystemAction(accessibilityServiceProvider, "Open notifications") {
                actionExecutor.openNotifications()
            }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description = "Pulls down the notification shade. Returns after the action is performed.",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject {},
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "open_notifications"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// open_quick_settings
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `open_quick_settings`.
 *
 * Opens the quick settings panel via accessibility global action.
 *
 * **Input**: `{}` (no parameters)
 * **Output**: `{ "content": [{ "type": "text", "text": "Open quick settings executed successfully" }] }`
 * **Errors**:
 *   - PermissionDenied if accessibility service is not enabled
 *   - ActionFailed if action execution failed
 */
class OpenQuickSettingsHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("UnusedParameter")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            executeSystemAction(accessibilityServiceProvider, "Open quick settings") {
                actionExecutor.openQuickSettings()
            }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description = "Opens the quick settings panel. Returns after the action is performed.",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject {},
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "open_quick_settings"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// dismiss_keyboard
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `dismiss_keyboard`.
 *
 * Closes the on-screen soft keyboard if one is open. No-op (and never navigates back) when no
 * keyboard is visible — see [ActionExecutor.dismissKeyboard].
 *
 * **Input**: `{}` (no parameters)
 * **Output**: text `"Keyboard dismissed"` or `"No keyboard was open"`
 * **Errors**:
 *   - PermissionDenied if accessibility service is not enabled
 *   - ActionFailed if dismissing the keyboard failed
 */
class DismissKeyboardHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("UnusedParameter")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            if (!accessibilityServiceProvider.isReady()) {
                throw McpToolException.PermissionDenied(
                    "Accessibility service not enabled. Please enable it in Android Settings > Accessibility.",
                )
            }

            val dismissed =
                actionExecutor.dismissKeyboard().getOrElse { exception ->
                    throw McpToolException.ActionFailed(
                        "Dismiss keyboard failed: ${exception.message ?: "Unknown error"}",
                    )
                }

            return McpToolUtils.textResult(
                if (dismissed) "Keyboard dismissed" else "No keyboard was open",
            )
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Closes the on-screen keyboard if open; no-op if none (never navigates back). " +
                        "Use after typing to reveal elements it covers.",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject {},
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "dismiss_keyboard"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Registration function
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Registers all system action tools with the given [Server].
 *
 * Called from [McpServerService.startServer] during server startup.
 */
fun registerSystemActionTools(
    registrar: LoggedToolRegistrar,
    actionExecutor: ActionExecutor,
    accessibilityServiceProvider: AccessibilityServiceProvider,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(PressBackHandler.TOOL_NAME)) {
        PressBackHandler(actionExecutor, accessibilityServiceProvider).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(PressHomeHandler.TOOL_NAME)) {
        PressHomeHandler(actionExecutor, accessibilityServiceProvider).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(PressRecentsHandler.TOOL_NAME)) {
        PressRecentsHandler(actionExecutor, accessibilityServiceProvider).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(OpenNotificationsHandler.TOOL_NAME)) {
        OpenNotificationsHandler(actionExecutor, accessibilityServiceProvider).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(OpenQuickSettingsHandler.TOOL_NAME)) {
        OpenQuickSettingsHandler(actionExecutor, accessibilityServiceProvider).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(DismissKeyboardHandler.TOOL_NAME)) {
        DismissKeyboardHandler(actionExecutor, accessibilityServiceProvider).register(registrar, toolNamePrefix)
    }
}
