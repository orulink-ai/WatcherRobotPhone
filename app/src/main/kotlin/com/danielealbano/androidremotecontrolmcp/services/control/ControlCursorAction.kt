package com.danielealbano.androidremotecontrolmcp.services.control

/** Only real dispatched gestures or successful node actions instantiate this feedback. */
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
