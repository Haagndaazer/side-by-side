package com.example.sbsconverter.processing

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.example.sbsconverter.util.BitmapUtils
import kotlin.math.ln
import kotlin.math.pow

/**
 * Applies an Ultra HDR gainmap to a bitmap in software, producing an enhanced
 * SDR image. Used when the target display lacks native HDR support (e.g., XREAL
 * glasses) so the compositor would otherwise ignore the gainmap entirely.
 *
 * Implements the ISO 21496-1 gainmap formula in linear light space with
 * Reinhard soft-knee tone-mapping to compress highlights.
 */
object GainmapApplicator {

    private const val TAG = "GainmapApplicator"

    /**
     * Apply the bitmap's gainmap to its pixels, returning a new enhanced SDR bitmap.
     * Returns the original bitmap unchanged if no gainmap is present or API < 34.
     */
    fun applyToSdr(bitmap: Bitmap): Bitmap {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return bitmap
        if (!bitmap.hasGainmap()) return bitmap

        val startTime = System.nanoTime()
        val gainmap = bitmap.gainmap!!
        val result = applyGainmap(bitmap, gainmap)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        Log.d(TAG, "Gainmap applied to SDR in ${elapsedMs}ms (${bitmap.width}x${bitmap.height})")
        return result
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun applyGainmap(bitmap: Bitmap, gainmap: android.graphics.Gainmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // Extract gainmap metadata (per-channel RGB arrays)
        val ratioMin = gainmap.ratioMin
        val ratioMax = gainmap.ratioMax
        val gamma = gainmap.gamma
        val epsilonSdr = gainmap.epsilonSdr
        val epsilonHdr = gainmap.epsilonHdr

        // Weight derived from gainmap spec: W = 1.0 gives full HDR boost.
        // Use displayRatioForFullHdr as the synthetic headroom to match what
        // the phone's compositor would apply at that headroom level.
        val displayRatioForFullHdr = gainmap.displayRatioForFullHdr
        val minDisplayRatio = gainmap.minDisplayRatioForHdrTransition
        val weight = if (displayRatioForFullHdr > minDisplayRatio) {
            ((ln(displayRatioForFullHdr) - ln(minDisplayRatio)) /
                    (ln(displayRatioForFullHdr) - ln(minDisplayRatio))).coerceIn(0f, 1f)
        } else {
            1f
        }
        // The above simplifies to 1.0 when using full headroom. This is intentional:
        // we simulate a display with exactly displayRatioForFullHdr headroom, then
        // rely on Reinhard to compress into SDR range.

        Log.d(TAG, "Gainmap metadata: ratioMin=${ratioMin.contentToString()}, " +
                "ratioMax=${ratioMax.contentToString()}, gamma=${gamma.contentToString()}, " +
                "displayRatioForFullHdr=$displayRatioForFullHdr, weight=$weight")

        // Pre-compute per-channel log ratios
        val logRatioMin = FloatArray(3) { ln(ratioMin[it].coerceAtLeast(1e-6f)) }
        val logRatioMax = FloatArray(3) { ln(ratioMax[it].coerceAtLeast(1e-6f)) }
        val invGamma = FloatArray(3) { 1f / gamma[it].coerceAtLeast(1e-6f) }

        // Read base pixels
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Prepare gainmap pixels — scale to match base dimensions if needed
        var gmBitmap = gainmap.gainmapContents
        if (gmBitmap.config == Bitmap.Config.ALPHA_8) {
            val converted = BitmapUtils.convertAlpha8ToArgb(gmBitmap)
            gmBitmap = converted
        }
        val needsScale = gmBitmap.width != width || gmBitmap.height != height
        val scaledGm = if (needsScale) {
            Bitmap.createScaledBitmap(gmBitmap, width, height, true)
        } else {
            gmBitmap
        }
        val gmPixels = IntArray(width * height)
        scaledGm.getPixels(gmPixels, 0, width, 0, 0, width, height)
        // Clean up intermediates (don't recycle the original gainmap contents)
        if (needsScale) scaledGm.recycle()
        if (gmBitmap !== gainmap.gainmapContents) gmBitmap.recycle()

        // Apply gainmap per pixel in linear light space
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val alpha = (pixel ushr 24) and 0xFF

            // Extract sRGB channels [0..255] → [0..1]
            val sR = ((pixel shr 16) and 0xFF) / 255f
            val sG = ((pixel shr 8) and 0xFF) / 255f
            val sB = (pixel and 0xFF) / 255f

            // Extract gainmap values [0..1]
            val gmR = ((gmPixels[i] shr 16) and 0xFF) / 255f
            val gmG = ((gmPixels[i] shr 8) and 0xFF) / 255f
            val gmB = (gmPixels[i] and 0xFF) / 255f

            // sRGB → linear
            val linR = srgbToLinear(sR)
            val linG = srgbToLinear(sG)
            val linB = srgbToLinear(sB)

            // Apply gainmap formula per channel
            val outR = applyChannel(linR, gmR, invGamma[0], logRatioMin[0], logRatioMax[0],
                epsilonSdr[0], epsilonHdr[0], weight)
            val outG = applyChannel(linG, gmG, invGamma[1], logRatioMin[1], logRatioMax[1],
                epsilonSdr[1], epsilonHdr[1], weight)
            val outB = applyChannel(linB, gmB, invGamma[2], logRatioMin[2], logRatioMax[2],
                epsilonSdr[2], epsilonHdr[2], weight)

            // Linear → sRGB
            val finalR = (linearToSrgb(outR) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val finalG = (linearToSrgb(outG) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val finalB = (linearToSrgb(outB) * 255f + 0.5f).toInt().coerceIn(0, 255)

            pixels[i] = (alpha shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * ISO 21496-1 gainmap formula for a single channel in linear light space:
     *   recovery = pow(gainmapValue, 1/gamma)
     *   logBoost = mix(log(ratioMin), log(ratioMax), recovery)
     *   linearHdr = (linearSdr + epsilonSdr) * exp(logBoost * weight) - epsilonHdr
     *   output = reinhard(linearHdr)
     */
    private fun applyChannel(
        linearSdr: Float,
        gainmapValue: Float,
        invGamma: Float,
        logRatioMin: Float,
        logRatioMax: Float,
        epsilonSdr: Float,
        epsilonHdr: Float,
        weight: Float
    ): Float {
        val recovery = gainmapValue.pow(invGamma)
        val logBoost = logRatioMin + (logRatioMax - logRatioMin) * recovery
        val linearHdr = ((linearSdr + epsilonSdr) * Math.exp((logBoost * weight).toDouble()).toFloat()) - epsilonHdr
        // Reinhard soft-knee: smoothly compresses highlights instead of hard clipping
        val positive = linearHdr.coerceAtLeast(0f)
        return positive / (positive + 1f)
    }

    /** sRGB gamma decode: [0,1] → linear [0,1] */
    private fun srgbToLinear(s: Float): Float {
        return if (s <= 0.04045f) s / 12.92f
        else ((s + 0.055f) / 1.055f).pow(2.4f)
    }

    /** Linear → sRGB gamma encode: linear [0,1] → [0,1] */
    private fun linearToSrgb(l: Float): Float {
        return if (l <= 0.0031308f) l * 12.92f
        else 1.055f * l.pow(1f / 2.4f) - 0.055f
    }
}
