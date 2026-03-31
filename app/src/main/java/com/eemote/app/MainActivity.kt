package com.eemote.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.eemote.app.databinding.ActivityMainBinding
import com.eemote.app.gesture.DetectedGesture
import com.eemote.app.gesture.GestureCategory
import com.eemote.app.gesture.HandGestureAnalyzer
import com.eemote.app.service.GestureForegroundService
import com.eemote.app.service.RemoteAccessibilityService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: HandGestureAnalyzer? = null
    private var localCameraBound = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (!GestureForegroundService.isRunning()) {
                startCamera()
            }
        } else {
            binding.liveStatusText.text = "CAMERA PERMISSION DENIED"
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Foreground service can still run, but asking once helps on Android 13+.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.backgroundButton.setOnClickListener {
            toggleBackgroundControl()
        }

        if (hasCameraPermission()) {
            if (!GestureForegroundService.isRunning()) {
                startCamera()
            } else {
                binding.liveStatusText.text = "BACKGROUND MODE ACTIVE"
            }
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityButtonState()
        updateBackgroundButtonState()
        if (GestureForegroundService.isRunning()) {
            stopLocalCamera()
            binding.liveStatusText.text = "BACKGROUND MODE ACTIVE"
        }
    }

    override fun onDestroy() {
        stopLocalCamera()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        if (localCameraBound || GestureForegroundService.isRunning()) return

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            analyzer = try {
                HandGestureAnalyzer(
                    context = this,
                    onGestureDetected = { gesture -> runOnUiThread { onGestureDetected(gesture) } },
                    onDetectionState = { state -> runOnUiThread { binding.liveStatusText.text = state } },
                )
            } catch (_: Throwable) {
                binding.liveStatusText.text = "ANALYZER INIT FAILED"
                null
            }
            val currentAnalyzer = analyzer
            if (currentAnalyzer == null) {
                binding.liveStatusText.text = "ANALYZER INIT FAILED"
                return@addListener
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, currentAnalyzer)
                }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalyzer,
                )
                localCameraBound = true
            } catch (_: Exception) {
                binding.liveStatusText.text = "CAMERA START FAILED"
                localCameraBound = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopLocalCamera() {
        analyzer?.close()
        analyzer = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        localCameraBound = false
    }

    private fun toggleBackgroundControl() {
        if (GestureForegroundService.isRunning()) {
            GestureForegroundService.stop(this)
            updateBackgroundButtonState()
            if (hasCameraPermission()) {
                startCamera()
            }
            binding.liveStatusText.text = "BACKGROUND MODE STOPPED"
            return
        }

        if (!hasCameraPermission()) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        requestNotificationPermissionIfNeeded()
        stopLocalCamera()
        GestureForegroundService.start(this)
        updateBackgroundButtonState()
        binding.liveStatusText.text = "BACKGROUND MODE STARTING"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun onGestureDetected(gesture: DetectedGesture) {
        binding.liveStatusText.text = gesture.command.name
        highlightCard(gesture.category)

        if (isAccessibilityEnabled()) {
            RemoteAccessibilityService.dispatch(gesture.command)
        }
    }

    private fun highlightCard(category: GestureCategory) {
        val activeColor = 0x66FFD54F

        val mapping = mapOf(
            GestureCategory.STOP to binding.cardStop,
            GestureCategory.ROTATE to binding.cardRotate,
            GestureCategory.ZOOM to binding.cardZoom,
            GestureCategory.TURN to binding.cardTurn,
            GestureCategory.SWIPE to binding.cardSwipe,
        )

        mapping.values.forEach { it.setCardBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light)) }
        mapping[category]?.setCardBackgroundColor(activeColor.toInt())
    }

    private fun updateAccessibilityButtonState() {
        if (isAccessibilityEnabled()) {
            binding.accessibilityButton.text = "Accessibility Enabled"
            binding.accessibilityButton.isEnabled = false
        } else {
            binding.accessibilityButton.text = "Enable Accessibility Service"
            binding.accessibilityButton.isEnabled = true
        }
    }

    private fun updateBackgroundButtonState() {
        if (GestureForegroundService.isRunning()) {
            binding.backgroundButton.text = getString(R.string.background_control_stop)
        } else {
            binding.backgroundButton.text = getString(R.string.background_control_start)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val componentName = ComponentName(this, RemoteAccessibilityService::class.java)
        val expectedFull = componentName.flattenToString()
        val expectedShort = componentName.flattenToShortString()
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false

        return enabledServices.split(':').any {
            it.equals(expectedFull, ignoreCase = true) ||
                it.equals(expectedShort, ignoreCase = true)
        }
    }
}
