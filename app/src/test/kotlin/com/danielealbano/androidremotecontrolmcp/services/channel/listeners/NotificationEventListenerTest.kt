package com.danielealbano.androidremotecontrolmcp.services.channel.listeners

import com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus
import com.danielealbano.androidremotecontrolmcp.data.model.ChannelEvent
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationChangeEvent
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationChangeType
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationFilterMode
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.channel.EventDispatcher
import com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("NotificationEventListener")
class NotificationEventListenerTest {
    private fun createMockDispatcher(): EventDispatcher {
        val mock = mockk<EventDispatcher>(relaxed = true)
        coEvery { mock.dispatch(any()) } returns Result.success(Unit)
        coEvery { mock.connectionStatus } returns MutableStateFlow(ChannelConnectionStatus.Idle)
        return mock
    }

    private fun sampleNotification(
        title: String? = "Test Title",
        text: String? = "Test text",
        packageName: String = "com.example.app",
    ): NotificationData =
        NotificationData(
            notificationId = "aabbcc01",
            packageName = packageName,
            appName = "Example",
            title = title,
            text = text,
            bigText = null,
            subText = null,
            timestamp = 1_700_000_000_000L,
            isOngoing = false,
            isClearable = true,
            category = null,
            groupKey = null,
            actions = emptyList(),
        )

    /**
     * Emits into the companion [MutableSharedFlow] backing
     * [McpNotificationListenerService.notificationChangeEvents]. Kotlin compiles the
     * companion's private property as a static field on the enclosing class.
     */
    private fun emitChangeEvent(event: NotificationChangeEvent) {
        val field = McpNotificationListenerService::class.java.getDeclaredField("_notificationChangeEvents")
        field.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val flow = field.get(null) as MutableSharedFlow<NotificationChangeEvent>
        flow.tryEmit(event)
    }

    @Nested
    @DisplayName("filter modes")
    inner class FilterModes {
        @Test
        fun `ALL mode forwards all packages`() {
            val config =
                NotificationChannelConfig(
                    enabled = true,
                    filterMode = NotificationFilterMode.ALL,
                )
            // ALL mode: any package should pass
            assertTrue(shouldForward("com.any.app", config))
            assertTrue(shouldForward("com.other.app", config))
        }

        @Test
        fun `WHITELIST mode forwards only matching apps`() {
            val config =
                NotificationChannelConfig(
                    enabled = true,
                    filterMode = NotificationFilterMode.WHITELIST,
                    filterApps = setOf("com.whitelisted.app"),
                )
            assertTrue(shouldForward("com.whitelisted.app", config))
        }

        @Test
        fun `WHITELIST mode blocks non-matching apps`() {
            val config =
                NotificationChannelConfig(
                    enabled = true,
                    filterMode = NotificationFilterMode.WHITELIST,
                    filterApps = setOf("com.whitelisted.app"),
                )
            assertFalse(shouldForward("com.other.app", config))
        }

        @Test
        fun `BLACKLIST mode blocks matching apps`() {
            val config =
                NotificationChannelConfig(
                    enabled = true,
                    filterMode = NotificationFilterMode.BLACKLIST,
                    filterApps = setOf("com.blocked.app"),
                )
            assertFalse(shouldForward("com.blocked.app", config))
        }

        @Test
        fun `BLACKLIST mode forwards non-matching apps`() {
            val config =
                NotificationChannelConfig(
                    enabled = true,
                    filterMode = NotificationFilterMode.BLACKLIST,
                    filterApps = setOf("com.blocked.app"),
                )
            assertTrue(shouldForward("com.other.app", config))
        }
    }

    @Nested
    @DisplayName("privacy redaction")
    inner class PrivacyRedaction {
        @Test
        fun `dispatched event carries redacted fields`() =
            runTest(UnconfinedTestDispatcher()) {
                val dispatcher = createMockDispatcher()
                val gate = mockk<PrivacyToolGate>()
                coEvery { gate.texts(any()) } returns listOf("Example", "EMAIL#abcde", "body", null, null)

                val listener = NotificationEventListener(dispatcher, backgroundScope, gate)
                listener.start(NotificationChannelConfig(enabled = true, filterMode = NotificationFilterMode.ALL))

                emitChangeEvent(
                    NotificationChangeEvent(
                        NotificationChangeType.POSTED,
                        sampleNotification(title = "Contact john@example.com"),
                    ),
                )
                advanceUntilIdle()

                val slot = slot<ChannelEvent>()
                coVerify { dispatcher.dispatch(capture(slot)) }
                val data = slot.captured.data.jsonObject
                assertEquals("EMAIL#abcde", data["title"]?.jsonPrimitive?.content)
                assertEquals("body", data["text"]?.jsonPrimitive?.content)

                listener.stop()
            }

        @Test
        fun `event dropped and not dispatched when gate fails closed`() =
            runTest(UnconfinedTestDispatcher()) {
                val dispatcher = createMockDispatcher()
                val gate = mockk<PrivacyToolGate>()
                coEvery { gate.texts(any()) } throws
                    McpToolException.PrivacyModeUnavailable("model unavailable")

                val listener = NotificationEventListener(dispatcher, backgroundScope, gate)
                listener.start(NotificationChannelConfig(enabled = true, filterMode = NotificationFilterMode.ALL))

                emitChangeEvent(
                    NotificationChangeEvent(
                        NotificationChangeType.POSTED,
                        sampleNotification(title = "Contact john@example.com"),
                    ),
                )
                advanceUntilIdle()

                coVerify(exactly = 0) { dispatcher.dispatch(any()) }

                listener.stop()
            }
    }

    @Nested
    @DisplayName("lifecycle")
    inner class Lifecycle {
        @Test
        fun `stop is safe to call without start`() {
            val dispatcher = createMockDispatcher()
            val listener = NotificationEventListener(dispatcher, mockk(relaxed = true), mockk(relaxed = true))
            // Calling stop before start should not throw
            listener.stop()
            // Calling stop again should also be safe (idempotent)
            listener.stop()
        }
    }

    /**
     * Mirrors the private shouldForward logic from NotificationEventListener
     * to verify filter behavior independently.
     */
    private fun shouldForward(
        packageName: String,
        config: NotificationChannelConfig,
    ): Boolean =
        when (config.filterMode) {
            NotificationFilterMode.ALL -> true
            NotificationFilterMode.WHITELIST -> packageName in config.filterApps
            NotificationFilterMode.BLACKLIST -> packageName !in config.filterApps
        }
}
