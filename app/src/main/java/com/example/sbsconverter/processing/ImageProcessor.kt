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
        rawDepthMap518: FloatArray,
        config: ProcessingConfig
    ): SbsResult = withContext(Dispatchers.Default) {
        // Pipeline: normalize → histogram equalize → gamma remap → blur → warp
        val normalized = BitmapUtils.normalizeDepthMap(rawDepthMap518)
        val equalized = BitmapUtils.equalizeDepthHistogram(normalized)
        val remapped = BitmapUtils.remapDepthGamma(equalized, config.depthGamma)

        val blurred = BitmapUtils.blurDepthMap(
            remapped, 518, 518, config.depthBlurKernel
        )

        warper.generateSbsPair(sourceBitmap, blurred, config)
    }
}
