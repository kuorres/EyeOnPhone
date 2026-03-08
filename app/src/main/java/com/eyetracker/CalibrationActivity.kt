package com.eyetracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CalibrationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CalibrationActivity"
        private const val SAMPLES_PER_POINT = 15  // Number of samples to collect per point
        private const val SAMPLE_DELAY_MS = 100L   // Delay between samples
    }

    // UI Elements
    private lateinit var cameraPreview: PreviewView
    private lateinit var targetContainer: FrameLayout
    private lateinit var tvProgress: TextView
    private lateinit var tvInstructions: TextView
    private lateinit var btnCancel: Button
    private lateinit var completionOverlay: LinearLayout
    private lateinit var tvCompletionTitle: TextView
    private lateinit var tvQuality: TextView
    private lateinit var tvQualityDesc: TextView
    private lateinit var btnRecalibrate: Button
    private lateinit var btnDone: Button

    // Camera and ML Kit
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
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

    // Calibration
    private lateinit var targetView: CalibrationTargetView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isCollectingSamples = false
    private val collectedSamples = mutableListOf<PointF>()
    private var currentSampleCount = 0

    // Latest eye position
    private var latestEyePosition: PointF? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Full screen immersive mode
        window.apply {
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        
        setContentView(R.layout.activity_calibration)
        
        initializeViews()
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        if (checkCameraPermission()) {
            startCalibration()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun initializeViews() {
        cameraPreview = findViewById(R.id.cameraPreview)
        targetContainer = findViewById(R.id.targetContainer)
        tvProgress = findViewById(R.id.tvCalibrationProgress)
        tvInstructions = findViewById(R.id.tvCalibrationInstructions)
        btnCancel = findViewById(R.id.btnCancelCalibration)
        completionOverlay = findViewById(R.id.completionOverlay)
        tvCompletionTitle = findViewById(R.id.tvCompletionTitle)
        tvQuality = findViewById(R.id.tvCalibrationQuality)
        tvQualityDesc = findViewById(R.id.tvQualityDescription)
        btnRecalibrate = findViewById(R.id.btnRecalibrate)
        btnDone = findViewById(R.id.btnDone)

        // Create and add calibration target view
        targetView = CalibrationTargetView(this)
        targetContainer.addView(targetView)

        btnCancel.setOnClickListener {
            finish()
        }

        btnRecalibrate.setOnClickListener {
            restartCalibration()
        }

        btnDone.setOnClickListener {
            finish()
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun startCalibration() {
        // Clear any previous calibration
        CalibrationManager.clearCalibration()
        
        // Initialize calibration points
        targetView.initializePoints()
        updateProgress()
        
        // Start camera
        startCamera()
        
        // Start pulsing animation
        targetView.startAnimation()
        
        // Start collecting samples after a short delay
        mainHandler.postDelayed({
            startCollectingSamples()
        }, 1500)
    }

    private fun restartCalibration() {
        completionOverlay.visibility = View.GONE
        CalibrationManager.clearCalibration()
        targetView.initializePoints()
        updateProgress()
        targetView.startAnimation()
        
        mainHandler.postDelayed({
            startCollectingSamples()
        }, 1500)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(cameraPreview.surfaceProvider)

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
                cameraProvider?.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed: ${e.message}")
            }

        }, ContextCompat.getMainExecutor(this))
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
                    val face = faces[0]
                    
                    // Get head pose angles
                    val yaw = face.headEulerAngleY
                    val pitch = face.headEulerAngleX
                    
                    // Normalize to 0-1 range (same logic as EyeTrackingService)
                    val maxYaw = 25f
                    val maxPitch = 20f
                    
                    val normX = ((-yaw / maxYaw) * 0.5f + 0.5f).coerceIn(0f, 1f)
                    val normY = ((pitch / maxPitch) * 0.5f + 0.5f).coerceIn(0f, 1f)
                    
                    latestEyePosition = PointF(normX, normY)
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun startCollectingSamples() {
        if (isCollectingSamples) return
        
        isCollectingSamples = true
        currentSampleCount = 0
        collectedSamples.clear()
        
        tvInstructions.text = "Look at the pulsing circle and hold your gaze steady"
        
        collectNextSample()
    }

    private fun collectNextSample() {
        if (!isCollectingSamples) return
        
        latestEyePosition?.let { eyePos ->
            collectedSamples.add(PointF(eyePos.x, eyePos.y))
            currentSampleCount++
            
            if (currentSampleCount >= SAMPLES_PER_POINT) {
                // Collected enough samples for this point
                finishCurrentPoint()
            } else {
                // Collect next sample
                mainHandler.postDelayed({
                    collectNextSample()
                }, SAMPLE_DELAY_MS)
            }
        } ?: run {
            // No face detected, retry
            mainHandler.postDelayed({
                collectNextSample()
            }, SAMPLE_DELAY_MS)
        }
    }

    private fun finishCurrentPoint() {
        isCollectingSamples = false
        
        // Compute average of collected samples
        val avgX = collectedSamples.map { it.x }.average().toFloat()
        val avgY = collectedSamples.map { it.y }.average().toFloat()
        val avgEyePos = PointF(avgX, avgY)
        
        // Get current target position
        val target = targetView.getCurrentTarget() ?: return
        
        // Add calibration point
        CalibrationManager.addCalibrationPoint(target, avgEyePos)
        
        Log.d(TAG, "Point ${targetView.getCurrentIndex() + 1}: Target=$target, Eye=$avgEyePos")
        
        // Move to next point
        val hasMore = targetView.nextPoint()
        
        if (hasMore) {
            updateProgress()
            mainHandler.postDelayed({
                startCollectingSamples()
            }, 500)
        } else {
            // Calibration complete
            completeCalibration()
        }
    }

    private fun completeCalibration() {
        targetView.stopAnimation()
        
        // Compute calibration transformation
        val success = CalibrationManager.finalizeCalibration()
        
        if (success) {
            val quality = CalibrationManager.getCalibrationQuality()
            showCompletionScreen(quality)
        } else {
            Toast.makeText(this, "Calibration failed. Please try again.", Toast.LENGTH_LONG).show()
            restartCalibration()
        }
    }

    private fun showCompletionScreen(quality: Int) {
        completionOverlay.visibility = View.VISIBLE
        tvQuality.text = "Quality: $quality%"
        
        val description = when {
            quality >= 80 -> "Excellent! Your eye tracking accuracy is greatly improved."
            quality >= 60 -> "Good calibration. Eye tracking should be more accurate."
            quality >= 40 -> "Fair calibration. Consider recalibrating for better results."
            else -> "Low quality. Please recalibrate for better accuracy."
        }
        
        tvQualityDesc.text = description
        
        Log.d(TAG, "Calibration completed with quality: $quality%")
    }

    private fun updateProgress() {
        val current = targetView.getCurrentIndex() + 1
        val total = targetView.getTotalPoints()
        tvProgress.text = "Point $current of $total"
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        faceDetector.close()
        targetView.stopAnimation()
    }
}
