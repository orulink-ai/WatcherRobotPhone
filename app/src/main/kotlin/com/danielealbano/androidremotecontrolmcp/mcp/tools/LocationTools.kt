@file:Suppress("MatchingDeclarationName")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.location.LocationProvider
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// get_location
// ─────────────────────────────────────────────────────────────────────────────

class GetLocationHandler
    @Inject
    constructor(
        private val locationProvider: LocationProvider,
        private val privacyToolGate: PrivacyToolGate,
    ) {
        suspend fun execute(
            arguments: JsonObject?,
            freshFixParamEnabled: Boolean,
        ): CallToolResult {
            Log.d(TAG, "Executing get_location")

            val requestedFreshFix =
                McpToolUtils.optionalBoolean(arguments, "fresh_fix", false)
            val freshFix = if (freshFixParamEnabled) requestedFreshFix else false

            val result = locationProvider.getLocation(freshFix)

            if (result.isFailure) {
                val exception = result.exceptionOrNull()!!
                val message = exception.message ?: "Unknown error"
                if (exception is SecurityException) {
                    throw McpToolException.PermissionDenied(message)
                }
                throw McpToolException.ActionFailed(message)
            }

            val locationData = result.getOrThrow()
            val redactedStreet = privacyToolGate.text(locationData.street, "address")
            val jsonResult =
                buildJsonObject {
                    put("latitude", locationData.latitude)
                    put("longitude", locationData.longitude)
                    put("accuracy_meters", locationData.accuracyMeters)
                    put("street", redactedStreet)
                }

            return McpToolUtils.untrustedTextResult(jsonResult.toString())
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
            freshFixParamEnabled: Boolean,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Retrieves the device's current location including coordinates, accuracy, " +
                        "and street address. Returns latitude, longitude, accuracy in meters " +
                        "(68% confidence radius), and street address (may be null if reverse " +
                        "geocoding is unavailable). Parameter 'fresh_fix': if true, requests a " +
                        "fresh GPS fix which may take up to 10 seconds; if false (default), " +
                        "returns the last known location which is faster but may be stale. " +
                        "Requires ACCESS_FINE_LOCATION permission to be granted on the device. " +
                        "Requires Google Play Services to be available on the device.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("fresh_fix") {
                                    put("type", "boolean")
                                    put(
                                        "description",
                                        "If true, requests a fresh GPS fix (may take up to " +
                                            "10 seconds). If false (default), returns last " +
                                            "known location (faster but possibly stale).",
                                    )
                                }
                            },
                        required = emptyList(),
                    ),
            ) { request -> execute(request.arguments, freshFixParamEnabled) }
        }

        companion object {
            const val TOOL_NAME = "get_location"
            private const val TAG = "MCP:GetLocationHandler"
        }
    }

fun registerLocationTools(
    registrar: LoggedToolRegistrar,
    locationProvider: LocationProvider,
    privacyToolGate: PrivacyToolGate,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(GetLocationHandler.TOOL_NAME)) {
        GetLocationHandler(locationProvider, privacyToolGate).register(
            registrar,
            toolNamePrefix,
            freshFixParamEnabled =
                perms.isParamEnabled(
                    GetLocationHandler.TOOL_NAME,
                    "fresh_fix",
                ),
        )
    }
}
