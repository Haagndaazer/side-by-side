package com.example.sbsconverter.processing

import android.graphics.Bitmap
import com.example.sbsconverter.model.ProcessingConfig
import com.example.sbsconverter.model.SbsResult
import com.example.sbsconverter.util.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImageProcessor {

    private val warper = SbsWarper()
    private val depthEnhancer = DepthEnhancer()

    suspend fun processImage(
        sourceBitmap: Bitmap,
        rawDepthMap: FloatArray,
        config: ProcessingConfig
    ): SbsResult = withContext(Dispatchers.Default) {
        val depthSize = DepthEstimator.MODEL_INPUT_SIZE
        // Pipeline: normalize → histogram equalize → gamma remap → [enhance with raw detail] → blur → warp
        val normalized = BitmapUtils.normalizeDepthMap(rawDepthMap)
        val equalized = BitmapUtils.equalizeDepthHistogram(normalized)
        val remapped = BitmapUtils.remapDepthGamma(equalized, config.depthGamma)

        // Bilateral unsharp mask — extract micro-detail from RAW depth, apply to processed
        val enhanced = if (config.surfaceDetail > 0f) {
            depthEnhancer.bilateralUnsharpMask(
                rawDepth = rawDepthMap,
                processedDepth = remapped,
                width = depthSize,
                height = depthSize,
                strength = config.surfaceDetail
            )
        } else {
            remapped
        }

        val blurred = BitmapUtils.blurDepthMap(
            enhanced, depthSize, depthSize, config.depthBlurKernel
        )

        warper.generateSbsPair(sourceBitmap, blurred, config)
    }
}
