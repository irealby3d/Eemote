package com.eemote.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.media.AudioManager
import android.view.accessibility.AccessibilityEvent
import com.eemote.app.gesture.GestureCommand

class RemoteAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used. Service is command-driven from MainActivity.
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    private fun execute(command: GestureCommand): Boolean {
        return when (command) {
            GestureCommand.STOP -> true
            GestureCommand.ROTATE -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            GestureCommand.TURN_LEFT -> performGlobalAction(GLOBAL_ACTION_BACK)
            GestureCommand.TURN_RIGHT -> performGlobalAction(GLOBAL_ACTION_HOME)
            GestureCommand.ZOOM_IN -> adjustVolume(AudioManager.ADJUST_RAISE)
            GestureCommand.ZOOM_OUT -> adjustVolume(AudioManager.ADJUST_LOWER)
            GestureCommand.SWIPE_LEFT -> swipe(Direction.LEFT)
            GestureCommand.SWIPE_RIGHT -> swipe(Direction.RIGHT)
            GestureCommand.SWIPE_UP -> swipe(Direction.UP)
            GestureCommand.SWIPE_DOWN -> swipe(Direction.DOWN)
        }
    }

    private fun adjustVolume(direction: Int): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return true
    }

    private enum class Direction { LEFT, RIGHT, UP, DOWN }

    private fun swipe(direction: Direction): Boolean {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val dx = width * 0.28f
        val dy = height * 0.22f

        val (startX, startY, endX, endY) = when (direction) {
            Direction.LEFT -> floatArrayOf(centerX + dx, centerY, centerX - dx, centerY)
            Direction.RIGHT -> floatArrayOf(centerX - dx, centerY, centerX + dx, centerY)
            Direction.UP -> floatArrayOf(centerX, centerY + dy, centerX, centerY - dy)
            Direction.DOWN -> floatArrayOf(centerX, centerY - dy, centerX, centerY + dy)
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 240)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile
        private var instance: RemoteAccessibilityService? = null

        fun dispatch(command: GestureCommand): Boolean {
            return instance?.execute(command) == true
        }

        fun isRunning(): Boolean = instance != null
    }
}
