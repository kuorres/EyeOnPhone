package com.eyetracker

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.util.Log

/**
 * Helper class to detect foldable device state changes.
 * Monitors screen size and orientation changes to detect fold/unfold events.
 */
class FoldableDeviceHelper(private val context: Context) {

    companion object {
        private const val TAG = "FoldableDeviceHelper"
    }

    interface FoldStateListener {
        fun onFoldStateChanged()
    }

    private var listeners = mutableListOf<FoldStateListener>()
    private var lastScreenSize = 0
    private var lastOrientation = 0

    init {
        captureCurrentState()
    }

    /**
     * Add listener for fold state changes.
     */
    fun addListener(listener: FoldStateListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    /**
     * Remove listener.
     */
    fun removeListener(listener: FoldStateListener) {
        listeners.remove(listener)
    }

    /**
     * Check if device configuration has changed significantly (potential fold/unfold).
     * Should be called from Activity's onConfigurationChanged().
     */
    fun onConfigurationChanged(newConfig: Configuration) {
        val newScreenSize = newConfig.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK
        val newOrientation = newConfig.orientation

        // Detect significant screen size changes (fold/unfold)
        if (newScreenSize != lastScreenSize && lastScreenSize != 0) {
            Log.d(TAG, "Screen size changed: $lastScreenSize -> $newScreenSize (likely fold/unfold)")
            notifyListeners()
        }

        // Also detect orientation changes that might indicate folding
        if (newOrientation != lastOrientation && lastOrientation != 0) {
            Log.d(TAG, "Orientation changed: $lastOrientation -> $newOrientation")
            // Only notify if it's a drastic change
            if (isDrasticChange(newScreenSize, newOrientation)) {
                notifyListeners()
            }
        }

        lastScreenSize = newScreenSize
        lastOrientation = newOrientation
    }

    /**
     * Capture current device state.
     */
    private fun captureCurrentState() {
        val config = context.resources.configuration
        lastScreenSize = config.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK
        lastOrientation = config.orientation
    }

    /**
     * Check if the configuration change is drastic enough to warrant recalibration.
     */
    private fun isDrasticChange(newScreenSize: Int, newOrientation: Int): Boolean {
        // If screen size changed between normal and large/xlarge, it's likely a fold/unfold
        val sizeDifference = kotlin.math.abs(newScreenSize - lastScreenSize)
        return sizeDifference > 1
    }

    private fun notifyListeners() {
        listeners.forEach { it.onFoldStateChanged() }
    }

    /**
     * Check if device is likely foldable.
     * This is a heuristic check - not 100% accurate.
     */
    fun isLikelyFoldable(): Boolean {
        // Check for common foldable device indicators
        val config = context.resources.configuration
        val screenSize = config.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK
        
        // Foldable devices often report as LARGE or XLARGE when unfolded
        return screenSize >= Configuration.SCREENLAYOUT_SIZE_LARGE
    }
}
