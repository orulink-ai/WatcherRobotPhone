package com.danielealbano.androidremotecontrolmcp.services.location

import com.danielealbano.androidremotecontrolmcp.data.model.LocationData

interface LocationProvider {
    companion object {
        /** Timeout for requesting a fresh GPS fix, in milliseconds. */
        const val FRESH_FIX_TIMEOUT_MS = 10_000L
    }

    /**
     * Retrieves the device's current location.
     *
     * @param freshFix If true, requests a fresh GPS fix (may take up to [FRESH_FIX_TIMEOUT_MS]).
     *                 If false, returns the last known location (fast but possibly stale).
     * @return [Result.success] with [LocationData] on success,
     *         [Result.failure] with descriptive exception on failure.
     *         Failure cases:
     *         - Google Play Services not available
     *         - Location permission not granted
     *         - No location available (e.g., GPS disabled, no last known location)
     *         - Timeout waiting for fresh fix
     */
    suspend fun getLocation(freshFix: Boolean): Result<LocationData>
}
