package com.eemote.app.gesture

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

class GestureInterpreter {
    private val points = ArrayDeque<Pair<Float, Float>>()
    private var lastPinchDistance: Float? = null
    private var lastEmittedAt = 0L

    fun interpret(landmarks: List<NormalizedLandmark>, timestampMs: Long): DetectedGesture? {
        if (landmarks.size < 21) return null

        val wrist = point(landmarks, 0)
        points.addLast(wrist)
        while (points.size > 8) points.removeFirst()

        val ext = extensionState(landmarks)
        val movement = movementVector()
        val pinchDistance = distance(point(landmarks, 4), point(landmarks, 8))

        if (ext.totalExtended >= 4) {
            return emitWithCooldown(timestampMs, DetectedGesture(GestureCategory.STOP, GestureCommand.STOP), 700)
        }

        if (ext.totalExtended >= 2 && abs(movement.first) + abs(movement.second) > 0.06f) {
            val command = dominantSwipeCommand(movement)
            return emitWithCooldown(timestampMs, DetectedGesture(GestureCategory.SWIPE, command), 550)
        }

        val pinchDelta = lastPinchDistance?.let { pinchDistance - it }
        lastPinchDistance = pinchDistance

        if (ext.thumb && ext.index && !ext.middle && !ext.ring && !ext.pinky && pinchDelta != null && abs(pinchDelta) > 0.008f) {
            val command = if (pinchDelta > 0f) GestureCommand.ZOOM_IN else GestureCommand.ZOOM_OUT
            return emitWithCooldown(timestampMs, DetectedGesture(GestureCategory.ZOOM, command), 500)
        }

        if (ext.index && !ext.thumb && !ext.middle && !ext.ring && !ext.pinky && abs(movement.first) > 0.025f) {
            val command = if (movement.first > 0f) GestureCommand.TURN_RIGHT else GestureCommand.TURN_LEFT
            return emitWithCooldown(timestampMs, DetectedGesture(GestureCategory.TURN, command), 500)
        }

        if (ext.totalExtended <= 1 && points.size >= 6 && hasCircularMotion()) {
            return emitWithCooldown(timestampMs, DetectedGesture(GestureCategory.ROTATE, GestureCommand.ROTATE), 900)
        }

        // Fallback static gestures to make control practical on real phones:
        // 1 finger -> Back, 2 fingers -> Home, 3 fingers -> Recents.
        if (ext.index && !ext.middle && !ext.ring && !ext.pinky) {
            return emitWithCooldown(timestampMs, DetectedGesture(GestureCategory.TURN, GestureCommand.TURN_LEFT), 900)
        }
        if (ext.index && ext.middle && !ext.ring && !ext.pinky) {
            return emitWithCooldown(timestampMs, DetectedGesture(GestureCategory.TURN, GestureCommand.TURN_RIGHT), 900)
        }
        if (ext.index && ext.middle && ext.ring && !ext.pinky) {
            return emitWithCooldown(timestampMs, DetectedGesture(GestureCategory.ROTATE, GestureCommand.ROTATE), 1000)
        }

        return null
    }

    private fun emitWithCooldown(timestampMs: Long, gesture: DetectedGesture, cooldownMs: Long): DetectedGesture? {
        if (timestampMs - lastEmittedAt < cooldownMs) return null
        lastEmittedAt = timestampMs
        return gesture
    }

    private fun dominantSwipeCommand(movement: Pair<Float, Float>): GestureCommand {
        return if (abs(movement.first) > abs(movement.second)) {
            if (movement.first > 0f) GestureCommand.SWIPE_RIGHT else GestureCommand.SWIPE_LEFT
        } else {
            if (movement.second > 0f) GestureCommand.SWIPE_DOWN else GestureCommand.SWIPE_UP
        }
    }

    private fun movementVector(): Pair<Float, Float> {
        if (points.size < 2) return 0f to 0f
        val first = points.first()
        val last = points.last()
        return (last.first - first.first) to (last.second - first.second)
    }

    private fun hasCircularMotion(): Boolean {
        if (points.size < 6) return false
        val centerX = points.map { it.first }.average().toFloat()
        val centerY = points.map { it.second }.average().toFloat()
        val radii = points.map { distance(it, centerX to centerY) }
        val avgRadius = radii.average().toFloat()
        if (avgRadius < 0.02f) return false

        val variance = radii.map { (it - avgRadius).pow(2f) }.average().toFloat()
        if (variance > 0.0005f) return false

        var totalAngle = 0f
        for (i in 1 until points.size) {
            val a1 = atan2(points[i - 1].second - centerY, points[i - 1].first - centerX)
            val a2 = atan2(points[i].second - centerY, points[i].first - centerX)
            var delta = a2 - a1
            if (delta > Math.PI) delta -= (2 * Math.PI).toFloat()
            if (delta < -Math.PI) delta += (2 * Math.PI).toFloat()
            totalAngle += delta
        }
        return abs(totalAngle) > 2.6f
    }

    private data class ExtensionState(
        val thumb: Boolean,
        val index: Boolean,
        val middle: Boolean,
        val ring: Boolean,
        val pinky: Boolean,
    ) {
        val totalExtended: Int
            get() = listOf(thumb, index, middle, ring, pinky).count { it }
    }

    private fun extensionState(landmarks: List<NormalizedLandmark>): ExtensionState {
        val wrist = point(landmarks, 0)
        val thumb = isExtended(landmarks, wrist, tip = 4, base = 2, factor = 1.06f, checkVertical = false)
        val index = isExtended(landmarks, wrist, tip = 8, base = 6, factor = 1.08f, checkVertical = true)
        val middle = isExtended(landmarks, wrist, tip = 12, base = 10, factor = 1.08f, checkVertical = true)
        val ring = isExtended(landmarks, wrist, tip = 16, base = 14, factor = 1.08f, checkVertical = true)
        val pinky = isExtended(landmarks, wrist, tip = 20, base = 18, factor = 1.08f, checkVertical = true)
        return ExtensionState(thumb, index, middle, ring, pinky)
    }

    private fun isExtended(
        landmarks: List<NormalizedLandmark>,
        wrist: Pair<Float, Float>,
        tip: Int,
        base: Int,
        factor: Float = 1.12f,
        checkVertical: Boolean = true,
    ): Boolean {
        val tipPoint = point(landmarks, tip)
        val basePoint = point(landmarks, base)

        val tipDistance = distance(point(landmarks, tip), wrist)
        val baseDistance = distance(point(landmarks, base), wrist)
        val byDistance = tipDistance > baseDistance * factor
        val byVertical = (basePoint.second - tipPoint.second) > 0.02f
        return byDistance || (checkVertical && byVertical)
    }

    private fun point(landmarks: List<NormalizedLandmark>, idx: Int): Pair<Float, Float> {
        val lm = landmarks[idx]
        return lm.x() to lm.y()
    }

    private fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        val dx = a.first - b.first
        val dy = a.second - b.second
        return sqrt(dx * dx + dy * dy)
    }
}
