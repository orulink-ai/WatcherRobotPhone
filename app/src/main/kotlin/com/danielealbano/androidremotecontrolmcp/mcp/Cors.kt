package com.danielealbano.androidremotecontrolmcp.mcp

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS

/** Request header carrying the negotiated MCP protocol version. */
const val MCP_PROTOCOL_VERSION_HEADER = "mcp-protocol-version"

/** Request/response header carrying the Streamable HTTP session id. */
const val MCP_SESSION_ID_HEADER = "mcp-session-id"

/** Preflight cache lifetime (1 hour) — avoids re-issuing a preflight for nearly every `/mcp` call. */
private const val CORS_MAX_AGE_SECONDS = 3600L

/**
 * Installs permissive CORS so browser-based MCP clients (e.g. the MCP Inspector) can complete the
 * OAuth discovery/DCR/authorize flow AND the `/mcp` protocol exchange. Without it the browser's
 * preflight `OPTIONS` and cross-origin requests are blocked before reaching any handler.
 *
 * Must run BEFORE [com.danielealbano.androidremotecontrolmcp.mcp.auth.McpAuthPlugin], which fails closed
 * on any token-less request to `/mcp` — including a preflight `OPTIONS`, which carries no `Authorization`
 * header. Ktor's CORS plugin registers in the `Validators` phase; [McpAuthPlugin] therefore runs in a
 * phase inserted after `Validators` (before `Call`) so CORS answers the preflight and decorates
 * responses before auth runs. This wiring is centralized in [installMcpBasePlugins] so production and
 * tests cannot drift.
 *
 * Any origin is allowed ([anyHost]) — this does NOT weaken auth: `/mcp` still requires the bearer or
 * OAuth token (which a cross-origin page cannot obtain) and the OAuth flow is still gated by the
 * on-device number-match approval. Credentials mode is deliberately NOT enabled (auth travels in the
 * `Authorization` header, not cookies), which is what permits the wildcard origin — the two are
 * mutually exclusive per the CORS spec. For every non-browser client (Claude.ai backend, `mcp-remote`,
 * Claude Desktop) there is no `Origin` header, so the CORS plugin is a complete no-op and OAuth /
 * bearer behavior is byte-identical to before.
 *
 * **Trade-off (open mode / DNS rebinding).** The wildcard removes the browser same-origin barrier that
 * previously blocked a cross-origin `application/json` POST to `/mcp`. With the defaults (loopback
 * binding + auth enabled) this is fully mitigated — a cross-origin page cannot obtain a token, so
 * `/mcp` returns 401. But if the user runs in OPEN mode (both `bearer_token_enabled` and
 * `oauth_enabled` disabled — an explicit, warned-about choice) on a network-reachable binding, a
 * malicious page in the victim's browser could now drive the tool surface. No `Origin`/`Host`
 * allowlist is enforced because the device's public host is dynamic (changing IPs, Cloudflare/ngrok
 * tunnels, `public_url_override`), so any static allowlist would break remote access. Stay on loopback
 * and keep at least one auth method enabled unless the network is trusted — see PROJECT.md
 * "Network Security".
 *
 * Response headers are exposed so browser clients can read them via `fetch()` (non-safelisted response
 * headers are hidden from JS otherwise):
 * - [MCP_SESSION_ID_HEADER] — the session id the Streamable HTTP transport returns, replayed on
 *   follow-up requests.
 * - `WWW-Authenticate` — carries the RFC 9728 `resource_metadata` pointer on a 401, which is how a
 *   browser MCP client discovers the OAuth authorization server. Not exposing it would defeat the
 *   header the auth plugin sends specifically for this discovery.
 *
 * [MCP_PROTOCOL_VERSION_HEADER] is allowed as a REQUEST header (MCP 2025-06-18 clients send it) but is
 * NOT exposed, because the SDK transport does not emit it as a response header.
 */
fun Application.configureCors() {
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(MCP_PROTOCOL_VERSION_HEADER)
        allowHeader(MCP_SESSION_ID_HEADER)
        exposeHeader(MCP_SESSION_ID_HEADER)
        exposeHeader(HttpHeaders.WWWAuthenticate)
        maxAgeInSeconds = CORS_MAX_AGE_SECONDS
        // MCP POSTs use `Content-Type: application/json`, a non-simple content type whose preflight
        // Ktor rejects unless explicitly allowed.
        allowNonSimpleContentTypes = true
    }
}
