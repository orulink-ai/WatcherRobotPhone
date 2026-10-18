package com.danielealbano.androidremotecontrolmcp.data.repository

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinPermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val TAG = "MCP:SettingsCodec"

@Suppress("TooGenericExceptionCaught")
internal fun parseBuiltinPermissionsJson(json: String): Map<String, BuiltinPermissions> =
    try {
        val root = Json.parseToJsonElement(json).jsonObject
        root.entries.associate { (key, value) ->
            val obj = value.jsonObject
            key to
                BuiltinPermissions(
                    allowWrite = obj["allowWrite"]?.jsonPrimitive?.booleanOrNull ?: false,
                    allowDelete = obj["allowDelete"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse builtin location permissions JSON", e)
        emptyMap()
    }

internal fun serializeBuiltinPermissions(perms: Map<String, BuiltinPermissions>): String =
    Json.encodeToString(
        buildJsonObject {
            for ((key, value) in perms) {
                put(
                    key,
                    buildJsonObject {
                        put("allowWrite", value.allowWrite)
                        put("allowDelete", value.allowDelete)
                    },
                )
            }
        },
    )

@Suppress("SwallowedException", "TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")
internal fun parseStoredLocationsJson(json: String?): List<SettingsRepository.StoredLocation> {
    if (json == null) return emptyList()
    return try {
        val jsonArray = Json.parseToJsonElement(json).jsonArray
        jsonArray.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val path = obj["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val treeUri = obj["treeUri"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val description = obj["description"]?.jsonPrimitive?.content ?: ""
                val allowWriteElement = obj["allowWrite"]
                // Missing allowWrite in persisted JSON means this is pre-permission-fields
                // data; default to true for backwards compatibility (old locations were
                // implicitly full-access).
                val allowWrite =
                    if (allowWriteElement == null || allowWriteElement is JsonNull) {
                        true
                    } else {
                        allowWriteElement.jsonPrimitive.booleanOrNull ?: false
                    }
                val allowDeleteElement = obj["allowDelete"]
                // Missing allowDelete in persisted JSON means this is pre-permission-fields
                // data; default to true for backwards compatibility (old locations were
                // implicitly full-access).
                val allowDelete =
                    if (allowDeleteElement == null || allowDeleteElement is JsonNull) {
                        true
                    } else {
                        allowDeleteElement.jsonPrimitive.booleanOrNull ?: false
                    }
                SettingsRepository.StoredLocation(
                    id = id,
                    name = name,
                    path = path,
                    description = description,
                    treeUri = treeUri,
                    allowWrite = allowWrite,
                    allowDelete = allowDelete,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed stored location entry", e)
                null
            }
        }
    } catch (_: Exception) {
        // Migration: try parsing old format (JSON object: {"locationId": "treeUri"}).
        // Old-format locations pre-date permission fields and were implicitly
        // full-access, so allowWrite and allowDelete default to true.
        try {
            val jsonObject = Json.parseToJsonElement(json).jsonObject
            jsonObject.map { (key, value) ->
                SettingsRepository.StoredLocation(
                    id = key,
                    name = key.substringAfterLast("/"),
                    path = "/",
                    description = "",
                    treeUri = value.jsonPrimitive.content,
                    allowWrite = true,
                    allowDelete = true,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse stored locations JSON, returning empty list", e)
            emptyList()
        }
    }
}

internal fun serializeStoredLocationsJson(locations: List<SettingsRepository.StoredLocation>): String =
    Json.encodeToString(
        buildJsonArray {
            for (loc in locations) {
                add(
                    buildJsonObject {
                        put("id", loc.id)
                        put("name", loc.name)
                        put("path", loc.path)
                        put("description", loc.description)
                        put("treeUri", loc.treeUri)
                        put("allowWrite", loc.allowWrite)
                        put("allowDelete", loc.allowDelete)
                    },
                )
            }
        },
    )
