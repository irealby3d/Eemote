package com.eemote.app.gesture

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.processors.ClassifierOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult

class HandGestureAnalyzer(
    context: Context,
    private val onGestureDetected: (DetectedGesture) -> Unit,
    private val onDetectionState: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val gestureInterpreter = GestureInterpreter()
    private var gestureRecognizer: GestureRecognizer? = null

    init {
        gestureRecognizer = try {
            createGestureRecognizer(context)
        } catch (_: Throwable) {
            onDetectionState("MODEL INIT FAILED")
            null
        }
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val recognizer = gestureRecognizer
        if (recognizer == null) {
            onDetectionState("MODEL INIT FAILED")
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        try {
            val mpImage = MediaImageBuilder(mediaImage).build()
            val imageProcessingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(imageProxy.imageInfo.rotationDegrees)
                .build()

            val timestamp = SystemClock.uptimeMillis()
            val result = recognizer.recognizeForVideo(mpImage, imageProcessingOptions, timestamp)
            val handLandmarks = result.landmarks()

            if (handLandmarks.isEmpty()) {
                onDetectionState("SHOW HAND")
                return
            }

            val template = topTemplate(result)
            onDetectionState(renderDetectionState(template))

            val gesture = gestureInterpreter.interpret(
                landmarks = handLandmarks.first(),
                templateName = template?.categoryName(),
                templateScore = template?.score() ?: 0f,
                timestampMs = timestamp,
            )
            if (gesture != null) {
                onGestureDetected(gesture)
            }
        } catch (_: Throwable) {
            onDetectionState("DETECTION ERROR")
        } finally {
            imageProxy.close()
        }
    }

    fun close() {
        gestureRecognizer?.close()
        gestureRecognizer = null
    }

    private fun topTemplate(result: GestureRecognizerResult): Category? {
        return result.gestures()
            .firstOrNull()
            ?.maxByOrNull { it.score() }
            ?.takeIf { it.categoryName().isNotBlank() }
    }

    private fun renderDetectionState(template: Category?): String {
        if (template == null) return "HAND DETECTED"

        val rawName = template.categoryName()
            .ifBlank { template.displayName() }
            .ifBlank { "HAND" }

        if (rawName.equals("none", ignoreCase = true)) {
            return "HAND DETECTED"
        }

        val prettyName = rawName.replace('_', ' ')
        val percent = (template.score() * 100f).toInt().coerceIn(0, 100)
        return "$prettyName $percent%"
    }

    private fun createGestureRecognizer(context: Context): GestureRecognizer {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_FILE)
            .build()

        val cannedClassifierOptions = ClassifierOptions.builder()
            .setScoreThreshold(0.38f)
            .setMaxResults(2)
            .build()

        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.40f)
            .setMinHandPresenceConfidence(0.40f)
            .setMinTrackingConfidence(0.35f)
            .setCannedGesturesClassifierOptions(cannedClassifierOptions)
            .build()

        return GestureRecognizer.createFromOptions(context, options)
    }

    companion object {
        private const val MODEL_FILE = "gesture_recognizer.task"
    }
}
