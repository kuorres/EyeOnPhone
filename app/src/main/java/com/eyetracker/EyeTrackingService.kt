package com.eyetracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Executor

class EyeTrackingService : LifecycleService() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "eye_tracking_channel"

        var isRunning = false
        var sensitivity = 0.7f      // 0.1 – 1.0 (maps angle range to screen)
        var highlightRadius = 60     // dp
        var highlightColor = 0xCCFF4444.toInt()

        private val TAG = "EyeTrackingService"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var gazeOverlay: GazeOverlayView
    private var overlayAdded = false

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

    // Smoothing: exponential moving average
    private var smoothX = 0.5f
    private var smoothY = 0.5f
    private val smoothingAlpha = 0.2f   // lower = smoother but laggier

    private val mainHandler = Handler(Looper.getMainLooper())

    // ML Kit face detector (ACCURATE mode with contours + landmark)
    private val faceDetector by lazy {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .enableTracking()
            .build()
        FaceDetection.getClient(opts)
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startEyeTracking()
            ACTION_STOP  -> stopEyeTracking()
        }
        return START_STICKY
    }

    // ─── Start / Stop ──────────────────────────────────────────────────────────

    private fun startEyeTracking() {
        if (isRunning) return
        isRunning = true

        startForeground(NOTIFICATION_ID, buildNotification())
        addOverlay()
        startCamera()
    }

    private fun stopEyeTracking() {
        isRunning = false
        cameraProvider?.unbindAll()
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        removeOverlay()
        faceDetector.close()
    }

    // ─── Overlay ───────────────────────────────────────────────────────────────

    private fun addOverlay() {
        if (overlayAdded) return

        gazeOverlay = GazeOverlayView(this)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        mainHandler.post {
            try {
                windowManager.addView(gazeOverlay, params)
                overlayAdded = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add overlay: ${e.message}")
            }
        }
    }

    private fun removeOverlay() {
        if (!overlayAdded) return
        mainHandler.post {
            try {
                windowManager.removeView(gazeOverlay)
            } catch (_: Exception) {}
            overlayAdded = false
        }
    }

    // ─── Camera + ML Kit ──────────────────────────────────────────────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processFrame(imageProxy)
            }

            try {
                cameraProvider?.unbindAll()
                // Use FRONT camera for eye tracking
                cameraProvider?.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed: ${e.message}")
            }

        }, Executor { mainHandler.post(it) })
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]  // Track closest/first face

                    // Head pose angles (degrees):
                    //   eulerY: negative = looking right, positive = looking left
                    //   eulerX: negative = looking up,   positive = looking down
                    val yaw   = face.headEulerAngleY  // left/right
                    val pitch = face.headEulerAngleX  // up/down

                    // Clamp and normalize angles to [0,1] screen space
                    // Typical comfortable viewing range: ±25° yaw, ±20° pitch
                    val maxYaw   = 25f / sensitivity
                    val maxPitch = 20f / sensitivity

                    // Front camera mirrors, so yaw direction is already correct
                    val rawX = ((-yaw   / maxYaw)   * 0.5f + 0.5f).coerceIn(0f, 1f)
                    val rawY = ((pitch  / maxPitch) * 0.5f + 0.5f).coerceIn(0f, 1f)

                    // Apply calibration transformation if available
                    val rawPoint = android.graphics.PointF(rawX, rawY)
                    val calibratedPoint = CalibrationManager.applyCalibration(rawPoint)
                    
                    val normX = calibratedPoint.x
                    val normY = calibratedPoint.y

                    // Exponential moving average for smoothing
                    smoothX = smoothingAlpha * normX + (1 - smoothingAlpha) * smoothX
                    smoothY = smoothingAlpha * normY + (1 - smoothingAlpha) * smoothY

                    mainHandler.post {
                        if (overlayAdded) {
                            gazeOverlay.updateGaze(
                                normX     = smoothX,
                                normY     = smoothY,
                                radius    = highlightRadius,
                                color     = highlightColor,
                                faceFound = true
                            )
                        }
                    }
                } else {
                    // No face found – hide highlight
                    mainHandler.post {
                        if (overlayAdded) gazeOverlay.updateGaze(faceFound = false)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Face detection failed: ${e.message}")
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Eye Tracking",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Eye tracking running in background" }
            )
        }

        val stopIntent = Intent(this, EyeTrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("👁 Eye Tracker Active")
            .setContentText("Tracking your gaze position on screen")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .setOngoing(true)
            .build()
    }
}
