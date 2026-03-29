package com.example.sbsconverter.processing

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.example.sbsconverter.model.ProcessingConfig
import com.example.sbsconverter.model.SbsResult
import com.example.sbsconverter.util.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImageProcessor {

    companion object {
        private const val TAG = "ImageProcessor"
    }

    private val warper = SbsWarper()
    private val bilateralFilter: GpuBilateralFilter? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val filter = GpuBilateralFilter()
            if (filter.gpuAvailable) filter else { filter.close(); null }
        } else null
    }

    suspend fun processImage(
        sourceBitmap: Bitmap,
        rawDepthMap: FloatArray,
        config: ProcessingConfig
    ): SbsResult = withContext(Dispatchers.Default) {
        val depthSize = DepthEstimator.MODEL_INPUT_SIZE

        // Compute raw depth range for scene-aware disparity scaling
        var rawMin = Float.MAX_VALUE
        var rawMax = Float.MIN_VALUE
        for (v in rawDepthMap) {
            if (v < rawMin) rawMin = v
            if (v > rawMax) rawMax = v
        }
        val rawRange = (rawMax - rawMin).coerceAtLeast(0.001f)

        // Linear normalize preserves depth ratios (no equalization/gamma)
        val normalized = BitmapUtils.normalizeDepthMap(rawDepthMap)

        val smoothed = if (config.depthBlurKernel <= 1) {
            normalized
        } else if (bilateralFilter != null) {
            Log.d(TAG, "Using GPU bilateral filter (spatialSigma=${config.bilateralSpatialSigma})")
            bilateralFilter!!.filter(
                normalized, depthSize, depthSize,
                config.bilateralSpatialSigma,
                ProcessingConfig.BILATERAL_RANGE_SIGMA
            )
        } else {
            Log.d(TAG, "GPU bilateral unavailable, falling back to box blur")
            BitmapUtils.blurDepthMap(normalized, depthSize, depthSize, config.depthBlurKernel)
        }

        warper.generateSbsPair(sourceBitmap, smoothed, config, rawRange)
    }
}
