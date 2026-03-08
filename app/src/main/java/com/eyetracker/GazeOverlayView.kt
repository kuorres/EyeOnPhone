package com.eyetracker

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Full-screen transparent overlay that draws a gaze highlight circle.
 * The circle uses a radial gradient for a soft spotlight effect.
 */
class GazeOverlayView(context: Context) : View(context) {

    // Current gaze position in screen-relative [0,1] coords
    private var gazeNormX = 0.5f
    private var gazeNormY = 0.5f

    // Animated radius (for pulse effect when gaze is stable)
    private var currentRadius = 0f
    private var targetRadius  = 0f

    private var overlayColor = 0xCCFF4444.toInt()
    private var highlightRadiusDp = 60
    private var faceVisible = false

    // Fade-in/out opacity animator
    private var opacity = 0f
    private val fadeInAnimator  = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 200
        interpolator = DecelerateInterpolator()
        addUpdateListener { opacity = it.animatedValue as Float; invalidate() }
    }
    private val fadeOutAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = 500
        interpolator = DecelerateInterpolator()
        addUpdateListener { opacity = it.animatedValue as Float; invalidate() }
    }

    // Paints
    private val spotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }

    fun updateGaze(
        normX: Float = gazeNormX,
        normY: Float = gazeNormY,
        radius: Int = highlightRadiusDp,
        color: Int = overlayColor,
        faceFound: Boolean
    ) {
        gazeNormX = normX
        gazeNormY = normY
        overlayColor = color
        highlightRadiusDp = radius

        if (faceFound && !faceVisible) {
            faceVisible = true
            fadeOutAnimator.cancel()
            fadeInAnimator.start()
        } else if (!faceFound && faceVisible) {
            faceVisible = false
            fadeInAnimator.cancel()
            fadeOutAnimator.start()
        }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (opacity <= 0f || width == 0 || height == 0) return

        val cx = gazeNormX * width
        val cy = gazeNormY * height
        val r  = dpToPx(highlightRadiusDp.toFloat())

        // Extract colour components and apply opacity
        val a = (Color.alpha(overlayColor) * opacity).toInt().coerceIn(0, 255)
        val red   = Color.red(overlayColor)
        val green = Color.green(overlayColor)
        val blue  = Color.blue(overlayColor)
        val center = Color.argb(a,   red, green, blue)
        val edge   = Color.argb(0,   red, green, blue)

        // Radial gradient — soft spotlight
        spotPaint.shader = RadialGradient(cx, cy, r, center, edge, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, spotPaint)

        // Crisp ring
        ringPaint.color = Color.argb((a * 0.8f).toInt(), red, green, blue)
        ringPaint.strokeWidth = dpToPx(2f)
        canvas.drawCircle(cx, cy, r * 0.6f, ringPaint)

        // Crosshair dot in centre
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((a * 0.9f).toInt(), red, green, blue)
        }
        canvas.drawCircle(cx, cy, dpToPx(4f), dotPaint)
    }

    private fun dpToPx(dp: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics
    )
}
