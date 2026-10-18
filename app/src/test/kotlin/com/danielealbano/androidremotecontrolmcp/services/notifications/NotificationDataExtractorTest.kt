// Note: NotificationDataExtractor.extract() requires StatusBarNotification,
// Notification, and Bundle from the Android framework. Mocking Bundle.getCharSequence()
// in JVM-only tests causes MockK issues. These tests verify the shared hash
// utility functions (computeNotificationHash, computeActionHash) used by the
// extractor. Full extract() testing requires instrumented tests.
package com.danielealbano.androidremotecontrolmcp.services.notifications

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("NotificationDataExtractor")
class NotificationDataExtractorTest {
    @Nested
    @DisplayName("hash functions")
    inner class HashFunctions {
        @Test
        fun `computeNotificationHash produces 8-char hex`() {
            val hash = NotificationProviderImpl.computeNotificationHash("test_key")
            assertEquals(NotificationProviderImpl.HASH_HEX_LENGTH, hash.length)
        }

        @Test
        fun `computeNotificationHash is deterministic`() {
            val hash1 = NotificationProviderImpl.computeNotificationHash("same_key")
            val hash2 = NotificationProviderImpl.computeNotificationHash("same_key")
            assertEquals(hash1, hash2)
        }

        @Test
        fun `computeNotificationHash produces different hashes for different keys`() {
            val hash1 = NotificationProviderImpl.computeNotificationHash("key_a")
            val hash2 = NotificationProviderImpl.computeNotificationHash("key_b")
            assertNotEquals(hash1, hash2)
        }

        @Test
        fun `computeActionHash produces 8-char hex`() {
            val hash = NotificationProviderImpl.computeActionHash("test_key", 0)
            assertEquals(NotificationProviderImpl.HASH_HEX_LENGTH, hash.length)
        }

        @Test
        fun `computeActionHash is deterministic`() {
            val hash1 = NotificationProviderImpl.computeActionHash("key", 1)
            val hash2 = NotificationProviderImpl.computeActionHash("key", 1)
            assertEquals(hash1, hash2)
        }

        @Test
        fun `computeActionHash differs by action index`() {
            val hash0 = NotificationProviderImpl.computeActionHash("key", 0)
            val hash1 = NotificationProviderImpl.computeActionHash("key", 1)
            assertNotEquals(hash0, hash1)
        }
    }
}
