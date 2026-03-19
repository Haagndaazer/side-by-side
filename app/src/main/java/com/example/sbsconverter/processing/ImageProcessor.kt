package com.example.sbsconverter.processing

import android.graphics.Bitmap
import com.example.sbsconverter.model.ProcessingConfig
import com.example.sbsconverter.util.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImageProcessor {

    private val warper = SbsWarper()

    suspend fun processImage(
        sourceBitmap: Bitmap,
        rawDepthMap518: FloatArray,
        config: ProcessingConfig
    ): Bitmap = withContext(Dispatchers.Default) {
        val normalized = BitmapUtils.normalizeDepthMap(rawDepthMap518)

        val blurred = BitmapUtils.blurDepthMap(
            normalized, 518, 518, config.depthBlurKernel
        )

        warper.generateSbsPair(sourceBitmap, blurred, config)
    }
}
