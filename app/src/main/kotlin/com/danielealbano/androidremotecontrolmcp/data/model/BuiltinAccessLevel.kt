package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Read-access level of a built-in MediaStore location, exposed as `access_level`
 * in the `list_storage_locations` MCP output.
 *
 * PARTIAL covers both the Android 14+ visual-media selection (limited access) and
 * a per-type mixed grant (e.g. images granted, videos not).
 */
enum class BuiltinAccessLevel(
    val jsonValue: String,
) {
    FULL("full"),
    PARTIAL("partial"),
    OWNED_ONLY("owned_only"),
}
