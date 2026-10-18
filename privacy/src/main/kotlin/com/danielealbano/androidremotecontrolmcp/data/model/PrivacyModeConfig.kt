package com.danielealbano.androidremotecontrolmcp.data.model

import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class RedactionMode { PSEUDONYMIZE, REDACT }

enum class PlaceholderFormat { HASHED, NUMBERED }

@Serializable
data class PrivacyModeConfig(
    val enabled: Boolean = false,
    val disabledCategories: Set<PiiCategory> = emptySet(),
    val redactionMode: RedactionMode = RedactionMode.PSEUDONYMIZE,
    val placeholderFormat: PlaceholderFormat = PlaceholderFormat.HASHED,
) {
    fun isCategoryEnabled(category: PiiCategory): Boolean = category !in disabledCategories

    fun enabledCategories(): Set<PiiCategory> = PiiCategory.entries.toSet() - disabledCategories

    fun modelRequired(): Boolean = enabled && enabledCategories().any { it.requiresModel }

    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun fromJsonOrDefault(json: String?): PrivacyModeConfig =
            json?.let {
                runCatching { Json.decodeFromString(serializer(), it) }.getOrElse { PrivacyModeConfig() }
            } ?: PrivacyModeConfig()
    }
}
