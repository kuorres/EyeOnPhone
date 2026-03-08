package com.eyetracker

import android.graphics.PointF

/**
 * Singleton manager for calibration state.
 * Provides global access to calibration data and transformation.
 */
object CalibrationManager {
    
    private val calibrationData = CalibrationData()
    
    /**
     * Listener for calibration state changes.
     */
    interface CalibrationListener {
        fun onCalibrationChanged(isCalibrated: Boolean)
    }
    
    private val listeners = mutableListOf<CalibrationListener>()
    
    /**
     * Check if calibration is active.
     */
    fun isCalibrated(): Boolean = calibrationData.isCalibrated
    
    /**
     * Add calibration point.
     */
    fun addCalibrationPoint(targetScreen: PointF, measuredEye: PointF) {
        calibrationData.addPoint(targetScreen, measuredEye)
    }
    
    /**
     * Finalize calibration by computing transformation matrix.
     */
    fun finalizeCalibration(): Boolean {
        val success = calibrationData.computeTransformation()
        if (success) {
            notifyListeners(true)
        }
        return success
    }
    
    /**
     * Clear calibration data (user wants to recalibrate).
     */
    fun clearCalibration() {
        calibrationData.clear()
        notifyListeners(false)
    }
    
    /**
     * Apply calibration transformation to raw eye position.
     * If not calibrated, returns raw position unchanged.
     */
    fun applyCalibration(rawEye: PointF): PointF {
        return if (calibrationData.isCalibrated) {
            calibrationData.applyTransformation(rawEye)
        } else {
            rawEye
        }
    }
    
    /**
     * Get calibration quality score (0-100).
     */
    fun getCalibrationQuality(): Int = calibrationData.getCalibrationQuality()
    
    /**
     * Get number of calibration points collected.
     */
    fun getPointCount(): Int = calibrationData.getPointCount()
    
    /**
     * Add listener for calibration changes.
     */
    fun addListener(listener: CalibrationListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }
    
    /**
     * Remove listener.
     */
    fun removeListener(listener: CalibrationListener) {
        listeners.remove(listener)
    }
    
    private fun notifyListeners(isCalibrated: Boolean) {
        listeners.forEach { it.onCalibrationChanged(isCalibrated) }
    }
}
