package com.example.sbsconverter.processing

import android.graphics.Bitmap
import com.example.sbsconverter.model.ProcessingConfig
import com.example.sbsconverter.model.SbsResult
import com.example.sbsconverter.util.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImageProcessor {

    private val warper = SbsWarper()

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

        val blurred = BitmapUtils.blurDepthMap(
            normalized, depthSize, depthSize, config.depthBlurKernel
        )

        warper.generateSbsPair(sourceBitmap, blurred, config, rawRange)
    }
}
