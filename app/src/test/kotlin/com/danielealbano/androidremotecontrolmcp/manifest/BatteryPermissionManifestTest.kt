package com.danielealbano.androidremotecontrolmcp.manifest

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the flavor placement of `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
 *
 * F-Droid flags this permission, so it MUST be declared only in the `gms` flavor manifest — never in
 * `src/main` (shared by both flavors) and never in `src/foss`. These tests parse the checked-in
 * manifests directly — no Robolectric, no device — so a misplaced declaration fails the build.
 */
@DisplayName("Battery-optimization permission")
class BatteryPermissionManifestTest {
    @Test
    fun `gms manifest declares REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`() {
        assertTrue(BATTERY_PERMISSION in usesPermissionsIn(GMS_MANIFEST)) {
            "$BATTERY_PERMISSION must be declared in $GMS_MANIFEST (gms flavor only)."
        }
    }

    @Test
    fun `main manifest does not declare it`() {
        assertFalse(BATTERY_PERMISSION in usesPermissionsIn(MAIN_MANIFEST)) {
            "$BATTERY_PERMISSION must NOT be declared in $MAIN_MANIFEST — it is gms-flavor only " +
                "because F-Droid flags it."
        }
    }

    @Test
    fun `foss manifest does not declare it`() {
        assertFalse(BATTERY_PERMISSION in usesPermissionsIn(FOSS_MANIFEST)) {
            "$BATTERY_PERMISSION must NOT be declared in $FOSS_MANIFEST — F-Droid flags it."
        }
    }

    /** Returns the `android:name` of every `<uses-permission>` in the manifest, or empty if absent. */
    private fun usesPermissionsIn(relativePath: String): Set<String> {
        val manifest = resolveManifest(relativePath) ?: return emptySet()
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                .newDocumentBuilder()
                .parse(manifest)

        val nodes = document.getElementsByTagName("uses-permission")
        return (0 until nodes.length)
            .map { index -> (nodes.item(index) as Element).getAttribute("android:name") }
            .toSet()
    }

    /** Unit tests run with the module directory as CWD; fall back to the repository root. Null when absent. */
    private fun resolveManifest(relativePath: String): File? =
        listOf(File(relativePath), File("app", relativePath)).firstOrNull { it.isFile }

    private companion object {
        const val MAIN_MANIFEST = "src/main/AndroidManifest.xml"
        const val GMS_MANIFEST = "src/gms/AndroidManifest.xml"
        const val FOSS_MANIFEST = "src/foss/AndroidManifest.xml"
        const val BATTERY_PERMISSION = "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    }
}
