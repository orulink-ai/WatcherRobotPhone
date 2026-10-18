package com.danielealbano.androidremotecontrolmcp.privacy

/** Current operational status of Privacy Mode, surfaced to the UI and the server-start self-check. */
sealed class PrivacyModeStatus {
    /** Privacy Mode is off. */
    data object Disabled : PrivacyModeStatus()

    /** Enabled, but no model-required category is on — deterministic detection only, no model needed. */
    data object ReadyDeterministicOnly : PrivacyModeStatus()

    /** Enabled, model downloaded and self-checked — full detection active. */
    data object Ready : PrivacyModeStatus()

    /** Enabled with model-required categories on, but the model is missing/unloadable/failing. */
    data class Unavailable(
        val reason: String,
    ) : PrivacyModeStatus()
}
