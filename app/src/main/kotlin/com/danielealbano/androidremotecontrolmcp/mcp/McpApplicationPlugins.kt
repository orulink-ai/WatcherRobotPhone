package com.danielealbano.androidremotecontrolmcp.mcp

import com.danielealbano.androidremotecontrolmcp.mcp.auth.McpAuthConfig
import com.danielealbano.androidremotecontrolmcp.mcp.auth.McpAuthPlugin
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson

/**
 * Installs the MCP application base plugins in the ONE canonical order shared by production
 * ([McpServer.configureApplication]) and the integration tests:
 *
 * 1. [ContentNegotiation] with `json(McpJson)` — required by the SDK Streamable HTTP transport.
 * 2. [configureCors] — installs Ktor's CORS plugin (Validators phase since Ktor 3.5).
 * 3. [McpAuthPlugin] — combined bearer/OAuth authentication, configured by the caller.
 *
 * The CORS-before-auth execution order (so token-less browser preflight `OPTIONS` are answered by CORS
 * instead of being failed closed by auth, and 401s still carry `Access-Control-Allow-Origin`) is
 * guaranteed by [McpAuthPlugin] running in a phase inserted after the CORS `Validators` phase — NOT by
 * install order. Centralizing the wiring here still keeps production and tests identical, and the
 * integration tests exercise it directly so a regression cannot pass unnoticed.
 *
 * @param configureAuth Caller-supplied [McpAuthConfig] block (tokens, OAuth validator, exclusions).
 */
fun Application.installMcpBasePlugins(configureAuth: McpAuthConfig.() -> Unit) {
    install(ContentNegotiation) { json(McpJson) }
    configureCors()
    install(McpAuthPlugin, configureAuth)
}
