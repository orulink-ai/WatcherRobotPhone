package com.danielealbano.androidremotecontrolmcp.data.model

import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationActionData
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationData
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("ChannelEventFactory")
class ChannelEventFactoryTest {
    private val sampleNotification =
        NotificationData(
            notificationId = "abc12345",
            packageName = "com.example.app",
            appName = "Example App",
            title = "Test Title",
            text = "Test message body",
            bigText = null,
            subText = "Sub text",
            timestamp = 1712345678000L,
            isOngoing = false,
            isClearable = true,
            category = "msg",
            groupKey = "group1",
            actions =
                listOf(
                    NotificationActionData(
                        actionId = "act1",
                        index = 0,
                        title = "Reply",
                        acceptsText = true,
                    ),
                ),
        )

    @Nested
    @DisplayName("notification events")
    inner class NotificationEvents {
        @Test
        fun `notification event has correct type`() {
            val event = ChannelEventFactory.notification(sampleNotification, "posted")
            assertEquals("notification", event.type)
        }

        @Test
        fun `notification event includes all fields`() {
            val event = ChannelEventFactory.notification(sampleNotification, "posted")
            val data = event.data.jsonObject
            assertEquals("posted", data["eventType"]?.jsonPrimitive?.content)
            assertEquals("abc12345", data["notificationId"]?.jsonPrimitive?.content)
            assertEquals("com.example.app", data["packageName"]?.jsonPrimitive?.content)
            assertEquals("Example App", data["appName"]?.jsonPrimitive?.content)
            assertEquals("Test Title", data["title"]?.jsonPrimitive?.content)
            assertEquals("Test message body", data["text"]?.jsonPrimitive?.content)
            assertEquals("Sub text", data["subText"]?.jsonPrimitive?.content)
            assertEquals("msg", data["category"]?.jsonPrimitive?.content)
            assertEquals("group1", data["groupKey"]?.jsonPrimitive?.content)
        }

        @Test
        fun `notification event includes actions array`() {
            val event = ChannelEventFactory.notification(sampleNotification, "posted")
            val data = event.data.jsonObject
            val actions = data["actions"]?.jsonArray
            assertNotNull(actions)
            assertEquals(1, actions!!.size)
            val action = actions[0].jsonObject
            assertEquals("act1", action["actionId"]?.jsonPrimitive?.content)
            assertEquals(0, action["index"]?.jsonPrimitive?.content?.toInt())
            assertEquals("Reply", action["title"]?.jsonPrimitive?.content)
            assertEquals("true", action["acceptsText"]?.jsonPrimitive?.content)
        }
    }

    @Nested
    @DisplayName("wifi events")
    inner class WifiEvents {
        @Test
        fun `wifi event has correct type and data`() {
            val event = ChannelEventFactory.wifi("MyNetwork", "connected", "00:11:22:33:44:55")
            assertEquals("wifi", event.type)
            val data = event.data.jsonObject
            assertEquals("MyNetwork", data["ssid"]?.jsonPrimitive?.content)
            assertEquals("connected", data["eventType"]?.jsonPrimitive?.content)
            assertEquals("00:11:22:33:44:55", data["bssid"]?.jsonPrimitive?.content)
        }
    }

    @Nested
    @DisplayName("timestamp")
    inner class Timestamp {
        @Test
        fun `timestamp is ISO 8601 format`() {
            val event = ChannelEventFactory.wifi("test", "discovered", null)
            // Should not throw — valid ISO 8601
            val parsed = Instant.parse(event.timestamp)
            assertTrue(parsed.isBefore(Instant.now().plusSeconds(1)))
        }
    }
}
