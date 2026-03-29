package com.example.sbsconverter.processing

import android.os.Build
import android.util.Log
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.exp

class DepthEnhancer : Closeable {

    private var gpuFilter: GpuBilateralFilter? = null
    private var gpuInitialized = false

    /**
     * Bilateral Unsharp Mask — amplifies local depth variation while preserving edges.
     * Extracts detail = original - bilateral_filtered, adds back scaled.
     * Output is clamped to [0,1] to prevent vertex fold artifacts.
     */
    /**
     * Bilateral Unsharp Mask — extracts micro-detail from the raw depth map
     * and applies it to the processed depth map.
     *
     * Running the bilateral on raw (un-normalized) depth preserves the full
     * dynamic range of the model's output, capturing surface detail that
     * normalization + histogram equalization would flatten.
     *
     * @param rawDepth       Raw depth from the model (arbitrary range, float32)
     * @param processedDepth Processed depth (normalized/equalized/gamma, [0,1])
     * @param width          Depth map width
     * @param height         Depth map height
     * @param strength       Enhancement strength (0-3)
     */
    fun bilateralUnsharpMask(
        rawDepth: FloatArray,
        processedDepth: FloatArray,
        width: Int,
        height: Int,
        spatialSigma: Float = 21.0f,
        strength: Float = 1.2f
    ): FloatArray {
        // Normalize raw depth to [0,1] for the bilateral filter's range kernel
        // but keep the full precision of the raw values
        var rawMin = Float.MAX_VALUE
        var rawMax = Float.MIN_VALUE
        for (v in rawDepth) {
            if (v < rawMin) rawMin = v
            if (v > rawMax) rawMax = v
        }
        val rawRange = rawMax - rawMin
        val normalizedRaw = if (rawRange > 0f) {
            FloatArray(rawDepth.size) { ((rawDepth[it] - rawMin) / rawRange).coerceIn(0f, 1f) }
        } else rawDepth

        // Adaptive depthSigma based on depth distribution spread
        // Long-distance scenes (low stddev): smaller sigma → finer sensitivity to subtle detail
        // Close-up scenes (high stddev): larger sigma → cleaner edge preservation
        var varSum = 0.0
        val mean = normalizedRaw.average().toFloat()
        for (v in normalizedRaw) { val d = v - mean; varSum += d * d }
        val stddev = kotlin.math.sqrt(varSum / normalizedRaw.size).toFloat()
        val depthSigma = (0.02f + 0.04f * stddev).coerceIn(0.01f, 0.08f)

        val smoothed = bilateralFilter(normalizedRaw, width, height, spatialSigma, depthSigma)

        // Extract detail from the raw (normalized) depth and apply to processed depth
        val enhanced = FloatArray(processedDepth.size)
        for (i in processedDepth.indices) {
            val detail = normalizedRaw[i] - smoothed[i]
            enhanced[i] = (processedDepth[i] + strength * detail).coerceIn(0f, 1f)
        }

        return enhanced
    }

    /**
     * Compute gradient-based micro-parallax displacement.
     * Uses central difference for horizontal gradient, with edge mask
     * to suppress gradient at depth discontinuities.
     */
    fun computeGradientMicroParallax(
        depth: FloatArray,
        width: Int,
        height: Int,
        microStrength: Float = 2.0f
    ): FloatArray {
        val microParallax = FloatArray(depth.size)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val dzdx: Float = when {
                    x == 0 -> depth[idx + 1] - depth[idx]
                    x == width - 1 -> depth[idx] - depth[idx - 1]
                    else -> (depth[idx + 1] - depth[idx - 1]) / 2.0f
                }
                microParallax[idx] = dzdx * microStrength
            }
        }

        // Edge mask: suppress gradient at sharp depth discontinuities
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val maxNeighborDiff = maxOf(
                    abs(depth[idx] - depth[idx - 1]),
                    abs(depth[idx] - depth[idx + 1]),
                    abs(depth[idx] - depth[idx - width]),
                    abs(depth[idx] - depth[idx + width])
                )
                if (maxNeighborDiff > 0.06f) {
                    microParallax[idx] = 0f
                }
            }
        }

        return microParallax
    }

    /**
     * Bilateral filter — dispatches to GPU (API 33+) or CPU fallback.
     */
    private fun bilateralFilter(
        depth: FloatArray,
        width: Int,
        height: Int,
        spatialSigma: Float,
        depthSigma: Float
    ): FloatArray {
        // Try GPU path on API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val gpu = getOrCreateGpuFilter(width, height)
            if (gpu != null && gpu.gpuAvailable) {
                return try {
                    gpu.filter(depth, width, height, spatialSigma, depthSigma)
                } catch (e: Exception) {
                    Log.w("DepthEnhancer", "GPU bilateral failed, falling back to CPU: ${e.message}")
                    bilateralFilterCpu(depth, width, height, spatialSigma, depthSigma)
                }
            }
        }

        return bilateralFilterCpu(depth, width, height, spatialSigma, depthSigma)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun getOrCreateGpuFilter(width: Int, height: Int): GpuBilateralFilter? {
        if (!gpuInitialized) {
            gpuInitialized = true
            gpuFilter = try {
                GpuBilateralFilter()
            } catch (e: Exception) {
                Log.w("DepthEnhancer", "Failed to create GPU filter: ${e.message}")
                null
            }
        }
        return gpuFilter
    }

    /**
     * CPU bilateral filter — separable approximation (horizontal + vertical).
     */
    private fun bilateralFilterCpu(
        depth: FloatArray,
        width: Int,
        height: Int,
        spatialSigma: Float,
        depthSigma: Float
    ): FloatArray {
        val radius = (spatialSigma * 2.0f).toInt().coerceIn(1, 42)
        val spatialDenom = 2.0f * spatialSigma * spatialSigma
        val depthDenom = 2.0f * depthSigma * depthSigma

        val spatialWeights = FloatArray(radius + 1) { k ->
            exp(-(k * k).toFloat() / spatialDenom)
        }

        // Pass 1: Horizontal
        val horizontal = FloatArray(depth.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val centerDepth = depth[idx]
                var weightSum = 0.0f
                var valueSum = 0.0f

                for (k in -radius..radius) {
                    val nx = x + k
                    if (nx < 0 || nx >= width) continue

                    val neighborDepth = depth[y * width + nx]
                    val depthDiff = centerDepth - neighborDepth
                    val w = spatialWeights[abs(k)] * exp(-(depthDiff * depthDiff) / depthDenom)

                    valueSum += w * neighborDepth
                    weightSum += w
                }

                horizontal[idx] = if (weightSum > 0f) valueSum / weightSum else centerDepth
            }
        }

        // Pass 2: Vertical
        val result = FloatArray(depth.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val centerDepth = horizontal[idx]
                var weightSum = 0.0f
                var valueSum = 0.0f

                for (k in -radius..radius) {
                    val ny = y + k
                    if (ny < 0 || ny >= height) continue

                    val neighborDepth = horizontal[ny * width + x]
                    val depthDiff = centerDepth - neighborDepth
                    val w = spatialWeights[abs(k)] * exp(-(depthDiff * depthDiff) / depthDenom)

                    valueSum += w * neighborDepth
                    weightSum += w
                }

                result[idx] = if (weightSum > 0f) valueSum / weightSum else centerDepth
            }
        }

        return result
    }

    override fun close() {
        gpuFilter?.close()
        gpuFilter = null
    }
}
