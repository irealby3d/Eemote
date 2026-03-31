package com.eemote.app.gesture

import android.content.Context
import android.os.SystemClock
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
    private val handLandmarker: HandLandmarker = createHandLandmarker(context)

    override fun analyze(imageProxy: ImageProxy) {
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

            val result = handLandmarker.detect(mpImage, imageProcessingOptions)
            val handLandmarks: List<List<NormalizedLandmark>> = result.landmarks()
            if (handLandmarks.isNotEmpty()) {
                onDetectionState("HAND DETECTED")
                val gesture = gestureInterpreter.interpret(
                    handLandmarks.first(),
                    SystemClock.uptimeMillis(),
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
        handLandmarker.close()
    }

    private fun createHandLandmarker(context: Context): HandLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_FILE)
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinHandDetectionConfidence(0.55f)
            .setMinHandPresenceConfidence(0.55f)
            .setMinTrackingConfidence(0.55f)
            .setNumHands(1)
            .setRunningMode(RunningMode.IMAGE)
            .build()

        return HandLandmarker.createFromOptions(context, options)
    }

    companion object {
        private const val MODEL_FILE = "hand_landmarker.task"
    }
}
