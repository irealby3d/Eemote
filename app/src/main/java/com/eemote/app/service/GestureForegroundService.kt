package com.eemote.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.eemote.app.MainActivity
import com.eemote.app.R
import com.eemote.app.gesture.DetectedGesture
import com.eemote.app.gesture.HandGestureAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GestureForegroundService : LifecycleService() {

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: HandGestureAnalyzer? = null
    private var lastStatusAt = 0L

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return Service.START_NOT_STICKY
        }

        startForegroundCompat(buildNotification(getString(R.string.background_notif_starting)))
        if (!running) {
            startGesturePipeline()
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        running = false
        analyzer?.close()
        analyzer = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun startGesturePipeline() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            updateNotification(getString(R.string.background_notif_camera_denied))
            stopSelf()
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = try {
                cameraProviderFuture.get()
            } catch (_: Throwable) {
                updateNotification(getString(R.string.background_notif_camera_failed))
                stopSelf()
                return@addListener
            }
            cameraProvider = provider

            analyzer = try {
                HandGestureAnalyzer(
                    context = this,
                    onGestureDetected = { gesture -> onGestureDetected(gesture) },
                    onDetectionState = { state -> maybeUpdateNotification(state) },
                )
            } catch (_: Throwable) {
                updateNotification(getString(R.string.background_notif_analyzer_failed))
                stopSelf()
                null
            }

            val currentAnalyzer = analyzer ?: return@addListener
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, currentAnalyzer) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    imageAnalyzer,
                )
                running = true
                updateNotification(getString(R.string.background_notif_running))
            } catch (_: Throwable) {
                updateNotification(getString(R.string.background_notif_camera_failed))
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onGestureDetected(gesture: DetectedGesture) {
        if (!RemoteAccessibilityService.isRunning()) {
            updateNotification(getString(R.string.background_notif_accessibility_off))
            return
        }
        val dispatched = RemoteAccessibilityService.dispatch(gesture.command)
        if (dispatched) {
            updateNotification("Gesture: ${gesture.command.name}")
        } else {
            updateNotification("Gesture detected, action blocked")
        }
    }

    private fun maybeUpdateNotification(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatusAt < 1200L) return
        lastStatusAt = now
        updateNotification(text)
    }

    private fun updateNotification(contentText: String) {
        lastStatus = contentText
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return
        notificationManager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        ensureNotificationChannel()

        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, GestureForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.background_notif_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.background_stop), stopIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.background_notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.background_notif_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "eemote_background_camera"
        private const val NOTIFICATION_ID = 7101
        const val ACTION_START = "com.eemote.app.action.START_BACKGROUND"
        const val ACTION_STOP = "com.eemote.app.action.STOP_BACKGROUND"

        @Volatile
        private var running: Boolean = false
        @Volatile
        private var lastStatus: String = "IDLE"

        fun start(context: Context) {
            val intent = Intent(context, GestureForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GestureForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun isRunning(): Boolean = running

        fun currentStatus(): String = lastStatus
    }
}
