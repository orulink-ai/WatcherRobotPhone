package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.EventChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationFilterMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.net.URL
import java.util.UUID
import javax.inject.Inject

/**
 * [EventChannelSettings] backed by the same Preferences DataStore as [SettingsRepositoryImpl] (which
 * delegates these members here). Every mutation is coalesced-logged through [SettingsChangeLogger];
 * secrets (auth token) log as "changed" with no value, and set-valued settings are compared
 * losslessly so a count-preserving membership swap is never dropped as a no-op.
 */
@Suppress("TooManyFunctions")
class EventChannelSettingsImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val settingsChangeLogger: SettingsChangeLogger,
    ) : EventChannelSettings {
        override val eventChannelConfig: Flow<EventChannelConfig> =
            dataStore.data.map { prefs ->
                val json = prefs[EVENT_CHANNEL_CONFIG_KEY] ?: return@map EventChannelConfig()
                EventChannelConfig.fromJsonOrDefault(json)
            }

        override suspend fun getEventChannelConfig(): EventChannelConfig = eventChannelConfig.first()

        private suspend fun updateConfig(
            transform: (EventChannelConfig) -> EventChannelConfig,
        ): Pair<EventChannelConfig, EventChannelConfig> {
            val current = getEventChannelConfig()
            val updated = transform(current)
            dataStore.edit { prefs -> prefs[EVENT_CHANNEL_CONFIG_KEY] = updated.toJson() }
            return current to updated
        }

        private fun logToggle(
            key: String,
            oldValue: Boolean,
            newValue: Boolean,
            subject: String,
        ) {
            settingsChangeLogger.submit(key, oldValue.toString(), newValue.toString()) { _, n ->
                "$subject ${if (n.toBoolean()) "enabled" else "disabled"}"
            }
        }

        override suspend fun updateEventChannelEnabled(enabled: Boolean) {
            val (old, new) = updateConfig { it.copy(enabled = enabled) }
            logToggle("channel_enabled", old.enabled, new.enabled, "Event channel")
        }

        override suspend fun updateEventChannelEndpointUrl(url: String) {
            val (old, new) = updateConfig { it.copy(endpointUrl = url) }
            settingsChangeLogger.submit("channel_endpoint", old.endpointUrl, new.endpointUrl) { o, n ->
                "Event channel endpoint changed $o → $n"
            }
        }

        override suspend fun updateEventChannelAuthToken(token: String) {
            val (old, new) = updateConfig { it.copy(authToken = token) }
            settingsChangeLogger.submit("channel_auth_token", old.authToken, new.authToken) { _, _ ->
                "Event channel auth token changed"
            }
        }

        override suspend fun generateNewEventChannelAuthToken(): String {
            val token = UUID.randomUUID().toString()
            updateEventChannelAuthToken(token)
            return token
        }

        override fun validateEndpointUrl(url: String): Result<String> {
            if (url.isBlank()) {
                return Result.failure(IllegalArgumentException("Endpoint URL cannot be empty"))
            }
            return try {
                val parsed = URL(url)
                if (parsed.protocol != "http" && parsed.protocol != "https") {
                    Result.failure(IllegalArgumentException("URL must use http or https protocol"))
                } else {
                    Result.success(url)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Result.failure(IllegalArgumentException("Invalid URL format: ${e.message}"))
            }
        }

        override suspend fun updateNotificationChannelEnabled(enabled: Boolean) {
            val (old, new) = updateConfig { it.copy(notifications = it.notifications.copy(enabled = enabled)) }
            logToggle(
                "channel_notifications",
                old.notifications.enabled,
                new.notifications.enabled,
                "Notification events",
            )
        }

        override suspend fun updateNotificationFilterMode(mode: NotificationFilterMode) {
            val (old, new) = updateConfig { it.copy(notifications = it.notifications.copy(filterMode = mode)) }
            settingsChangeLogger.submit(
                "channel_notif_filter_mode",
                old.notifications.filterMode.name,
                new.notifications.filterMode.name,
            ) { o, n -> "Notification filter mode changed $o → $n" }
        }

        override suspend fun updateNotificationFilterApps(apps: Set<String>) {
            val (old, new) = updateConfig { it.copy(notifications = it.notifications.copy(filterApps = apps)) }
            logSetChange(
                "channel_notif_filter_apps",
                old.notifications.filterApps,
                new.notifications.filterApps,
                "Notification filter apps changed",
            )
        }

        override suspend fun updateWifiChannelEnabled(enabled: Boolean) {
            val (old, new) = updateConfig { it.copy(wifi = it.wifi.copy(enabled = enabled)) }
            logToggle("channel_wifi", old.wifi.enabled, new.wifi.enabled, "Wi-Fi events")
        }

        override suspend fun updateWifiSsids(ssids: Set<String>) {
            val (old, new) = updateConfig { it.copy(wifi = it.wifi.copy(ssids = ssids)) }
            logSetChange("channel_wifi_ssids", old.wifi.ssids, new.wifi.ssids, "Wi-Fi monitored SSIDs changed")
        }

        override suspend fun updateWifiNotifyOnDiscovered(enabled: Boolean) {
            val (old, new) = updateConfig { it.copy(wifi = it.wifi.copy(notifyOnDiscovered = enabled)) }
            logToggle(
                "channel_wifi_discovered",
                old.wifi.notifyOnDiscovered,
                new.wifi.notifyOnDiscovered,
                "Wi-Fi notify-on-discovered",
            )
        }

        override suspend fun updateWifiNotifyOnLost(enabled: Boolean) {
            val (old, new) = updateConfig { it.copy(wifi = it.wifi.copy(notifyOnLost = enabled)) }
            logToggle("channel_wifi_lost", old.wifi.notifyOnLost, new.wifi.notifyOnLost, "Wi-Fi notify-on-lost")
        }

        override suspend fun updateWifiNotifyOnConnected(enabled: Boolean) {
            val (old, new) = updateConfig { it.copy(wifi = it.wifi.copy(notifyOnConnected = enabled)) }
            logToggle(
                "channel_wifi_connected",
                old.wifi.notifyOnConnected,
                new.wifi.notifyOnConnected,
                "Wi-Fi notify-on-connected",
            )
        }

        override suspend fun updateWifiNotifyOnDisconnected(enabled: Boolean) {
            val (old, new) = updateConfig { it.copy(wifi = it.wifi.copy(notifyOnDisconnected = enabled)) }
            logToggle(
                "channel_wifi_disconnected",
                old.wifi.notifyOnDisconnected,
                new.wifi.notifyOnDisconnected,
                "Wi-Fi notify-on-disconnected",
            )
        }

        /**
         * Submits a set-valued change: the old/new values are the LOSSLESS serialized sets (so a
         * count-preserving membership swap is not dropped as a no-op), while the rendered message
         * shows counts only.
         */
        private fun logSetChange(
            key: String,
            old: Set<String>,
            new: Set<String>,
            label: String,
        ) {
            settingsChangeLogger.submit(key, serializeSet(old), serializeSet(new)) { o, n ->
                "$label ${serializedSetCount(o)} → ${serializedSetCount(n)}"
            }
        }

        // The count is written as a leading digits-only header before the first '\n', so it is
        // recovered exactly even when an element itself contains a newline (802.11 SSIDs may).
        private fun serializeSet(values: Set<String>): String = "${values.size}\n" + values.sorted().joinToString("\n")

        private fun serializedSetCount(value: String): Int = value.substringBefore('\n').toIntOrNull() ?: 0

        private companion object {
            private val EVENT_CHANNEL_CONFIG_KEY = stringPreferencesKey("event_channel_config")
        }
    }
