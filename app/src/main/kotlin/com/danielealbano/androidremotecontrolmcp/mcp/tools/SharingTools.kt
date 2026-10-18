@file:Suppress("MatchingDeclarationName")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.currentRequestBaseUrl
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.sharing.EphemeralFileLinkService
import com.danielealbano.androidremotecontrolmcp.services.sharing.SharedContentClassifier
import com.danielealbano.androidremotecontrolmcp.services.sharing.SharedContentInbox
import com.danielealbano.androidremotecontrolmcp.services.sharing.SharedItem
import com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProvider
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// ─────────────────────────────────────────────────────────────────────────────
// get_shared_content
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_shared_content`.
 *
 * Drains the [SharedContentInbox] (consume-on-read) and returns each item: text inline; images as a
 * downscaled [ImageContent] plus a full-res capability URL; other files as a capability URL. The
 * untrusted-content warning is the first content block (device-derived content).
 */
class GetSharedContentHandler(
    private val inbox: SharedContentInbox,
    private val linkService: EphemeralFileLinkService,
    private val baseUrlProvider: () -> String,
    private val privacyToolGate: PrivacyToolGate,
) {
    suspend fun execute(): CallToolResult {
        val items = inbox.drainAll()
        if (items.isEmpty()) {
            return McpToolUtils.untrustedTextResult(
                "note:no shared content available. Share text/an image/a file to this app, then call this tool.",
            )
        }

        val content = mutableListOf<ContentBlock>()
        for (item in items) {
            appendItem(item, content)
        }

        // Redact every device-derived text block; image/binary blocks pass through unchanged.
        val textIndices = content.indices.filter { content[it] is TextContent }
        val redacted =
            privacyToolGate.texts(textIndices.map { (content[it] as TextContent).text to "shared content" })
        textIndices.forEachIndexed { batchIndex, contentIndex ->
            content[contentIndex] = TextContent(text = redacted[batchIndex].orEmpty())
        }

        return McpToolUtils.untrustedResult(content)
    }

    /** Appends the rendering of [item] to [content]. */
    private suspend fun appendItem(
        item: SharedItem,
        content: MutableList<ContentBlock>,
    ) {
        when {
            item.kind == SharedItem.Kind.TEXT -> {
                content += TextContent(text = item.text.orEmpty())
            }

            SharedContentClassifier.isImage(item.mimeType) && item.bytes != null -> {
                appendImageItem(item, item.bytes, content)
            }

            SharedContentClassifier.isTextual(item.mimeType) && item.bytes != null -> {
                // Textual files are returned inline (per the content-mapping decision), not as a URL.
                val name = item.fileName ?: "file"
                content +=
                    TextContent(
                        text =
                            "File '$name' ${item.mimeType} (${item.sizeBytes} bytes):\n" +
                                String(item.bytes, Charsets.UTF_8),
                    )
            }

            item.bytes != null -> {
                val name = item.fileName ?: "file"
                val url =
                    currentRequestBaseUrl { baseUrlProvider() } +
                        linkService.pathFor(linkService.register(item.bytes, item.mimeType, name))
                content +=
                    TextContent(
                        text =
                            "File '$name' ${item.mimeType} (${item.sizeBytes} bytes) at $url (expires 1h). " +
                                "You can web_fetch text/PDF URLs to read them; other types are download-only.",
                    )
            }

            else -> {
                // Defensive: a non-text item carrying no bytes has nothing to serve. Surface it rather than
                // dropping it silently (not reachable from the share receiver, which always attaches bytes).
                content +=
                    TextContent(
                        text =
                            "A shared item ('${item.fileName ?: "unknown"}', ${item.mimeType}) " +
                                "had no readable content and was skipped.",
                    )
            }
        }
    }

    /** Appends a downscaled inline image plus a full-res capability URL for [bytes] to [content]. */
    private suspend fun appendImageItem(
        item: SharedItem,
        bytes: ByteArray,
        content: MutableList<ContentBlock>,
    ) {
        val name = item.fileName ?: "image"
        val inline = runCatching { SharedContentClassifier.downscaleToInline(bytes, item.mimeType) }.getOrNull()
        val url =
            currentRequestBaseUrl { baseUrlProvider() } +
                linkService.pathFor(linkService.register(bytes, item.mimeType, name))
        if (inline != null) {
            content += ImageContent(data = inline.base64, mimeType = inline.mimeType)
            content +=
                TextContent(
                    text =
                        "Image '$name' (${item.sizeBytes} bytes). Original (full-res) at $url " +
                            "(expires 1h). Only share this URL with the user if they ask for the original.",
                )
        } else {
            content +=
                TextContent(
                    text =
                        "Image '$name' ${item.mimeType} (${item.sizeBytes} bytes) could not be decoded; " +
                            "download it at $url (expires 1h).",
                )
        }
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Returns and clears items shared to this app via Android Share (text, images, files); " +
                    "read-once (re-share to read again). Prefer this over the clipboard when content can be " +
                    "shared directly to this app. Text and textual files are returned inline; images return a " +
                    "downscaled view plus a full-res download URL; other files return a fetch URL " +
                    "(web_fetch reads PDF).",
            inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
        ) { execute() }
    }

    companion object {
        const val TOOL_NAME = "get_shared_content"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// share_file_via_web
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `share_file_via_web`.
 *
 * Reads an existing device file (via the authorized-location model) into memory, registers it as a
 * capability link, and returns a temporary fetch URL plus metadata. The untrusted-content warning is
 * included because the filename/MIME are device-derived. Files larger than the configured limit are rejected.
 */
class ShareFileViaWebHandler(
    private val fileOperationProvider: FileOperationProvider,
    private val linkService: EphemeralFileLinkService,
    private val fileSizeLimitMb: Int,
    private val baseUrlProvider: () -> String,
    private val privacyToolGate: PrivacyToolGate,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val locationId = McpToolUtils.requireString(arguments, "location_id")
        val path = McpToolUtils.requireString(arguments, "path")

        val result = readOrThrow(locationId, path)
        val token = linkService.register(result.bytes, result.mimeType, result.fileName)
        val url = currentRequestBaseUrl { baseUrlProvider() } + linkService.pathFor(token)

        // The filename is device-derived and can carry PII (e.g. Passport_John_Doe.pdf); redact it.
        val displayName = privacyToolGate.text(result.fileName, "file name")
        val message =
            "File '$displayName' ${result.mimeType} (${result.sizeBytes} bytes) at $url " +
                "(expires 1h). web_fetch can read text/PDF; other types are download-only."
        return McpToolUtils.untrustedTextResult(message)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun readOrThrow(
        locationId: String,
        path: String,
    ) = try {
        // The served bytes live in RAM in the link registry, so the effective cap is the smaller of the
        // configured file-size limit and the registry's total in-memory budget.
        val maxBytes = minOf(fileSizeLimitMb.toLong() * BYTES_PER_MB, EphemeralFileLinkService.MAX_TOTAL_BYTES)
        fileOperationProvider.readFileBytes(locationId, path, maxBytes)
    } catch (e: McpToolException) {
        throw e
    } catch (e: Exception) {
        throw McpToolException.ActionFailed(
            "Could not read the file (it may be missing or exceed the configured size limit): " +
                (e.message ?: "unknown error"),
            e,
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
                "Exposes a device file (location_id + path) as a temporary fetch URL (expires 1h, " +
                    "multiple fetches allowed). Use to let the agent read a device PDF/text via web_fetch, " +
                    "or to give the user a download link. The file is served from memory, so it is limited to " +
                    "64 MB or the configured file-size limit, whichever is smaller.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("location_id") {
                                put("type", "string")
                                put("description", "The authorized storage location identifier")
                            }
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "Relative path to the file within the location")
                            }
                        },
                    required = listOf("location_id", "path"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "share_file_via_web"
        private const val BYTES_PER_MB = 1024L * 1024L
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Registration
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Registers the sharing tools. The capability-link plumbing is shared by both tools.
 */
@Suppress("LongParameterList")
fun registerSharingTools(
    registrar: LoggedToolRegistrar,
    inbox: SharedContentInbox,
    linkService: EphemeralFileLinkService,
    fileOperationProvider: FileOperationProvider,
    fileSizeLimitMb: Int,
    baseUrlProvider: () -> String,
    privacyToolGate: PrivacyToolGate,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(GetSharedContentHandler.TOOL_NAME)) {
        GetSharedContentHandler(inbox, linkService, baseUrlProvider, privacyToolGate)
            .register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(ShareFileViaWebHandler.TOOL_NAME)) {
        ShareFileViaWebHandler(
            fileOperationProvider,
            linkService,
            fileSizeLimitMb,
            baseUrlProvider,
            privacyToolGate,
        ).register(registrar, toolNamePrefix)
    }
}
