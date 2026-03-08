package com.eyetracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity(), FoldableDeviceHelper.FoldStateListener {

    private lateinit var btnToggleTracking: Button
    private lateinit var btnCalibrate: Button
    private lateinit var tvStatus: TextView
    private lateinit var sliderSensitivity: Slider
    private lateinit var tvSensitivityValue: TextView
    private lateinit var sliderHighlightSize: Slider
    private lateinit var tvHighlightSizeValue: TextView
    private lateinit var btnColorRed: Button
    private lateinit var btnColorBlue: Button
    private lateinit var btnColorGreen: Button
    private lateinit var btnColorYellow: Button

    private var isTracking = false
    private lateinit var foldableHelper: FoldableDeviceHelper
    private var hasShownCalibrationPrompt = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) checkOverlayPermission() else showPermissionDeniedDialog("Camera")
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { checkCameraPermission() }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) startTracking()
        else showPermissionDeniedDialog("Draw Over Apps")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggleTracking    = findViewById(R.id.btnToggleTracking)
        btnCalibrate         = findViewById(R.id.btnCalibrate)
        tvStatus             = findViewById(R.id.tvStatus)
        sliderSensitivity    = findViewById(R.id.sliderSensitivity)
        tvSensitivityValue   = findViewById(R.id.tvSensitivityValue)
        sliderHighlightSize  = findViewById(R.id.sliderHighlightSize)
        tvHighlightSizeValue = findViewById(R.id.tvHighlightSizeValue)
        btnColorRed          = findViewById(R.id.btnColorRed)
        btnColorBlue         = findViewById(R.id.btnColorBlue)
        btnColorGreen        = findViewById(R.id.btnColorGreen)
        btnColorYellow       = findViewById(R.id.btnColorYellow)

        // Initialize foldable device helper
        foldableHelper = FoldableDeviceHelper(this)
        foldableHelper.addListener(this)

        setupUI()
    }

    override fun onResume() {
        super.onResume()
        updateTrackingButton()
        
        // Show calibration prompt on first launch
        if (!hasShownCalibrationPrompt) {
            hasShownCalibrationPrompt = true
            showCalibrationPrompt()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        foldableHelper.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        super.onDestroy()
        foldableHelper.removeListener(this)
    }

    // FoldableDeviceHelper.FoldStateListener implementation
    override fun onFoldStateChanged() {
        // Device was folded/unfolded - prompt for recalibration
        if (CalibrationManager.isCalibrated()) {
            showRecalibrationPrompt()
        }
    }

    private fun setupUI() {
        btnToggleTracking.setOnClickListener {
            if (isTracking) stopTracking() else requestAllPermissions()
        }
        btnCalibrate.setOnClickListener { 
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        sliderSensitivity.addOnChangeListener { _, value, _ ->
            tvSensitivityValue.text = "${value.toInt()}%"
            EyeTrackingService.sensitivity = value / 100f
        }
        sliderHighlightSize.addOnChangeListener { _, value, _ ->
            tvHighlightSizeValue.text = "${value.toInt()}dp"
            EyeTrackingService.highlightRadius = value.toInt()
        }

        btnColorRed.setOnClickListener    { EyeTrackingService.highlightColor = 0xCCFF4444.toInt(); updateColorSelection(0) }
        btnColorBlue.setOnClickListener   { EyeTrackingService.highlightColor = 0xCC4488FF.toInt(); updateColorSelection(1) }
        btnColorGreen.setOnClickListener  { EyeTrackingService.highlightColor = 0xCC44FF88.toInt(); updateColorSelection(2) }
        btnColorYellow.setOnClickListener { EyeTrackingService.highlightColor = 0xCCFFDD00.toInt(); updateColorSelection(3) }
    }

    private fun updateColorSelection(selectedIndex: Int) {
        listOf(btnColorRed, btnColorBlue, btnColorGreen, btnColorYellow)
            .forEachIndexed { i, btn -> btn.alpha = if (i == selectedIndex) 1f else 0.4f }
    }

    private fun updateTrackingButton() {
        isTracking = EyeTrackingService.isRunning
        if (isTracking) {
            btnToggleTracking.text = "⏹ Stop Eye Tracking"
            btnToggleTracking.setBackgroundColor(0xFFE53935.toInt())
            tvStatus.text = "● Tracking Active"
            tvStatus.setTextColor(0xFF4CAF50.toInt())
        } else {
            btnToggleTracking.text = "▶ Start Eye Tracking"
            btnToggleTracking.setBackgroundColor(0xFF1976D2.toInt())
            tvStatus.text = "○ Not Tracking"
            tvStatus.setTextColor(0xFF9E9E9E.toInt())
        }
    }

    private fun requestAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            checkCameraPermission()
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) checkOverlayPermission()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun checkOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            startTracking()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Draw Over Apps Permission")
                .setMessage("Eye Tracker needs permission to draw a highlight overlay on top of other apps. Please enable 'Display over other apps' for Eye Tracker.")
                .setPositiveButton("Open Settings") { _, _ ->
                    overlayPermissionLauncher.launch(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun startTracking() {
        ContextCompat.startForegroundService(this,
            Intent(this, EyeTrackingService::class.java).apply { action = EyeTrackingService.ACTION_START })
        isTracking = true
        updateTrackingButton()
        Toast.makeText(this, "Eye tracking started!", Toast.LENGTH_SHORT).show()
    }

    private fun stopTracking() {
        startService(Intent(this, EyeTrackingService::class.java).apply { action = EyeTrackingService.ACTION_STOP })
        isTracking = false
        updateTrackingButton()
        Toast.makeText(this, "Eye tracking stopped.", Toast.LENGTH_SHORT).show()
    }

    private fun showCalibrationPrompt() {
        AlertDialog.Builder(this)
            .setTitle("Eye Calibration")
            .setMessage("Would you like to calibrate eye tracking for better accuracy?\n\n" +
                    "Calibration takes about 30 seconds and helps improve gaze tracking precision.")
            .setPositiveButton("Calibrate Now") { _, _ ->
                startActivity(Intent(this, CalibrationActivity::class.java))
            }
            .setNegativeButton("Skip") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    private fun showRecalibrationPrompt() {
        AlertDialog.Builder(this)
            .setTitle("Recalibration Recommended")
            .setMessage("Your device configuration has changed (fold/unfold detected).\n\n" +
                    "Recalibrating will ensure accurate eye tracking.")
            .setPositiveButton("Recalibrate") { _, _ ->
                startActivity(Intent(this, CalibrationActivity::class.java))
            }
            .setNegativeButton("Later") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    private fun showPermissionDeniedDialog(permission: String) {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("$permission permission is required. Please grant it in app settings.")
            .setPositiveButton("Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Cancel", null).show()
    }
}
