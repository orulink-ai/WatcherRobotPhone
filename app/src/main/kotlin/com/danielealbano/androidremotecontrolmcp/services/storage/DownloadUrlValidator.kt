package com.danielealbano.androidremotecontrolmcp.services.storage

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import java.net.MalformedURLException
import java.net.URL

/**
 * Parses [url] and validates it for downloading: only HTTP/HTTPS are allowed, and plain HTTP requires
 * [ServerConfig.allowHttpDownloads]. Throws [McpToolException.ActionFailed] on a malformed or disallowed URL.
 */
internal fun parseAndValidateDownloadUrl(
    url: String,
    config: ServerConfig,
): URL {
    val parsedUrl =
        try {
            URL(url)
        } catch (e: MalformedURLException) {
            throw McpToolException.ActionFailed("Invalid URL: $url", e)
        }
    val errorMessage =
        when {
            parsedUrl.protocol != "http" && parsedUrl.protocol != "https" -> {
                "Unsupported URL protocol: ${parsedUrl.protocol}. Only HTTP and HTTPS are supported."
            }

            parsedUrl.protocol == "http" && !config.allowHttpDownloads -> {
                "HTTP downloads are not allowed. Enable 'Allow HTTP Downloads' in settings, or use HTTPS."
            }

            else -> {
                null
            }
        }
    if (errorMessage != null) {
        throw McpToolException.ActionFailed(errorMessage)
    }
    return parsedUrl
}
