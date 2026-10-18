package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import com.danielealbano.androidremotecontrolmcp.data.repository.OAuthClientRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import com.danielealbano.androidremotecontrolmcp.geo.GeoIpResolver
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Collaborators for the OAuth HTTP layer. [nowMs] is injectable for tests; [publicUrlOverride] pins the
 * host used by every metadata/`aud`/redirect (empty = auto-detect). Wraps [OAuthServerDeps] with
 * delegating getters so the constructor stays within detekt's `LongParameterList` threshold while
 * carrying the extra [serverLog] sink (no `@Suppress` needed).
 */
class OAuthRouteDeps(
    private val oauth: OAuthServerDeps,
    val publicUrlOverride: String,
    val serverLog: ServerLogRepository,
) {
    val clientRepository: OAuthClientRepository get() = oauth.oauthClientRepository
    val tokenService: JwtTokenService get() = oauth.jwtTokenService
    val authorizationCodeStore: AuthorizationCodeStore get() = oauth.authorizationCodeStore
    val approvalCoordinator: OAuthApprovalCoordinator get() = oauth.approvalCoordinator
    val geoIpResolver: GeoIpResolver get() = oauth.geoIpResolver

    /** Clock seam (defaulted; not a constructor param to keep the list small). */
    val nowMs: () -> Long = { System.currentTimeMillis() }
}

/**
 * Best-effort source IP of the request: a tunnel's forwarded header (`CF-Connecting-IP` then the first
 * `X-Forwarded-For` hop) if present, otherwise the socket peer. Header values are client-settable, but the
 * IP is informational and the response is per-connection (see RequestBaseUrl's trust rationale).
 */
fun ApplicationCall.clientIp(): String? {
    val forwarded =
        request.headers["CF-Connecting-IP"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.headers["X-Forwarded-For"]
                ?.substringBefore(',')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
    return forwarded ?: request.origin.remoteAddress.takeIf { it.isNotEmpty() }
}

internal data class PendingAuthorizeRequest(
    val clientId: String,
    val redirectUri: String,
    val codeChallenge: String,
    val resource: String,
    val scope: String,
    val state: String,
    val expiresAtMs: Long,
)

/** Server-lifetime, single-use, expiry-bounded store of approved-but-unredeemed authorize requests. */
internal class PendingAuthorizeStore {
    private val mutex = Mutex()
    private val map = mutableMapOf<String, PendingAuthorizeRequest>()

    suspend fun put(
        id: String,
        request: PendingAuthorizeRequest,
        nowMs: Long,
    ) = mutex.withLock {
        purgeLocked(nowMs)
        map[id] = request
    }

    suspend fun consume(
        id: String,
        nowMs: Long,
    ): PendingAuthorizeRequest? =
        mutex.withLock {
            purgeLocked(nowMs)
            map.remove(id)
        }

    private fun purgeLocked(nowMs: Long) {
        map.entries.removeAll { it.value.expiresAtMs <= nowMs }
    }
}
