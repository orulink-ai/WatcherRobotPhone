package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.OAuthClientRepository
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OAuthAccessValidator")
class OAuthAccessValidatorTest {
    private val resource = "https://host.example/mcp"
    private lateinit var tokenService: JwtTokenService
    private lateinit var clientRepository: OAuthClientRepository
    private lateinit var serverLog: RecordingServerLogRepository

    private fun client(
        id: String,
        clientName: String? = null,
        lastUsedAtMs: Long = 0L,
    ) = OAuthClient(
        clientId = id,
        clientName = clientName,
        redirectUris = listOf("https://claude.ai/api/mcp/auth_callback"),
        createdAtMs = 0L,
        lastUsedAtMs = lastUsedAtMs,
    )

    @BeforeEach
    fun setUp() {
        tokenService = mockk()
        clientRepository = mockk(relaxUnitFun = true)
        serverLog = RecordingServerLogRepository()
    }

    @Test
    @DisplayName("valid token for registered client passes")
    fun validPasses() =
        runTest {
            coEvery { tokenService.verifyAccessToken("tok") } returns AccessTokenClaims("c1", resource)
            coEvery { clientRepository.getClient("c1") } returns client("c1")
            val validator = OAuthAccessValidator(tokenService, clientRepository, serverLog)
            assertTrue(validator.validate("tok", resource))
        }

    @Test
    @DisplayName("wrong aud rejected")
    fun wrongAudRejected() =
        runTest {
            coEvery { tokenService.verifyAccessToken("tok") } returns AccessTokenClaims("c1", "https://other/mcp")
            coEvery { clientRepository.getClient("c1") } returns client("c1")
            val validator = OAuthAccessValidator(tokenService, clientRepository, serverLog)
            assertFalse(validator.validate("tok", resource))
        }

    @Test
    @DisplayName("revoked client rejected")
    fun revokedRejected() =
        runTest {
            coEvery { tokenService.verifyAccessToken("tok") } returns AccessTokenClaims("c1", resource)
            coEvery { clientRepository.getClient("c1") } returns null
            val validator = OAuthAccessValidator(tokenService, clientRepository, serverLog)
            assertFalse(validator.validate("tok", resource))
        }

    @Test
    @DisplayName("touchLastUsed called once then throttled within window")
    fun touchDebounced() =
        runTest {
            coEvery { tokenService.verifyAccessToken("tok") } returns AccessTokenClaims("c1", resource)
            coEvery { clientRepository.getClient("c1") } returns client("c1")
            var now = 1000L
            val validator =
                OAuthAccessValidator(tokenService, clientRepository, serverLog, debounceMs = 1000L, nowMs = { now })

            validator.validate("tok", resource)
            validator.validate("tok", resource)
            coVerify(exactly = 1) { clientRepository.touchLastUsed("c1", any()) }

            now += 1000L
            validator.validate("tok", resource)
            coVerify(exactly = 2) { clientRepository.touchLastUsed("c1", any()) }
        }

    @Test
    @DisplayName("validate logs idle session at or beyond threshold")
    fun idleLogsAtThreshold() =
        runTest {
            val now = 10_000_000L
            coEvery { tokenService.verifyAccessToken("tok") } returns AccessTokenClaims("c1", resource)
            coEvery { clientRepository.getClient("c1") } returns
                client("c1", clientName = "Claude", lastUsedAtMs = now - THIRTY_MIN_MS)
            val validator = OAuthAccessValidator(tokenService, clientRepository, serverLog, nowMs = { now })

            validator.validate("tok", resource)

            val entries = serverLog.ofType(ServerLogEntry.Type.OAUTH)
            assertEquals(1, entries.size)
            assertTrue(entries.first().message.contains("Claude"))
            assertTrue(entries.first().message.contains("30 min"))
        }

    @Test
    @DisplayName("validate does not log below threshold")
    fun noIdleBelowThreshold() =
        runTest {
            val now = 10_000_000L
            coEvery { tokenService.verifyAccessToken("tok") } returns AccessTokenClaims("c1", resource)
            coEvery { clientRepository.getClient("c1") } returns
                client("c1", lastUsedAtMs = now - (THIRTY_MIN_MS - ONE_MIN_MS))
            val validator = OAuthAccessValidator(tokenService, clientRepository, serverLog, nowMs = { now })

            validator.validate("tok", resource)

            assertTrue(serverLog.ofType(ServerLogEntry.Type.OAUTH).isEmpty())
        }

    @Test
    @DisplayName("validate does not log for invalid token")
    fun noIdleForInvalidToken() =
        runTest {
            coEvery { tokenService.verifyAccessToken("tok") } returns null
            val validator = OAuthAccessValidator(tokenService, clientRepository, serverLog)

            validator.validate("tok", resource)

            assertTrue(serverLog.ofType(ServerLogEntry.Type.OAUTH).isEmpty())
        }

    @Test
    @DisplayName("second validate within debounce window does not log idle again")
    fun idleNotRepeatedWithinDebounce() =
        runTest {
            var now = 10_000_000L
            coEvery { tokenService.verifyAccessToken("tok") } returns AccessTokenClaims("c1", resource)
            coEvery { clientRepository.getClient("c1") } returns
                client("c1", lastUsedAtMs = now - THIRTY_MIN_MS)
            val validator =
                OAuthAccessValidator(
                    tokenService,
                    clientRepository,
                    serverLog,
                    debounceMs = ONE_MIN_MS,
                    nowMs = { now },
                )

            validator.validate("tok", resource)
            now += 1000L
            validator.validate("tok", resource)

            assertEquals(1, serverLog.ofType(ServerLogEntry.Type.OAUTH).size)
        }

    @Test
    @DisplayName("two validates at the same instant log idle exactly once")
    fun idleLoggedOnceAtSameInstant() =
        runTest {
            val now = 10_000_000L
            coEvery { tokenService.verifyAccessToken("tok") } returns AccessTokenClaims("c1", resource)
            coEvery { clientRepository.getClient("c1") } returns
                client("c1", lastUsedAtMs = now - THIRTY_MIN_MS)
            val validator = OAuthAccessValidator(tokenService, clientRepository, serverLog, nowMs = { now })

            validator.validate("tok", resource)
            validator.validate("tok", resource)

            assertEquals(1, serverLog.ofType(ServerLogEntry.Type.OAUTH).size)
        }

    private companion object {
        const val THIRTY_MIN_MS = 1_800_000L
        const val ONE_MIN_MS = 60_000L
    }
}
