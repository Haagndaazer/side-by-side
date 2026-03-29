package com.example.sbsconverter.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlin.math.sqrt

/**
 * Calibration result with suggested depthScale and display metadata.
 */
data class CalibrationInfo(
    val depthScale: Float,
    val focusDistanceM: Float,
    val focalLength35mm: Int,
    val lens: String,
    val sceneType: String
)

/**
 * Computes a suggested depthScale value from EXIF focus distance and focal length.
 * Maps into the existing disparity formula: maxDisparity = depthScale / 100 * width / 2 * rawRange
 *
 * Returns null when EXIF data is insufficient — caller should use the default depthScale.
 */
object ExifDepthCalibrator {

    private const val TAG = "ExifDepthCalibrator"

    // Base depthScale at 1m distance. Tuned so sqrt(distance) scaling
    // produces natural-looking results across the range.
    private const val BASE_SCALE = 0.1f

    /**
     * Reads EXIF and computes calibration info including suggested depthScale.
     * Returns null if EXIF data is insufficient.
     */
    fun calibrate(
        context: Context,
        uri: Uri,
        rawRange: Float,
        imageWidth: Int
    ): CalibrationInfo? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                computeFromExif(exif, rawRange, imageWidth)
            }
        } catch (e: Exception) {
            Log.w(TAG, "EXIF read failed: ${e.message}")
            null
        }
    }

    private fun computeFromExif(
        exif: ExifInterface,
        @Suppress("UNUSED_PARAMETER") rawRange: Float,
        @Suppress("UNUSED_PARAMETER") imageWidth: Int
    ): CalibrationInfo? {
        // Read subject distance (meters) — Pixel 10 writes this from PDAF autofocus
        val distanceMeters = exif.getAttributeDouble(ExifInterface.TAG_SUBJECT_DISTANCE, -1.0)
        if (distanceMeters <= 0.0 || distanceMeters > 100.0) {
            Log.d(TAG, "No usable focus distance (value=$distanceMeters)")
            return null
        }

        val focalLength35mm = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, -1)
        if (focalLength35mm <= 0) {
            Log.d(TAG, "No 35mm equiv focal length, skipping")
            return null
        }

        // Perceptual scaling (opposite of real stereo physics):
        // - Close subjects: depth map has subtle gradients, warping artifacts visible → less 3D
        // - Far subjects: rich depth variation to emphasize → more 3D
        // sqrt gives a gentle positive curve: 1m→1.0, 4m→2.0, 9m→3.0, 25m→5.0
        val distanceFactor = sqrt(distanceMeters.toFloat()).coerceIn(0.3f, 5.0f)

        // Telephoto compresses depth → less 3D needed. Wide expands it → more.
        // Normalized to main lens (24mm equiv = factor 1.0)
        val focalFactor = (24f / focalLength35mm.toFloat()).coerceIn(0.3f, 1.5f)

        val depthScale = (BASE_SCALE * distanceFactor * focalFactor).coerceIn(0.1f, 0.4f)

        val lens = classifyLens(focalLength35mm)
        val sceneType = classifyScene(distanceMeters.toFloat(), focalLength35mm)

        Log.d(TAG, "Auto-calibration: distance=${distanceMeters}m, " +
                "focal35mm=${focalLength35mm}mm, lens=$lens, scene=$sceneType, " +
                "distFactor=$distanceFactor, focalFactor=$focalFactor, " +
                "depthScale=$depthScale")

        return CalibrationInfo(
            depthScale = depthScale,
            focusDistanceM = distanceMeters.toFloat(),
            focalLength35mm = focalLength35mm,
            lens = lens,
            sceneType = sceneType
        )
    }

    /**
     * Lightweight EXIF reader for display metadata only (no depthScale computation).
     * Used by batch items to show metadata chips before processing starts.
     */
    fun readMetadata(context: Context, uri: Uri): CalibrationInfo? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                computeFromExif(exif, 0f, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "EXIF metadata read failed: ${e.message}")
            null
        }
    }

    private fun classifyLens(focalLength35mm: Int): String = when {
        focalLength35mm <= 16 -> "Ultrawide"
        focalLength35mm <= 40 -> "Wide"
        focalLength35mm <= 70 -> "Standard"
        else -> "Telephoto"
    }

    private fun classifyScene(distanceM: Float, focalLength35mm: Int): String = when {
        distanceM < 0.3f -> "Macro"
        distanceM < 0.8f && focalLength35mm <= 40 -> "Close-up"
        distanceM < 2.0f -> "Portrait"
        distanceM < 5.0f -> "Mid-range"
        distanceM < 15.0f -> "Group / Room"
        else -> "Landscape"
    }
}
