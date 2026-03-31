package com.eemote.app.gesture

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

class HandGestureAnalyzer(
    context: Context,
    private val onGestureDetected: (DetectedGesture) -> Unit,
    private val onDetectionState: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val gestureInterpreter = GestureInterpreter()
    private var handLandmarker: HandLandmarker? = null

    init {
        handLandmarker = try {
            createHandLandmarker(context)
        } catch (_: Throwable) {
            onDetectionState("MODEL INIT FAILED")
            null
        }
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val landmarker = handLandmarker
        if (landmarker == null) {
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
            val result = landmarker.detectForVideo(mpImage, imageProcessingOptions, timestamp)
            val handLandmarks: List<List<NormalizedLandmark>> = result.landmarks()
            if (handLandmarks.isNotEmpty()) {
                onDetectionState("HAND DETECTED")
                val gesture = gestureInterpreter.interpret(
                    handLandmarks.first(),
                    timestamp,
                )
                if (gesture != null) {
                    onGestureDetected(gesture)
                }
            } else {
                onDetectionState("SHOW HAND")
            }
        } catch (_: Throwable) {
            onDetectionState("DETECTION ERROR")
        } finally {
            imageProxy.close()
        }
    }

    fun close() {
        handLandmarker?.close()
    }

    private fun createHandLandmarker(context: Context): HandLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_FILE)
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinHandDetectionConfidence(0.35f)
            .setMinHandPresenceConfidence(0.35f)
            .setMinTrackingConfidence(0.35f)
            .setNumHands(1)
            .setRunningMode(RunningMode.VIDEO)
            .build()

        return HandLandmarker.createFromOptions(context, options)
    }

    companion object {
        private const val MODEL_FILE = "hand_landmarker.task"
    }
}
