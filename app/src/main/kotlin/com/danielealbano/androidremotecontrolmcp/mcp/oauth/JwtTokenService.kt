package com.danielealbano.androidremotecontrolmcp.mcp.oauth

/** Claims recovered from a verified OAuth access token. */
data class AccessTokenClaims(
    val clientId: String,
    val audience: String,
)

/** Claims recovered from a verified OAuth refresh token. */
data class RefreshTokenClaims(
    val clientId: String,
    val jti: String,
)

/**
 * Issues and verifies HS256 OAuth tokens for the self-contained authorization server. The signing
 * secret is device-held (never published as a JWKS), so the same service both mints and validates.
 * Both access and refresh tokens carry `aud = resource` and a `client_id` claim.
 */
interface JwtTokenService {
    suspend fun issueAccessToken(
        clientId: String,
        resource: String,
    ): String

    suspend fun issueRefreshToken(
        clientId: String,
        jti: String,
        resource: String,
    ): String

    suspend fun verifyAccessToken(token: String): AccessTokenClaims?

    suspend fun verifyRefreshToken(token: String): RefreshTokenClaims?
}
