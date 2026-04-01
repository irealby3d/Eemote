package com.eemote.app.gesture

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

class GestureInterpreter {
    private data class TimedPoint(val timestampMs: Long, val x: Float, val y: Float)
    private data class TimedValue(val timestampMs: Long, val value: Float)
    private data class TemplateHit(val timestampMs: Long, val name: String, val score: Float)

    private val wristTrail = ArrayDeque<TimedPoint>()
    private val pinchTrail = ArrayDeque<TimedValue>()
    private val templateTrail = ArrayDeque<TemplateHit>()

    private val lastCommandAt = mutableMapOf<GestureCommand, Long>()
    private var lastAnyCommandAt = 0L
    private var pausedUntilMs = 0L

    fun interpret(
        landmarks: List<NormalizedLandmark>,
        templateName: String?,
        templateScore: Float,
        timestampMs: Long,
    ): DetectedGesture? {
        if (landmarks.size < 21) return null

        val wrist = point(landmarks, 0)
        val handSize = estimateHandSize(landmarks)
        val pinchDistance = distance(point(landmarks, 4), point(landmarks, 8))

        trackWrist(timestampMs, wrist)
        trackPinch(timestampMs, pinchDistance)
        trackTemplate(timestampMs, templateName, templateScore)

        val normalizedTemplate = normalizeTemplate(templateName)
        val ext = extensionState(landmarks)
        val movement = movementVector(windowMs = 460L)
        val movementMagnitude = magnitude(movement)

        val openPalm = ext.totalExtended >= 4 || isTemplateStable("open_palm", 0.42f, 3, 650L)
        if (openPalm) {
            pausedUntilMs = timestampMs + 1800L
            return emit(
                timestampMs = timestampMs,
                gesture = DetectedGesture(GestureCategory.STOP, GestureCommand.STOP),
                perCommandCooldownMs = 1200L,
                globalCooldownMs = 300L,
            )
        }

        if (timestampMs < pausedUntilMs) {
            return null
        }

        val circular = hasCircularMotion(handSize)
        val pinchClosed = pinchDistance < handSize * 0.50f
        if (
            circular && (
                pinchClosed ||
                    ext.totalExtended <= 1 ||
                    isTemplateStable("closed_fist", 0.50f, 3, 700L)
                )
        ) {
            return emit(
                timestampMs = timestampMs,
                gesture = DetectedGesture(GestureCategory.ROTATE, GestureCommand.ROTATE),
                perCommandCooldownMs = 1150L,
            )
        }

        val pinchDelta = pinchDelta(windowMs = 280L)
        val pinchShape = (ext.thumb && ext.index && !ext.middle && !ext.ring) || pinchClosed
        if (pinchShape && pinchDelta != null && abs(pinchDelta) > handSize * 0.11f) {
            val command = if (pinchDelta > 0f) GestureCommand.ZOOM_IN else GestureCommand.ZOOM_OUT
            return emit(
                timestampMs = timestampMs,
                gesture = DetectedGesture(GestureCategory.ZOOM, command),
                perCommandCooldownMs = 650L,
            )
        }

        val swipeCandidate = (
            isTemplateStable("victory", 0.45f, 2, 650L) ||
                ext.totalExtended >= 2 ||
                normalizedTemplate == "pointing_up"
            )
        if (swipeCandidate && movementMagnitude > handSize * 0.58f) {
            val command = dominantSwipeCommand(movement)
            return emit(
                timestampMs = timestampMs,
                gesture = DetectedGesture(GestureCategory.SWIPE, command),
                perCommandCooldownMs = 720L,
            )
        }

        if (isTemplateStable("thumb_up", 0.46f, 2, 700L)) {
            return emit(
                timestampMs = timestampMs,
                gesture = DetectedGesture(GestureCategory.TURN, GestureCommand.TURN_RIGHT),
                perCommandCooldownMs = 1100L,
            )
        }
        if (isTemplateStable("thumb_down", 0.46f, 2, 700L)) {
            return emit(
                timestampMs = timestampMs,
                gesture = DetectedGesture(GestureCategory.TURN, GestureCommand.TURN_LEFT),
                perCommandCooldownMs = 1100L,
            )
        }

        val oneFinger = ext.index && !ext.middle && !ext.ring && !ext.pinky
        if ((normalizedTemplate == "pointing_up" || oneFinger) && abs(movement.first) > handSize * 0.34f) {
            val command = if (movement.first > 0f) GestureCommand.TURN_RIGHT else GestureCommand.TURN_LEFT
            return emit(
                timestampMs = timestampMs,
                gesture = DetectedGesture(GestureCategory.TURN, command),
                perCommandCooldownMs = 800L,
            )
        }

        // Static fallback mapping for hard devices/camera angles:
        // 1 finger => Back, 2 fingers => Home, 3 fingers => Recents.
        if (oneFinger) {
            return emit(
                timestampMs = timestampMs,
                gesture = DetectedGesture(GestureCategory.TURN, GestureCommand.TURN_LEFT),
                perCommandCooldownMs = 1300L,
            )
        }
        if (ext.index && ext.middle && !ext.ring && !ext.pinky) {
            return emit(
                timestampMs = timestampMs,
                gesture = DetectedGesture(GestureCategory.TURN, GestureCommand.TURN_RIGHT),
                perCommandCooldownMs = 1300L,
            )
        }
        if (ext.index && ext.middle && ext.ring && !ext.pinky) {
            return emit(
                timestampMs = timestampMs,
                gesture = DetectedGesture(GestureCategory.ROTATE, GestureCommand.ROTATE),
                perCommandCooldownMs = 1400L,
            )
        }

        return null
    }

    private fun emit(
        timestampMs: Long,
        gesture: DetectedGesture,
        perCommandCooldownMs: Long,
        globalCooldownMs: Long = 260L,
    ): DetectedGesture? {
        if (timestampMs - lastAnyCommandAt < globalCooldownMs) return null
        val lastCommandTs = lastCommandAt[gesture.command] ?: 0L
        if (timestampMs - lastCommandTs < perCommandCooldownMs) return null

        lastAnyCommandAt = timestampMs
        lastCommandAt[gesture.command] = timestampMs
        return gesture
    }

    private fun dominantSwipeCommand(movement: Pair<Float, Float>): GestureCommand {
        return if (abs(movement.first) > abs(movement.second)) {
            if (movement.first > 0f) GestureCommand.SWIPE_RIGHT else GestureCommand.SWIPE_LEFT
        } else {
            if (movement.second > 0f) GestureCommand.SWIPE_DOWN else GestureCommand.SWIPE_UP
        }
    }

    private fun movementVector(windowMs: Long): Pair<Float, Float> {
        if (wristTrail.size < 2) return 0f to 0f
        val latest = wristTrail.last()
        val reference = wristTrail.firstOrNull { latest.timestampMs - it.timestampMs <= windowMs } ?: wristTrail.first()
        return (latest.x - reference.x) to (latest.y - reference.y)
    }

    private fun hasCircularMotion(handSize: Float): Boolean {
        if (wristTrail.size < 7) return false

        val recent = wristTrail.takeLast(9)
        val centerX = recent.map { it.x }.average().toFloat()
        val centerY = recent.map { it.y }.average().toFloat()
        val radii = recent.map { distance(it.x to it.y, centerX to centerY) }
        val avgRadius = radii.average().toFloat()
        if (avgRadius < handSize * 0.12f) return false

        val variance = radii.map { (it - avgRadius).pow(2f) }.average().toFloat()
        if (variance > handSize * handSize * 0.06f) return false

        var totalAngle = 0f
        for (i in 1 until recent.size) {
            val a1 = atan2(recent[i - 1].y - centerY, recent[i - 1].x - centerX)
            val a2 = atan2(recent[i].y - centerY, recent[i].x - centerX)
            var delta = a2 - a1
            if (delta > Math.PI) delta -= (2 * Math.PI).toFloat()
            if (delta < -Math.PI) delta += (2 * Math.PI).toFloat()
            totalAngle += delta
        }
        return abs(totalAngle) > 2.45f
    }

    private fun pinchDelta(windowMs: Long): Float? {
        if (pinchTrail.size < 2) return null
        val latest = pinchTrail.last()
        val reference = pinchTrail.firstOrNull { latest.timestampMs - it.timestampMs <= windowMs } ?: pinchTrail.first()
        return latest.value - reference.value
    }

    private fun trackWrist(timestampMs: Long, point: Pair<Float, Float>) {
        wristTrail.addLast(TimedPoint(timestampMs, point.first, point.second))
        while (wristTrail.size > 14) wristTrail.removeFirst()
        while (wristTrail.isNotEmpty() && timestampMs - wristTrail.first().timestampMs > 1200L) {
            wristTrail.removeFirst()
        }
    }

    private fun trackPinch(timestampMs: Long, pinchDistance: Float) {
        pinchTrail.addLast(TimedValue(timestampMs, pinchDistance))
        while (pinchTrail.size > 14) pinchTrail.removeFirst()
        while (pinchTrail.isNotEmpty() && timestampMs - pinchTrail.first().timestampMs > 900L) {
            pinchTrail.removeFirst()
        }
    }

    private fun trackTemplate(timestampMs: Long, templateName: String?, templateScore: Float) {
        val normalized = normalizeTemplate(templateName)
        if (normalized.isBlank() || normalized == "none") return

        templateTrail.addLast(TemplateHit(timestampMs, normalized, templateScore))
        while (templateTrail.size > 16) templateTrail.removeFirst()
        while (templateTrail.isNotEmpty() && timestampMs - templateTrail.first().timestampMs > 1400L) {
            templateTrail.removeFirst()
        }
    }

    private fun isTemplateStable(
        name: String,
        minScore: Float,
        minHits: Int,
        withinMs: Long,
    ): Boolean {
        if (templateTrail.isEmpty()) return false
        val latestTs = templateTrail.last().timestampMs
        return templateTrail.count {
            it.name == name &&
                it.score >= minScore &&
                latestTs - it.timestampMs <= withinMs
        } >= minHits
    }

    private fun normalizeTemplate(templateName: String?): String {
        return templateName
            ?.trim()
            ?.lowercase()
            ?.replace(' ', '_')
            ?: ""
    }

    private fun estimateHandSize(landmarks: List<NormalizedLandmark>): Float {
        val wrist = point(landmarks, 0)
        val middleMcp = point(landmarks, 9)
        val ringMcp = point(landmarks, 13)
        val palm = (distance(wrist, middleMcp) + distance(wrist, ringMcp)) / 2f
        return palm.coerceAtLeast(0.06f)
    }

    private fun magnitude(vector: Pair<Float, Float>): Float {
        return sqrt(vector.first * vector.first + vector.second * vector.second)
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
        val index = isExtended(landmarks, wrist, tip = 8, base = 6, factor = 1.07f, checkVertical = true)
        val middle = isExtended(landmarks, wrist, tip = 12, base = 10, factor = 1.07f, checkVertical = true)
        val ring = isExtended(landmarks, wrist, tip = 16, base = 14, factor = 1.07f, checkVertical = true)
        val pinky = isExtended(landmarks, wrist, tip = 20, base = 18, factor = 1.07f, checkVertical = true)
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
        val byVertical = (basePoint.second - tipPoint.second) > 0.014f
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
