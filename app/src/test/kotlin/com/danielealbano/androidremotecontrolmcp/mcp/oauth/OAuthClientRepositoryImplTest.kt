package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.OAuthClientRepositoryImpl
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("OAuthClientRepositoryImpl")
class OAuthClientRepositoryImplTest {
    @TempDir
    lateinit var tempDir: File

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: OAuthClientRepositoryImpl
    private val serverLog = RecordingServerLogRepository()
    private var counter = 0

    @BeforeEach
    fun setUp() {
        counter++
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { File(tempDir, "oauth_clients_$counter.preferences_pb") },
            )
        repository = OAuthClientRepositoryImpl(dataStore, serverLog)
    }

    @Test
    @DisplayName("register then get round-trips fields")
    fun registerRoundTrips() =
        testScope.runTest {
            val created =
                repository.register(
                    clientName = "Claude",
                    redirectUris = listOf("https://claude.ai/api/mcp/auth_callback"),
                    applicationType = "web",
                    logoUri = "https://logo.example/l.png",
                    nowMs = 100L,
                )
            val fetched = repository.getClient(created.clientId)
            assertNotNull(fetched)
            assertEquals("Claude", fetched?.clientName)
            assertEquals(listOf("https://claude.ai/api/mcp/auth_callback"), fetched?.redirectUris)
            assertEquals("web", fetched?.applicationType)
            assertEquals("https://logo.example/l.png", fetched?.logoUri)
            assertEquals(100L, fetched?.createdAtMs)
            assertEquals(100L, fetched?.lastUsedAtMs)
        }

    @Test
    @DisplayName("persists across new instance")
    fun persistsAcrossInstance() =
        testScope.runTest {
            val created =
                repository.register("Claude", listOf("https://claude.ai/api/mcp/auth_callback"), "web", null, 1L)
            val newRepo = OAuthClientRepositoryImpl(dataStore, serverLog)
            assertNotNull(newRepo.getClient(created.clientId))
        }

    @Test
    @DisplayName("getClient served from snapshot after first load")
    fun getClientFromSnapshot() =
        testScope.runTest {
            val created =
                repository.register("Claude", listOf("https://claude.ai/api/mcp/auth_callback"), "web", null, 1L)
            assertEquals(repository.getClient(created.clientId), repository.getClient(created.clientId))
        }

    @Test
    @DisplayName("evicts oldest unapproved beyond cap")
    fun evictsOldestUnapproved() =
        testScope.runTest {
            val ids = (1..OAuthPolicy.MAX_OAUTH_CLIENTS).map { i -> register(i.toLong()).clientId }
            // Mark the overall-oldest (first) as approved; the oldest UNAPPROVED is the second.
            repository.setRefreshJti(ids[0], "jti")
            register((OAuthPolicy.MAX_OAUTH_CLIENTS + 1).toLong())

            assertNotNull(repository.getClient(ids[0]), "approved oldest must survive")
            assertNull(repository.getClient(ids[1]), "oldest unapproved must be evicted")
        }

    @Test
    @DisplayName("evicts overall oldest when all approved")
    fun evictsOverallOldestWhenAllApproved() =
        testScope.runTest {
            val ids = (1..OAuthPolicy.MAX_OAUTH_CLIENTS).map { i -> register(i.toLong()).clientId }
            ids.forEach { repository.setRefreshJti(it, "jti") }
            val newest = register((OAuthPolicy.MAX_OAUTH_CLIENTS + 1).toLong())

            assertNull(repository.getClient(ids[0]), "overall oldest must be evicted")
            assertNotNull(repository.getClient(newest.clientId), "newcomer must be kept")
        }

    @Test
    @DisplayName("revoke removes client")
    fun revokeRemoves() =
        testScope.runTest {
            val created =
                repository.register("Claude", listOf("https://claude.ai/api/mcp/auth_callback"), "web", null, 1L)
            repository.revoke(created.clientId)
            assertNull(repository.getClient(created.clientId))
        }

    @Test
    @DisplayName("revoke logs client name; unknown id logs nothing")
    fun revokeLogsClientName() =
        testScope.runTest {
            val created =
                repository.register("Claude", listOf("https://claude.ai/api/mcp/auth_callback"), "web", null, 1L)

            repository.revoke("does-not-exist")
            assertEquals(0, serverLog.ofType(ServerLogEntry.Type.OAUTH).size)

            repository.revoke(created.clientId)
            val entries = serverLog.ofType(ServerLogEntry.Type.OAUTH)
            assertEquals(1, entries.size)
            assertEquals("OAuth client revoked: Claude", entries.first().message)
        }

    @Test
    @DisplayName("touchLastUsed and setRefreshJti update")
    fun touchAndSetJtiUpdate() =
        testScope.runTest {
            val created =
                repository.register("Claude", listOf("https://claude.ai/api/mcp/auth_callback"), "web", null, 1L)
            repository.touchLastUsed(created.clientId, 999L)
            repository.setRefreshJti(created.clientId, "jti-1")
            val updated = repository.getClient(created.clientId)
            assertEquals(999L, updated?.lastUsedAtMs)
            assertEquals("jti-1", updated?.currentRefreshJti)
        }

    private suspend fun register(nowMs: Long) =
        repository.register("c$nowMs", listOf("https://claude.ai/api/mcp/auth_callback"), "web", null, nowMs)
}
