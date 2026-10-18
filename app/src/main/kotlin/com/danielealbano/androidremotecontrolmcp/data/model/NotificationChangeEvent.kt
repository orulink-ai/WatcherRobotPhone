package com.danielealbano.androidremotecontrolmcp.data.model

import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationData

data class NotificationChangeEvent(
    val eventType: NotificationChangeType,
    val notification: NotificationData,
)

enum class NotificationChangeType {
    POSTED,
    REMOVED,
}
