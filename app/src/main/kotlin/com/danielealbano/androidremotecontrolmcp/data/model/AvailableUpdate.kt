package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * A newer app release that has been detected on GitHub and is available to install.
 *
 * @property versionName The canonical `major.minor.patch` version of the newer release (no leading `v`).
 * @property releaseUrl The canonical GitHub release page URL to open in the browser.
 */
data class AvailableUpdate(
    val versionName: String,
    val releaseUrl: String,
)
