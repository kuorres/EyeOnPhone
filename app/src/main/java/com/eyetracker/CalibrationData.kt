package com.eyetracker

import android.graphics.PointF
import kotlin.math.min

/**
 * Represents a single calibration point: where user was asked to look vs. actual eye position.
 */
data class CalibrationPoint(
    val targetScreen: PointF,    // Target position in screen coordinates (normalized 0-1)
    val measuredEye: PointF      // Measured eye position (raw yaw/pitch angles or normalized)
)

/**
 * Stores all calibration data and computes transformation matrix.
 * Uses affine transformation to map raw eye positions to calibrated screen positions.
 */
class CalibrationData {
    
    private val points = mutableListOf<CalibrationPoint>()
    
    // Affine transformation matrix [2x3]
    // [x']   [a  b  c]   [x]
    // [y'] = [d  e  f] * [y]
    //                    [1]
    private var transformMatrix: FloatArray? = null
    
    var isCalibrated: Boolean = false
        private set
    
    /**
     * Add a calibration point.
     */
    fun addPoint(targetScreen: PointF, measuredEye: PointF) {
        points.add(CalibrationPoint(targetScreen, measuredEye))
    }
    
    /**
     * Clear all calibration data.
     */
    fun clear() {
        points.clear()
        transformMatrix = null
        isCalibrated = false
    }
    
    /**
     * Compute transformation matrix using least-squares fitting.
     * Requires at least 3 points for 2D affine transformation.
     */
    fun computeTransformation(): Boolean {
        if (points.size < 3) return false
        
        try {
            // We need to solve for 6 parameters (a,b,c,d,e,f) in affine transformation:
            // x' = a*x + b*y + c
            // y' = d*x + e*y + f
            
            val n = points.size
            
            // Build matrices for least squares: A * params = B
            // For X coordinates
            val matrixAx = Array(n) { i ->
                floatArrayOf(
                    points[i].measuredEye.x,
                    points[i].measuredEye.y,
                    1f
                )
            }
            val vectorBx = FloatArray(n) { i -> points[i].targetScreen.x }
            
            // For Y coordinates
            val matrixAy = Array(n) { i ->
                floatArrayOf(
                    points[i].measuredEye.x,
                    points[i].measuredEye.y,
                    1f
                )
            }
            val vectorBy = FloatArray(n) { i -> points[i].targetScreen.y }
            
            // Solve using pseudo-inverse (simplified least squares for overdetermined system)
            val paramsX = solveLeastSquares(matrixAx, vectorBx)
            val paramsY = solveLeastSquares(matrixAy, vectorBy)
            
            if (paramsX != null && paramsY != null) {
                transformMatrix = floatArrayOf(
                    paramsX[0], paramsX[1], paramsX[2],  // a, b, c
                    paramsY[0], paramsY[1], paramsY[2]   // d, e, f
                )
                isCalibrated = true
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return false
    }
    
    /**
     * Apply calibration transformation to raw eye position.
     * Returns calibrated screen position (normalized 0-1).
     */
    fun applyTransformation(rawEye: PointF): PointF {
        val matrix = transformMatrix ?: return rawEye
        
        val x = rawEye.x
        val y = rawEye.y
        
        val calibratedX = matrix[0] * x + matrix[1] * y + matrix[2]
        val calibratedY = matrix[3] * x + matrix[4] * y + matrix[5]
        
        // Clamp to [0, 1] range
        return PointF(
            calibratedX.coerceIn(0f, 1f),
            calibratedY.coerceIn(0f, 1f)
        )
    }
    
    /**
     * Simple least squares solver for overdetermined system A*x = b.
     * Uses normal equations: (A^T * A) * x = A^T * b
     */
    private fun solveLeastSquares(A: Array<FloatArray>, b: FloatArray): FloatArray? {
        val m = A.size       // number of equations
        val n = A[0].size    // number of unknowns (should be 3)
        
        if (m < n) return null
        
        // Compute A^T * A
        val AtA = Array(n) { FloatArray(n) }
        for (i in 0 until n) {
            for (j in 0 until n) {
                var sum = 0f
                for (k in 0 until m) {
                    sum += A[k][i] * A[k][j]
                }
                AtA[i][j] = sum
            }
        }
        
        // Compute A^T * b
        val Atb = FloatArray(n)
        for (i in 0 until n) {
            var sum = 0f
            for (k in 0 until m) {
                sum += A[k][i] * b[k]
            }
            Atb[i] = sum
        }
        
        // Solve AtA * x = Atb using Gaussian elimination
        return solveLinearSystem(AtA, Atb)
    }
    
    /**
     * Solve linear system using Gaussian elimination with partial pivoting.
     */
    private fun solveLinearSystem(A: Array<FloatArray>, b: FloatArray): FloatArray? {
        val n = A.size
        val augmented = Array(n) { i -> A[i].copyOf(n + 1) }
        
        // Augment matrix with b
        for (i in 0 until n) {
            augmented[i][n] = b[i]
        }
        
        // Forward elimination with partial pivoting
        for (col in 0 until n) {
            // Find pivot
            var maxRow = col
            for (row in col + 1 until n) {
                if (kotlin.math.abs(augmented[row][col]) > kotlin.math.abs(augmented[maxRow][col])) {
                    maxRow = row
                }
            }
            
            // Swap rows
            val temp = augmented[col]
            augmented[col] = augmented[maxRow]
            augmented[maxRow] = temp
            
            // Check for singular matrix
            if (kotlin.math.abs(augmented[col][col]) < 1e-10f) {
                return null
            }
            
            // Eliminate column
            for (row in col + 1 until n) {
                val factor = augmented[row][col] / augmented[col][col]
                for (j in col until n + 1) {
                    augmented[row][j] -= factor * augmented[col][j]
                }
            }
        }
        
        // Back substitution
        val x = FloatArray(n)
        for (i in n - 1 downTo 0) {
            var sum = augmented[i][n]
            for (j in i + 1 until n) {
                sum -= augmented[i][j] * x[j]
            }
            x[i] = sum / augmented[i][i]
        }
        
        return x
    }
    
    /**
     * Get calibration quality score (0-100).
     * Based on residual error between predicted and actual target positions.
     */
    fun getCalibrationQuality(): Int {
        if (!isCalibrated || points.isEmpty()) return 0
        
        var totalError = 0f
        for (point in points) {
            val predicted = applyTransformation(point.measuredEye)
            val dx = predicted.x - point.targetScreen.x
            val dy = predicted.y - point.targetScreen.y
            val error = kotlin.math.sqrt(dx * dx + dy * dy)
            totalError += error
        }
        
        val avgError = totalError / points.size
        // Error of 0 = 100%, error of 0.5 (half screen) = 0%
        val quality = ((1f - min(avgError * 2f, 1f)) * 100f).toInt()
        
        return quality.coerceIn(0, 100)
    }
    
    /**
     * Get number of calibration points collected.
     */
    fun getPointCount(): Int = points.size
}
