package com.eemote.app.gesture

enum class GestureCategory {
    STOP,
    ROTATE,
    ZOOM,
    TURN,
    SWIPE,
}

enum class GestureCommand {
    STOP,
    ROTATE,
    ZOOM_IN,
    ZOOM_OUT,
    TURN_LEFT,
    TURN_RIGHT,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    SWIPE_UP,
    SWIPE_DOWN,
}

data class DetectedGesture(
    val category: GestureCategory,
    val command: GestureCommand,
)
