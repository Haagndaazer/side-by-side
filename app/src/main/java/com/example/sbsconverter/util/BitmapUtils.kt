package com.example.sbsconverter.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object BitmapUtils {

    private const val MODEL_INPUT_SIZE = 518
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

        if (outputWidth == MODEL_INPUT_SIZE && outputHeight == MODEL_INPUT_SIZE) {
            return depthBitmap
        }
        val scaled = Bitmap.createScaledBitmap(depthBitmap, outputWidth, outputHeight, true)
        depthBitmap.recycle()
        return scaled
    }

    fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()
        if (bitmap == null) return null

        // Handle EXIF rotation
        val rotation = try {
            val exifStream = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif = ExifInterface(exifStream)
            exifStream.close()
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) {
            0f
        }

        if (rotation == 0f) return bitmap

        val matrix = Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    fun loadBitmapFromAsset(context: Context, assetPath: String): Bitmap? {
        return try {
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }
}
