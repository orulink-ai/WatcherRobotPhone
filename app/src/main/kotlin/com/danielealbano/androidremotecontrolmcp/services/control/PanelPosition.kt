package com.danielealbano.androidremotecontrolmcp.services.control

internal data class PanelPosition(
    val x: Int,
    val y: Int,
)

/** Coordinates are relative to the system-bar/keyboard-safe window area, in pixels. */
internal fun placeBottomControlPanel(
    availableWidth: Int,
    availableHeight: Int,
    panelWidth: Int,
    panelHeight: Int,
    margin: Int,
    preferred: PanelPosition? = null,
): PanelPosition {
    val maxX = (availableWidth - panelWidth).coerceAtLeast(0)
    val maxY = (availableHeight - panelHeight).coerceAtLeast(0)
    val insetX = margin.coerceIn(0, maxX / 2)
    val insetY = margin.coerceIn(0, maxY / 2)
    return PanelPosition(
        x = preferred?.x?.coerceIn(insetX, maxX - insetX) ?: maxX / 2,
        y = preferred?.y?.coerceIn(insetY, maxY - insetY) ?: maxY - insetY,
    )
}
