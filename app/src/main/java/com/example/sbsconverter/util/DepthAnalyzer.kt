package com.example.sbsconverter.util

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

data class AutoParams(
    val convergencePoint: Float,
    val depthScale: Float,
    val depthBlurKernel: Int,
    val depthGamma: Float
)

object DepthAnalyzer {

    /**
     * Analyzes a normalized [0,1] depth map and returns recommended processing parameters.
     * Should be called on the raw normalized depth (before histogram equalization or gamma).
     */
    fun analyze(
        depth: FloatArray,
        width: Int = 756,
        height: Int = 756
    ): AutoParams {
        val n = depth.size

        // Basic statistics
        var sum = 0.0
        for (v in depth) sum += v
        val mean = (sum / n).toFloat()

        var varSum = 0.0
        for (v in depth) {
            val d = v - mean
            varSum += d * d
        }
        val stddev = sqrt(varSum / n).toFloat()

        val sorted = depth.copyOf().also { it.sort() }
        val median = sorted[n / 2]
        val iqr = sorted[(n * 0.75).toInt()] - sorted[(n * 0.25).toInt()]

        // Edge density via Sobel gradient magnitude
        val edgeDensity = computeEdgeDensity(depth, width, height)

        // Noise estimation via Laplacian
        val noiseLevel = computeNoiseLevel(depth, width, height)

        // Auto-convergence: center-weighted depth mode
        val convergence = computeCenterWeightedConvergence(depth, width, height)

        // Auto-disparity scale
        val baseScale = 5.0f
        val spreadFactor = (0.289f / stddev.coerceIn(0.05f, 0.5f)).coerceIn(0.6f, 1.8f)
        val edgePenalty = 1.0f - (edgeDensity * 2.0f).coerceIn(0f, 0.3f)
        val depthScale = (baseScale * spreadFactor * edgePenalty).coerceIn(2.0f, 10.0f)

        // Auto-blur kernel
        val noiseKernel = when {
            noiseLevel < 0.008f -> 1
            noiseLevel < 0.015f -> 3
            noiseLevel < 0.035f -> 5
            noiseLevel < 0.055f -> 7
            else -> 9
        }
        val maxKernelForEdges = if (edgeDensity > 0.10f) 5 else 9
        val blurKernel = minOf(noiseKernel, maxKernelForEdges).let {
            if (it % 2 == 0) it + 1 else it
        }

        // Auto-gamma: push median toward 0.5 for balanced depth distribution
        val gamma = if (median in 0.1f..0.9f) {
            (ln(0.5) / ln(median.toDouble())).toFloat().coerceIn(0.5f, 2.0f)
        } else 1.0f

        return AutoParams(
            convergencePoint = convergence,
            depthScale = depthScale,
            depthBlurKernel = blurKernel,
            depthGamma = gamma
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

    private fun computeNoiseLevel(depth: FloatArray, width: Int, height: Int): Float {
        var lapSum = 0.0
        var count = 0

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val lap = depth[idx - 1] + depth[idx + 1] +
                        depth[idx - width] + depth[idx + width] -
                        4 * depth[idx]
                lapSum += abs(lap)
                count++
            }
        }

        if (count == 0) return 0f
        return ((lapSum / count) * sqrt(Math.PI / 2.0) / (2.0 * sqrt(2.0))).toFloat()
    }
}
