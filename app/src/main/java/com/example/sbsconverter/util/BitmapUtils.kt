package com.example.sbsconverter.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.nio.ByteBuffer
import kotlin.math.pow
import java.nio.ByteOrder
import java.nio.FloatBuffer

object BitmapUtils {

    private const val MODEL_INPUT_SIZE = 770
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    fun bitmapToFloatTensor(bitmap: Bitmap): FloatBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true)
        val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        resized.getPixels(pixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)
        if (resized !== bitmap) resized.recycle()

        val tensorSize = 1 * 3 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE
        val buffer = ByteBuffer.allocateDirect(tensorSize * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val channelSize = MODEL_INPUT_SIZE * MODEL_INPUT_SIZE

        // NCHW format: all R values, then all G values, then all B values
        for (c in 0 until 3) {
            val mean = MEAN[c]
            val std = STD[c]
            val shift = when (c) {
                0 -> 16 // R
                1 -> 8  // G
                2 -> 0  // B
                else -> 0
            }
            for (i in 0 until channelSize) {
                val pixelValue = (pixels[i] shr shift) and 0xFF
                buffer.put((pixelValue / 255.0f - mean) / std)
            }
        }

        buffer.rewind()
        return buffer
    }

    fun depthToGrayscaleBitmap(
        depthValues: FloatArray,
        outputWidth: Int,
        outputHeight: Int
    ): Bitmap {
        var min = Float.MAX_VALUE
        var max = Float.MIN_VALUE
        for (v in depthValues) {
            if (v < min) min = v
            if (v > max) max = v
        }

        val range = max - min
        val pixels = IntArray(depthValues.size)
        for (i in depthValues.indices) {
            val normalized = if (range > 0f) ((depthValues[i] - min) / range * 255f).toInt().coerceIn(0, 255) else 128
            pixels[i] = (0xFF shl 24) or (normalized shl 16) or (normalized shl 8) or normalized
        }

        val depthBitmap = Bitmap.createBitmap(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, Bitmap.Config.ARGB_8888)
        depthBitmap.setPixels(pixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)

        // Cap output size to avoid OOM with huge source images
        val targetW = outputWidth.coerceAtMost(MAX_IMAGE_DIMENSION)
        val targetH = outputHeight.coerceAtMost(MAX_IMAGE_DIMENSION)

        if (targetW == MODEL_INPUT_SIZE && targetH == MODEL_INPUT_SIZE) {
            return depthBitmap
        }
        val scaled = Bitmap.createScaledBitmap(depthBitmap, targetW, targetH, true)
        depthBitmap.recycle()
        return scaled
    }

    /**
     * Max pixel dimension for loaded images. Keeps memory usage reasonable
     * (~2048x2048x4 = 16MB) while preserving enough quality for SBS output.
     */
    private const val MAX_IMAGE_DIMENSION = 2048

    fun loadBitmapFromUri(context: Context, uri: Uri, maxDimension: Int = MAX_IMAGE_DIMENSION): Bitmap? {
        // First pass: decode bounds only to calculate sample size
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOptions)
        }
        val rawWidth = boundsOptions.outWidth
        val rawHeight = boundsOptions.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) return null

        // Calculate power-of-2 sample size for efficient decode
        var sampleSize = 1
        while (rawWidth / (sampleSize * 2) >= maxDimension ||
            rawHeight / (sampleSize * 2) >= maxDimension
        ) {
            sampleSize *= 2
        }

        // Second pass: decode with subsampling
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: return null

        // Fine-scale if still exceeds max (inSampleSize is coarse, power-of-2 only)
        val bitmap = if (decoded.width > maxDimension || decoded.height > maxDimension) {
            val scale = maxDimension.toFloat() / maxOf(decoded.width, decoded.height)
            val newW = (decoded.width * scale).toInt()
            val newH = (decoded.height * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(decoded, newW, newH, true)
            if (scaled !== decoded) decoded.recycle()
            scaled
        } else {
            decoded
        }

        // Handle EXIF rotation
        val rotation = try {
            context.contentResolver.openInputStream(uri)?.use { exifStream ->
                val exif = ExifInterface(exifStream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) {
            0f
        }

        if (rotation == 0f) return bitmap

        val matrix = Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    fun normalizeDepthMap(rawDepth: FloatArray): FloatArray {
        var min = Float.MAX_VALUE
        var max = Float.MIN_VALUE
        for (v in rawDepth) {
            if (v < min) min = v
            if (v > max) max = v
        }
        val range = max - min
        val result = FloatArray(rawDepth.size)
        if (range > 0f) {
            for (i in rawDepth.indices) {
                result[i] = ((rawDepth[i] - min) / range).coerceIn(0f, 1f)
            }
        } else {
            result.fill(0.5f)
        }
        return result
    }

    /**
     * Histogram equalization — redistributes depth values so the full [0,1] range
     * is utilized. Prevents most of the disparity budget from being wasted on
     * clustered depth values (e.g., a person's body at similar depth).
     */
    fun equalizeDepthHistogram(depth: FloatArray, numBins: Int = 1024): FloatArray {
        val histogram = IntArray(numBins)
        for (v in depth) {
            val bin = (v * (numBins - 1)).toInt().coerceIn(0, numBins - 1)
            histogram[bin]++
        }
        // Build CDF
        val cdf = FloatArray(numBins)
        cdf[0] = histogram[0].toFloat()
        for (i in 1 until numBins) {
            cdf[i] = cdf[i - 1] + histogram[i]
        }
        val cdfMin = cdf.first { it > 0f }
        val total = depth.size.toFloat()
        val denominator = total - cdfMin
        if (denominator <= 0f) return depth.copyOf()

        return FloatArray(depth.size) { i ->
            val bin = (depth[i] * (numBins - 1)).toInt().coerceIn(0, numBins - 1)
            ((cdf[bin] - cdfMin) / denominator).coerceIn(0f, 1f)
        }
    }

    /**
     * Power curve remapping — gamma < 1 expands mid-range detail (more 3D pop),
     * gamma > 1 compresses it. Applied after histogram equalization.
     */
    fun remapDepthGamma(depth: FloatArray, gamma: Float): FloatArray {
        if (gamma == 1f) return depth.copyOf()
        val g = gamma.toDouble()
        return FloatArray(depth.size) { i ->
            depth[i].toDouble().pow(g).toFloat()
        }
    }

    fun blurDepthMap(
        depth: FloatArray,
        width: Int,
        height: Int,
        kernelSize: Int
    ): FloatArray {
        if (kernelSize <= 1) return depth.copyOf()
        val k = if (kernelSize % 2 == 0) kernelSize + 1 else kernelSize
        val half = k / 2
        var current = depth.copyOf()
        var buffer = FloatArray(current.size)

        // 3 passes of separable box blur
        repeat(3) {
            // Horizontal pass
            for (y in 0 until height) {
                val rowOffset = y * width
                var sum = 0f
                // Initialize running sum for first pixel
                for (dx in -half..half) {
                    sum += current[rowOffset + dx.coerceIn(0, width - 1)]
                }
                buffer[rowOffset] = sum / k

                for (x in 1 until width) {
                    val addIdx = (x + half).coerceIn(0, width - 1)
                    val removeIdx = (x - half - 1).coerceIn(0, width - 1)
                    sum += current[rowOffset + addIdx] - current[rowOffset + removeIdx]
                    buffer[rowOffset + x] = sum / k
                }
            }

            // Swap
            val temp = current
            current = buffer
            buffer = temp

            // Vertical pass
            for (x in 0 until width) {
                var sum = 0f
                for (dy in -half..half) {
                    sum += current[dy.coerceIn(0, height - 1) * width + x]
                }
                buffer[x] = sum / k

                for (y in 1 until height) {
                    val addIdx = (y + half).coerceIn(0, height - 1)
                    val removeIdx = (y - half - 1).coerceIn(0, height - 1)
                    sum += current[addIdx * width + x] - current[removeIdx * width + x]
                    buffer[y * width + x] = sum / k
                }
            }

            val temp2 = current
            current = buffer
            buffer = temp2
        }

        return current
    }

    fun loadThumbnail(context: Context, uri: Uri, maxSize: Int = 128): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                context.contentResolver.loadThumbnail(uri, android.util.Size(maxSize, maxSize), null)
            } catch (_: Exception) { null }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val sampleSize = maxOf(bounds.outWidth, bounds.outHeight) / maxSize
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    fun loadBitmapFromAsset(context: Context, assetPath: String): Bitmap? {
        return try {
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }
}
