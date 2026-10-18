package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.intents.IntentDispatcher
import com.danielealbano.androidremotecontrolmcp.services.intents.SendIntentRequest
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// send_intent
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `send_intent`.
 *
 * Sends an Android intent. Supports starting activities, sending broadcasts, and
 * starting services with optional action, data, component, extras, and flags.
 *
 * **Input**: `{ "type": "activity|broadcast|service", "action": "...", ... }`
 * **Output**: `{ "content": [{ "type": "text", "text": "Intent sent successfully: ..." }] }`
 */
class SendIntentHandler
    @Inject
    constructor(
        private val intentDispatcher: IntentDispatcher,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val type = McpToolUtils.requireString(arguments, "type")
            if (type !in VALID_TYPES) {
                throw McpToolException.InvalidParams(
                    "type must be 'activity', 'broadcast', or 'service'",
                )
            }
            val action =
                McpToolUtils
                    .optionalString(arguments, "action", "")
                    .ifBlank { null }
            val data =
                McpToolUtils
                    .optionalString(arguments, "data", "")
                    .ifBlank { null }
            val component =
                McpToolUtils
                    .optionalString(arguments, "component", "")
                    .ifBlank { null }
            val packageName =
                McpToolUtils
                    .optionalString(arguments, "package", "")
                    .ifBlank { null }

            val extras = extractExtras(arguments)
            val extrasTypes = extractExtrasTypes(arguments)
            val flags = extractFlags(arguments)

            Logger.d(TAG, "Executing send_intent: type=$type, action=$action")
            val result =
                intentDispatcher.sendIntent(
                    SendIntentRequest(type, action, data, component, packageName, extras, extrasTypes, flags),
                )
            val actionLabel = action ?: "(none)"
            return McpToolUtils.handleActionResult(
                result,
                "Intent sent successfully: type=$type, action=$actionLabel",
            )
        }

        private fun extractExtras(arguments: JsonObject?): Map<String, Any?>? {
            val extrasObj = (arguments?.get("extras") as? JsonObject) ?: return null
            val result = mutableMapOf<String, Any?>()
            for ((key, value) in extrasObj) {
                convertExtra(key, value)?.let { result[key] = it }
            }
            return result.ifEmpty { null }
        }

        private fun convertExtra(
            key: String,
            value: JsonElement,
        ): Any? =
            when (value) {
                is JsonPrimitive -> {
                    convertPrimitive(value)
                }

                is JsonArray -> {
                    convertStringArray(key, value)
                }

                else -> {
                    Logger.w(TAG, "Skipping unsupported extra type for key: $key")
                    null
                }
            }

        private fun convertPrimitive(value: JsonPrimitive): Any =
            when {
                value.isString -> {
                    value.content
                }

                value.content == "true" || value.content == "false" -> {
                    value.content.toBooleanStrict()
                }

                value.content.contains('.') -> {
                    value.content.toDoubleOrNull()
                        ?: throw IllegalArgumentException(
                            "Cannot parse numeric extra: '${value.content}'",
                        )
                }

                else -> {
                    value.content.toLongOrNull()
                        ?: throw IllegalArgumentException(
                            "Cannot parse numeric extra: '${value.content}'",
                        )
                }
            }

        private fun convertStringArray(
            key: String,
            array: JsonArray,
        ): List<String>? {
            val stringList =
                array.mapNotNull { element ->
                    (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                }
            if (stringList.size != array.size) {
                Logger.w(TAG, "Skipping non-string array extra: $key")
                return null
            }
            return stringList
        }

        private fun extractExtrasTypes(arguments: JsonObject?): Map<String, String>? {
            val obj = (arguments?.get("extras_types") as? JsonObject) ?: return null
            val result = mutableMapOf<String, String>()
            for ((key, value) in obj) {
                val primitive = value as? JsonPrimitive ?: continue
                result[key] = primitive.content
            }
            return result.ifEmpty { null }
        }

        private fun extractFlags(arguments: JsonObject?): List<String>? {
            val array = (arguments?.get("flags") as? JsonArray) ?: return null
            val result =
                array.mapNotNull { item ->
                    (item as? JsonPrimitive)?.content
                }
            return result.ifEmpty { null }
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = "send_intent",
                name = "${toolNamePrefix}send_intent",
                description = TOOL_DESCRIPTION,
                inputSchema = buildInputSchema(),
            ) { request -> execute(request.arguments) }
        }

        private fun buildInputSchema(): ToolSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        putStringProperty("type", "The intent delivery type: 'activity', 'broadcast', or 'service'")
                        putStringProperty("action", "The intent action (e.g., 'android.intent.action.VIEW')")
                        putStringProperty("data", "Data URI for the intent")
                        putStringProperty("component", COMPONENT_DESCRIPTION)
                        putStringProperty("package", PACKAGE_DESCRIPTION)
                        putObjectProperty("extras", EXTRAS_DESCRIPTION)
                        putObjectProperty("extras_types", EXTRAS_TYPES_DESCRIPTION)
                        putJsonObject("flags") {
                            put("type", "array")
                            putJsonObject("items") { put("type", "string") }
                            put("description", FLAGS_DESCRIPTION)
                        }
                    },
                required = listOf("type"),
            )

        companion object {
            const val TOOL_NAME = "send_intent"
            private const val TAG = "MCP:SendIntentTool"
            private val VALID_TYPES = setOf("activity", "broadcast", "service")
            private const val TOOL_DESCRIPTION =
                "Send an Android intent. Supports starting activities, " +
                    "sending broadcasts, and starting services. Use for opening specific " +
                    "settings pages, triggering app-specific actions, or sending broadcasts."
            private const val COMPONENT_DESCRIPTION =
                "Target component as 'package/class' " +
                    "(e.g., 'com.example.app/com.example.app.MyActivity')"
            private const val PACKAGE_DESCRIPTION =
                "Target package scoping delivery (maps to Intent.setPackage(), " +
                    "equivalent to 'am ... -p'). For broadcasts this reaches a " +
                    "manifest-declared receiver in that app without needing the " +
                    "receiver's class name (e.g., 'com.example.app')"
            private const val EXTRAS_DESCRIPTION =
                "Key-value extras. Values auto-typed: " +
                    "string→String, integer→Int/Long, decimal→Double, " +
                    "boolean→Boolean, string array→StringArrayList"
            private const val EXTRAS_TYPES_DESCRIPTION =
                "Type overrides for extras keys. " +
                    "Supported: 'string', 'int', 'long', 'float', 'double', 'boolean'"
            private const val FLAGS_DESCRIPTION =
                "Intent flag names (e.g., 'FLAG_ACTIVITY_CLEAR_TOP'). " +
                    "FLAG_ACTIVITY_NEW_TASK auto-added for activity type."
        }
    }

private fun JsonObjectBuilder.putStringProperty(
    name: String,
    description: String,
) = putJsonObject(name) {
    put("type", "string")
    put("description", description)
}

private fun JsonObjectBuilder.putObjectProperty(
    name: String,
    description: String,
) = putJsonObject(name) {
    put("type", "object")
    put("description", description)
}

// ─────────────────────────────────────────────────────────────────────────────
// open_uri
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `open_uri`.
 *
 * Opens a URI using Android's `ACTION_VIEW`. Handles https, http, tel, mailto,
 * geo, content URLs, deep links, and custom app schemes.
 *
 * **Input**: `{ "uri": "<string>", "package_name": "<string>?", "mime_type": "<string>?" }`
 * **Output**: `{ "content": [{ "type": "text", "text": "URI opened successfully: ..." }] }`
 */
class OpenUriHandler
    @Inject
    constructor(
        private val intentDispatcher: IntentDispatcher,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val uri = McpToolUtils.requireString(arguments, "uri")
            val packageName =
                McpToolUtils
                    .optionalString(arguments, "package_name", "")
                    .ifEmpty { null }
            val mimeType =
                McpToolUtils
                    .optionalString(arguments, "mime_type", "")
                    .ifEmpty { null }

            Logger.d(TAG, "Executing open_uri")
            val result = intentDispatcher.openUri(uri, packageName, mimeType)
            return McpToolUtils.handleActionResult(result, "URI opened successfully: $uri")
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = "open_uri",
                name = "${toolNamePrefix}open_uri",
                description =
                    "Open a URI using Android's ACTION_VIEW. Handles https://, http://, " +
                        "tel:, mailto:, geo:, content:// URLs, deep links, and custom app schemes " +
                        "(e.g., whatsapp://send?phone=...).",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("uri") {
                                    put("type", "string")
                                    put("description", "The URI to open")
                                }
                                putJsonObject("package_name") {
                                    put("type", "string")
                                    put("description", "Force a specific app to handle the URI")
                                }
                                putJsonObject("mime_type") {
                                    put("type", "string")
                                    put("description", "MIME type hint (useful for content:// URIs)")
                                }
                            },
                        required = listOf("uri"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "open_uri"
            private const val TAG = "MCP:OpenUriTool"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Registration function
// ─────────────────────────────────────────────────────────────────────────────

fun registerIntentTools(
    registrar: LoggedToolRegistrar,
    intentDispatcher: IntentDispatcher,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(SendIntentHandler.TOOL_NAME)) {
        SendIntentHandler(intentDispatcher).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(OpenUriHandler.TOOL_NAME)) {
        OpenUriHandler(intentDispatcher).register(registrar, toolNamePrefix)
    }
}
