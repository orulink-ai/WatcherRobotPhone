package com.danielealbano.androidremotecontrolmcp.data.model

import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationData
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object ChannelEventFactory {
    private fun nowIso8601(): String =
        java.time.Instant
            .now()
            .toString()

    fun notification(
        notification: NotificationData,
        eventType: String,
    ): ChannelEvent =
        ChannelEvent(
            type = "notification",
            timestamp = nowIso8601(),
            data =
                buildJsonObject {
                    put("eventType", eventType)
                    put("notificationId", notification.notificationId)
                    put("packageName", notification.packageName)
                    put("appName", notification.appName)
                    put("title", notification.title?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("text", notification.text?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("bigText", notification.bigText?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("subText", notification.subText?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("timestamp", notification.timestamp)
                    put("isOngoing", notification.isOngoing)
                    put("isClearable", notification.isClearable)
                    put("category", notification.category?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("groupKey", notification.groupKey?.let { JsonPrimitive(it) } ?: JsonNull)
                    put(
                        "actions",
                        buildJsonArray {
                            for (action in notification.actions) {
                                add(
                                    buildJsonObject {
                                        put("actionId", action.actionId)
                                        put("index", action.index)
                                        put("title", action.title)
                                        put("acceptsText", action.acceptsText)
                                    },
                                )
                            }
                        },
                    )
                },
        )

    fun wifi(
        ssid: String,
        eventType: String,
        bssid: String?,
    ): ChannelEvent =
        ChannelEvent(
            type = "wifi",
            timestamp = nowIso8601(),
            data =
                buildJsonObject {
                    put("ssid", ssid)
                    put("eventType", eventType)
                    put("bssid", bssid?.let { JsonPrimitive(it) } ?: JsonNull)
                },
        )
}
