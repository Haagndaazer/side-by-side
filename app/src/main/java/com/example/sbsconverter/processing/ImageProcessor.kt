package com.example.sbsconverter.processing

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.example.sbsconverter.model.ProcessingConfig
import com.example.sbsconverter.model.SbsResult
import com.example.sbsconverter.util.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

class ImageProcessor {

    companion object {
        private const val TAG = "ImageProcessor"
    }

    private val warper = SbsWarper()
    private val depthDilator: GpuDepthDilator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val dilator = GpuDepthDilator()
            if (dilator.gpuAvailable) dilator else { dilator.close(); null }
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

        // Foreground depth dilation: expand close-object depth outward to prevent
        // ghost gaps during stereo warp. Radius scales with 3D strength.
        val processedDepth = if (depthDilator != null) {
            val maxDisparity = config.depthScale / 100f * sourceBitmap.width / 2f * rawRange
            val dilationRadius = ceil(maxDisparity / sourceBitmap.width * depthSize * 0.5f)
                .coerceIn(2f, 5f)
            Log.d(TAG, "Depth dilation: radius=$dilationRadius (maxDisparity=$maxDisparity)")
            depthDilator!!.filter(normalized, depthSize, depthSize, dilationRadius)
        } else {
            normalized
        }

        warper.generateSbsPair(sourceBitmap, processedDepth, config, rawRange)
    }
}
