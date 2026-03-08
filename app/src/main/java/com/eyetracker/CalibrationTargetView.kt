package com.eyetracker

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Custom view that draws calibration target points.
 * Shows a pulsing circle at the current calibration point.
 */
class CalibrationTargetView(context: Context) : View(context) {

    private var currentTargetIndex = 0
    private val calibrationPoints = mutableListOf<PointF>()
    
    // Animation
    private var pulseScale = 1f
    private val pulseAnimator = ValueAnimator.ofFloat(1f, 1.5f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { 
            pulseScale = it.animatedValue as Float
            invalidate()
        }
    }
    
    // Paints
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.FILL
    }
    
    private val targetRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#81C784")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    
    private val inactivePointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")
        style = Paint.Style.FILL
    }
    
    private val completedPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1976D2")
        style = Paint.Style.FILL
    }
    
    /**
     * Initialize calibration points in a 3x3 grid.
     * Points are positioned with safe margins from screen edges.
     */
    fun initializePoints() {
        calibrationPoints.clear()
        
        // 3x3 grid with margins
        val marginX = 0.15f  // 15% margin from edges
        val marginY = 0.20f  // 20% margin from edges (more on top/bottom)
        
        val gridX = floatArrayOf(marginX, 0.5f, 1f - marginX)
        val gridY = floatArrayOf(marginY, 0.5f, 1f - marginY)
        
        // Generate points in reading order: top-left, top-center, top-right, etc.
        for (row in gridY) {
            for (col in gridX) {
                calibrationPoints.add(PointF(col, row))
            }
        }
        
        currentTargetIndex = 0
    }
    
    /**
     * Get current calibration target position (normalized 0-1).
     */
    fun getCurrentTarget(): PointF? {
        return if (currentTargetIndex < calibrationPoints.size) {
            calibrationPoints[currentTargetIndex]
        } else {
            null
        }
    }
    
    /**
     * Move to next calibration point.
     * Returns true if there are more points, false if calibration is complete.
     */
    fun nextPoint(): Boolean {
        currentTargetIndex++
        invalidate()
        return currentTargetIndex < calibrationPoints.size
    }
    
    /**
     * Get current point index (0-based).
     */
    fun getCurrentIndex(): Int = currentTargetIndex
    
    /**
     * Get total number of calibration points.
     */
    fun getTotalPoints(): Int = calibrationPoints.size
    
    /**
     * Start pulsing animation.
     */
    fun startAnimation() {
        pulseAnimator.start()
    }
    
    /**
     * Stop pulsing animation.
     */
    fun stopAnimation() {
        pulseAnimator.cancel()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (width == 0 || height == 0) return
        
        // Draw all calibration points
        calibrationPoints.forEachIndexed { index, point ->
            val x = point.x * width
            val y = point.y * height
            
            when {
                index < currentTargetIndex -> {
                    // Completed points - blue
                    canvas.drawCircle(x, y, 12f, completedPointPaint)
                }
                index == currentTargetIndex -> {
                    // Active target - pulsing green circle
                    val baseRadius = 40f
                    val pulsingRadius = baseRadius * pulseScale
                    
                    // Outer pulsing ring
                    targetRingPaint.alpha = ((1.5f - pulseScale) * 255).toInt().coerceIn(0, 255)
                    canvas.drawCircle(x, y, pulsingRadius, targetRingPaint)
                    
                    // Inner solid circle
                    canvas.drawCircle(x, y, baseRadius * 0.6f, targetPaint)
                    
                    // Center dot
                    val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                    }
                    canvas.drawCircle(x, y, 8f, centerDotPaint)
                }
                else -> {
                    // Inactive points - gray
                    canvas.drawCircle(x, y, 8f, inactivePointPaint)
                }
            }
        }
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}
