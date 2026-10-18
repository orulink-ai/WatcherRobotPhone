package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AuthorizationCodeStoreImpl")
class AuthorizationCodeStoreImplTest {
    private fun newStore() = AuthorizationCodeStoreImpl()

    @Test
    @DisplayName("create then consume returns entry once")
    fun createThenConsumeOnce() =
        runTest {
            val store = newStore()
            val code =
                store.create(
                    AuthorizationCodeRequest("client-1", "http://localhost/cb", "challenge", "https://h/mcp", "mcp"),
                    0L,
                )
            val first = store.consume(code, 1L)
            assertNotNull(first)
            assertEquals("client-1", first?.clientId)
            assertEquals("https://h/mcp", first?.resource)
            assertNull(store.consume(code, 2L), "second consume must be null (single-use)")
        }

    @Test
    @DisplayName("expired code not consumable")
    fun expiredNotConsumable() =
        runTest {
            val store = newStore()
            val code =
                store.create(
                    AuthorizationCodeRequest("client-1", "http://localhost/cb", "challenge", "https://h/mcp", "mcp"),
                    0L,
                )
            assertNull(store.consume(code, OAuthPolicy.AUTH_CODE_TTL_MS + 1))
        }
}
