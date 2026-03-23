package com.example.sbsconverter.processing

import kotlin.math.abs
import kotlin.math.exp

class DepthEnhancer {

    /**
     * Bilateral Unsharp Mask — amplifies local depth variation while preserving edges.
     * Extracts detail = original - bilateral_filtered, adds back scaled.
     * Output is clamped to [0,1] to prevent vertex fold artifacts.
     */
    fun bilateralUnsharpMask(
        depth: FloatArray,
        width: Int,
        height: Int,
        spatialSigma: Float = 21.0f,
        depthSigma: Float = 0.04f,
        strength: Float = 1.2f
    ): FloatArray {
        // Half-resolution optimization: downsample → bilateral → upsample.
        // The bilateral produces a smooth version — fine detail lost by downsampling
        // doesn't matter since we subtract it from the full-res original anyway.
        val halfW = width / 2
        val halfH = height / 2
        val halfSigma = spatialSigma / 2f

        val halfDepth = downsample(depth, width, height, halfW, halfH)
        val halfSmoothed = bilateralFilter(halfDepth, halfW, halfH, halfSigma, depthSigma)
        val smoothed = upsample(halfSmoothed, halfW, halfH, width, height)

        val enhanced = FloatArray(depth.size)
        for (i in depth.indices) {
            val detail = depth[i] - smoothed[i]
            enhanced[i] = (depth[i] + strength * detail).coerceIn(0f, 1f)
        }

        return enhanced
    }

    private fun downsample(src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        val dst = FloatArray(dstW * dstH)
        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val sx = x * 2
                val sy = y * 2
                // Average 2x2 block
                var sum = src[sy * srcW + sx]
                var count = 1
                if (sx + 1 < srcW) { sum += src[sy * srcW + sx + 1]; count++ }
                if (sy + 1 < srcH) { sum += src[(sy + 1) * srcW + sx]; count++ }
                if (sx + 1 < srcW && sy + 1 < srcH) { sum += src[(sy + 1) * srcW + sx + 1]; count++ }
                dst[y * dstW + x] = sum / count
            }
        }
        return dst
    }

    private fun upsample(src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        val dst = FloatArray(dstW * dstH)
        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val fx = x.toFloat() / dstW * srcW
                val fy = y.toFloat() / dstH * srcH
                val x0 = fx.toInt().coerceIn(0, srcW - 2)
                val y0 = fy.toInt().coerceIn(0, srcH - 2)
                val dx = fx - x0
                val dy = fy - y0
                dst[y * dstW + x] =
                    src[y0 * srcW + x0] * (1 - dx) * (1 - dy) +
                    src[y0 * srcW + x0 + 1] * dx * (1 - dy) +
                    src[(y0 + 1) * srcW + x0] * (1 - dx) * dy +
                    src[(y0 + 1) * srcW + x0 + 1] * dx * dy
            }
        }
        return dst
    }

    /**
     * Compute gradient-based micro-parallax displacement.
     * Uses central difference for horizontal gradient, with edge mask
     * to suppress gradient at depth discontinuities.
     *
     * @return Per-pixel horizontal gradient scaled by microStrength
     */
    fun computeGradientMicroParallax(
        depth: FloatArray,
        width: Int,
        height: Int,
        microStrength: Float = 2.0f
    ): FloatArray {
        val microParallax = FloatArray(depth.size)

        // First compute raw gradient
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
     * Separable bilateral filter — edge-preserving smoothing.
     * Applies 1D bilateral horizontally then vertically.
     * Not mathematically identical to true 2D bilateral but very close
     * in practice and ~10x faster.
     */
    private fun bilateralFilter(
        depth: FloatArray,
        width: Int,
        height: Int,
        spatialSigma: Float,
        depthSigma: Float
    ): FloatArray {
        val radius = (spatialSigma * 2.0f).toInt().coerceIn(1, 42)
        val spatialDenom = 2.0f * spatialSigma * spatialSigma
        val depthDenom = 2.0f * depthSigma * depthSigma

        // Precompute spatial weights
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
}
