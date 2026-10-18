package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val eventChannelJson = Json { ignoreUnknownKeys = true }

@Serializable
data class EventChannelConfig(
    val enabled: Boolean = false,
    val endpointUrl: String = "",
    val authToken: String = "",
    val notifications: NotificationChannelConfig = NotificationChannelConfig(),
    val wifi: WifiChannelConfig = WifiChannelConfig(),
) {
    companion object {
        const val DEFAULT_ENDPOINT_URL = "http://localhost:9090"

        fun fromJson(json: String): EventChannelConfig = eventChannelJson.decodeFromString(serializer(), json)

        fun fromJsonOrDefault(json: String): EventChannelConfig =
            try {
                fromJson(json)
            } catch (_: Exception) {
                EventChannelConfig()
            }
    }

    fun toJson(): String = eventChannelJson.encodeToString(serializer(), this)
}

@Serializable
data class NotificationChannelConfig(
    val enabled: Boolean = false,
    val filterMode: NotificationFilterMode = NotificationFilterMode.ALL,
    val filterApps: Set<String> = emptySet(),
)

@Serializable
enum class NotificationFilterMode {
    ALL,
    WHITELIST,
    BLACKLIST,
}

@Serializable
data class WifiChannelConfig(
    val enabled: Boolean = false,
    val ssids: Set<String> = emptySet(),
    val notifyOnDiscovered: Boolean = true,
    val notifyOnLost: Boolean = true,
    val notifyOnConnected: Boolean = true,
    val notifyOnDisconnected: Boolean = true,
)
