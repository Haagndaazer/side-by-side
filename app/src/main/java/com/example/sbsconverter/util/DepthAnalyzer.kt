package com.example.sbsconverter.util

import kotlin.math.exp
import kotlin.math.sqrt

data class AutoParams(
    val convergencePoint: Float,
    val depthScale: Float
)

object DepthAnalyzer {

    /**
     * Analyzes a normalized [0,1] depth map and returns recommended
     * convergence point and 3D strength. Should be called on the raw
     * normalized depth (before histogram equalization or gamma).
     */
    fun analyze(
        depth: FloatArray,
        width: Int = 756,
        height: Int = 756
    ): AutoParams {
        val n = depth.size

        // Standard deviation — indicates depth spread
        var sum = 0.0
        for (v in depth) sum += v
        val mean = (sum / n).toFloat()

        var varSum = 0.0
        for (v in depth) {
            val d = v - mean
            varSum += d * d
        }
        val stddev = sqrt(varSum / n).toFloat()

        // Auto-convergence: center-weighted depth mode
        val convergence = computeCenterWeightedConvergence(depth, width, height)

        // Auto-disparity scale based on depth spread
        // stddev of uniform[0,1] is ~0.289 — use as reference
        val baseScale = 5.0f
        val spreadFactor = (0.289f / stddev.coerceIn(0.05f, 0.5f)).coerceIn(0.6f, 1.8f)
        val edgeDensity = computeEdgeDensity(depth, width, height)
        val edgePenalty = 1.0f - (edgeDensity * 2.0f).coerceIn(0f, 0.3f)
        val depthScale = (baseScale * spreadFactor * edgePenalty).coerceIn(2.0f, 10.0f)

        return AutoParams(
            convergencePoint = convergence,
            depthScale = depthScale
        )
    }

    private fun computeCenterWeightedConvergence(
        depth: FloatArray,
        width: Int,
        height: Int
    ): Float {
        val bins = 64
        val histogram = FloatArray(bins)
        val cx = width / 2f
        val cy = height / 2f

        for (y in 0 until height) {
            for (x in 0 until width) {
                val d = depth[y * width + x]
                val bin = (d * (bins - 1)).toInt().coerceIn(0, bins - 1)
                val dx = (x - cx) / cx
                val dy = (y - cy) / cy
                val weight = exp(-(dx * dx + dy * dy) / 0.5f)
                histogram[bin] += weight
            }
        }

        val peakBin = histogram.indices.maxByOrNull { histogram[it] } ?: bins / 2
        return ((peakBin + 0.5f) / bins).coerceIn(0f, 1f)
    }

    private fun computeEdgeDensity(depth: FloatArray, width: Int, height: Int): Float {
        var magSum = 0.0
        var magSqSum = 0.0
        var count = 0
        val magnitudes = FloatArray((width - 2) * (height - 2))

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val gx = depth[idx - width + 1] + 2 * depth[idx + 1] + depth[idx + width + 1] -
                        depth[idx - width - 1] - 2 * depth[idx - 1] - depth[idx + width - 1]
                val gy = depth[idx + width - 1] + 2 * depth[idx + width] + depth[idx + width + 1] -
                        depth[idx - width - 1] - 2 * depth[idx - width] - depth[idx - width + 1]
                val mag = sqrt(gx * gx + gy * gy)
                magnitudes[count] = mag
                magSum += mag
                magSqSum += mag * mag
                count++
            }
        }

        if (count == 0) return 0f
        val meanMag = (magSum / count).toFloat()
        val stdMag = sqrt((magSqSum / count - meanMag * meanMag).coerceAtLeast(0.0)).toFloat()
        val threshold = meanMag + 1.5f * stdMag

        var edgeCount = 0
        for (i in 0 until count) {
            if (magnitudes[i] > threshold) edgeCount++
        }
        return edgeCount.toFloat() / count
    }
}
