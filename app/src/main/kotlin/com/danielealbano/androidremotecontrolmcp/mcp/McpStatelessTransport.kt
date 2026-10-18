package com.danielealbano.androidremotecontrolmcp.mcp

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import kotlinx.coroutines.withContext

/**
 * Installs the MCP stateless Streamable HTTP transport at `/mcp`, together with a per-request
 * [RequestBaseUrlElement] scoped to the `/mcp` route so MCP tool handlers can build absolute
 * links (e.g. ephemeral file links).
 *
 * The base-URL element is installed as a route-level interceptor on the same `/mcp` route node
 * the transport uses, so it is present only for requests dispatched to `/mcp` (the only route
 * whose handlers read [RequestBaseUrlElement]). Each stateless POST is a fresh, never-initialized
 * session, so the SDK dispatches tool-call handlers inline in the request coroutine — which is why
 * wrapping [proceed] in `withContext` correctly reaches the handler.
 *
 * DNS-rebinding protection is disabled: requests arrive via a cloudflared/ngrok tunnel, so the
 * `Host` header is the tunnel hostname (not localhost) and the SDK's localhost-default validation
 * would reject all legitimate traffic. Bearer/OAuth authentication is the compensating control.
 *
 * Shared by [McpServer] and the integration tests so production and test wiring cannot drift.
 *
 * @param publicUrlOverride Pins the base URL host; empty means derive it from the request.
 * @param block Provider for the MCP [Server] instance to serve each request.
 */
fun Application.installMcpStatelessTransport(
    publicUrlOverride: String = "",
    block: () -> Server,
) {
    routing {
        route("/mcp") {
            intercept(ApplicationCallPipeline.Plugins) {
                withContext(RequestBaseUrlElement(effectiveBaseUrl(call, publicUrlOverride))) {
                    proceed()
                }
            }
        }
    }
    mcpStatelessStreamableHttp(
        path = "/mcp",
        enableDnsRebindingProtection = false,
    ) {
        block()
    }
}
