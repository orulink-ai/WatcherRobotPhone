package com.danielealbano.androidremotecontrolmcp.services.control

internal enum class ControlCursorPhase { APPROACH, EXECUTING, COMPLETED }

internal data class ControlCursorVisual(
    val action: ControlCursorAction,
    val phase: ControlCursorPhase,
    val alpha: Float = 1f,
    val clickEffect: ControlCursorClickEffect? = null,
)

/** Action verbs are selected before approach and confirmed only by actual callbacks. */
internal enum class ControlCursorAction {
    CLICK,
    LONG_PRESS,
    SWIPE,
    SCROLL,
    TYPE,
    ;

    companion object {
        fun fromTool(toolName: String?): ControlCursorAction? =
            when {
                toolName == null -> null
                toolName.contains("long_press") || toolName.contains("long_click") -> LONG_PRESS
                toolName.contains("scroll") -> SCROLL
                toolName.contains("swipe") || toolName.contains("drag") -> SWIPE
                toolName.startsWith("type_") || toolName == "press_key" -> TYPE
                toolName.contains("tap") || toolName.contains("click") -> CLICK
                else -> null
            }
    }
}
