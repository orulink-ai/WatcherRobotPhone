package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Date

@DisplayName("JwtTokenServiceImpl")
class JwtTokenServiceImplTest {
    private val secret = "test-signing-secret-0123456789-abcdef"
    private val resource = "https://host.example/mcp"
    private lateinit var service: JwtTokenServiceImpl

    @BeforeEach
    fun setUp() {
        val repo = mockk<SettingsRepository>()
        coEvery { repo.getOrCreateJwtSigningSecret() } returns secret
        service = JwtTokenServiceImpl(repo)
    }

    @Test
    @DisplayName("access token round-trips claims")
    fun accessRoundTrips() =
        runTest {
            val token = service.issueAccessToken("client-1", resource)
            val claims = service.verifyAccessToken(token)
            assertEquals("client-1", claims?.clientId)
            assertEquals(resource, claims?.audience)
        }

    @Test
    @DisplayName("refresh token round-trips jti")
    fun refreshRoundTrips() =
        runTest {
            val token = service.issueRefreshToken("client-2", "jti-xyz", resource)
            val claims = service.verifyRefreshToken(token)
            assertEquals("client-2", claims?.clientId)
            assertEquals("jti-xyz", claims?.jti)
        }

    @Test
    @DisplayName("rejects tampered token")
    fun rejectsTampered() =
        runTest {
            val token = service.issueAccessToken("client-1", resource)
            val tampered = token.dropLast(2) + "00"
            assertNull(service.verifyAccessToken(tampered))
        }

    @Test
    @DisplayName("rejects wrong type")
    fun rejectsWrongType() =
        runTest {
            val access = service.issueAccessToken("client-1", resource)
            val refresh = service.issueRefreshToken("client-1", "jti", resource)
            assertNull(service.verifyRefreshToken(access))
            assertNull(service.verifyAccessToken(refresh))
        }

    @Test
    @DisplayName("rejects expired token")
    fun rejectsExpired() =
        runTest {
            val expired =
                JWT
                    .create()
                    .withIssuer("android-remote-control-mcp")
                    .withSubject("client-1")
                    .withClaim("client_id", "client-1")
                    .withClaim("typ", "access")
                    .withAudience(resource)
                    .withJWTId("jti")
                    .withIssuedAt(Date(0))
                    .withExpiresAt(Date(1000))
                    .sign(Algorithm.HMAC256(secret))
            assertNull(service.verifyAccessToken(expired))
        }
}
