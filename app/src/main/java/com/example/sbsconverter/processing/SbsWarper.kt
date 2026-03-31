package com.example.sbsconverter.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Gainmap
import android.os.Build
import android.util.Log
import com.example.sbsconverter.model.Arrangement
import com.example.sbsconverter.model.ProcessingConfig
import com.example.sbsconverter.model.SbsResult
import com.example.sbsconverter.util.BitmapUtils
import java.io.Closeable

class SbsWarper : Closeable {

    companion object {
        private const val TAG = "SbsWarper"
        private const val DEPTH_SIZE = 1022
        private const val MAX_MESH_DIM = 256
        private const val MESH_DIVISOR = 8
        private const val EDGE_FADE_PERCENT = 0.02f
    }

    private var gpuWarper: GpuStereoWarper? = null
    private var gpuInitialized = false
    private var depthWarper: GpuDepthWarper? = null
    private var depthWarperInitialized = false
    private var depthDilator: GpuDepthDilator? = null
    private var depthDilatorInitialized = false
    private var depthGuidedFiller: GpuDepthGuidedFiller? = null
    private var depthGuidedFillerInitialized = false

    fun generateSbsPair(
        sourceBitmap: Bitmap,
        processedDepth: FloatArray,
        config: ProcessingConfig,
        depthRange: Float = 1f
    ): SbsResult {
        val maxDisparity = config.depthScale / 100f * sourceBitmap.width / 2f * depthRange

        // Warp base image into left/right eye views
        val (leftEye, rightEye) = warpPair(sourceBitmap, processedDepth, config, maxDisparity)
        val combined = combineViews(leftEye, rightEye, sourceBitmap.width, sourceBitmap.height, config)
        leftEye.recycle()
        rightEye.recycle()

        // Warp gainmap with same displacement if source is Ultra HDR (API 34+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && sourceBitmap.hasGainmap()) {
            try {
                attachWarpedGainmap(sourceBitmap, combined, processedDepth, config, maxDisparity)
            } catch (e: Exception) {
                Log.w(TAG, "Gainmap warp failed, output will be SDR: ${e.message}")
            }
        }

        return SbsResult(sbsBitmap = combined)
    }

    /**
     * Warp a bitmap into left and right eye views using the Disney-inspired pipeline:
     *   Pass 1: Depth self-warp (target-view depth with holes)
     *   Pass 2-3: Directional depth dilation (dense target-view depth)
     *   Pass 4: Disparity-aware warp with target depth validation
     *   Pass 5: Depth-guided hole fill
     * Falls back to CPU mesh warp if GPU is unavailable.
     */
    private fun warpPair(
        bitmap: Bitmap,
        processedDepth: FloatArray,
        config: ProcessingConfig,
        maxDisparity: Float
    ): Pair<Bitmap, Bitmap> {
        val halfDisparity = maxDisparity / 2f

        val gpuResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val gpu = getOrCreateGpuWarper()
            val dw = getOrCreateDepthWarper()
            val dd = getOrCreateDepthDilator()
            val filler = getOrCreateDepthGuidedFiller()

            if (gpu != null && gpu.gpuAvailable &&
                dw != null && dw.gpuAvailable &&
                dd != null && dd.gpuAvailable) {
                try {
                    warpPairDisney(bitmap, processedDepth, config, halfDisparity,
                        gpu, dw, dd, filler)
                } catch (e: Exception) {
                    Log.w(TAG, "GPU Disney pipeline failed, falling back to CPU: ${e.message}")
                    null
                }
            } else null
        } else null

        return gpuResult ?: Pair(
            warpEyeCpu(bitmap, processedDepth, true, config, halfDisparity),
            warpEyeCpu(bitmap, processedDepth, false, config, halfDisparity)
        )
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun warpPairDisney(
        bitmap: Bitmap,
        processedDepth: FloatArray,
        config: ProcessingConfig,
        halfDisparity: Float,
        gpu: GpuStereoWarper,
        dw: GpuDepthWarper,
        dd: GpuDepthDilator,
        filler: GpuDepthGuidedFiller?
    ): Pair<Bitmap, Bitmap> {
        val depthSize = DepthEstimator.MODEL_INPUT_SIZE

        // Encode depth to 24-bit packed bitmap (shared by all passes)
        val depthBitmap = BitmapUtils.floatArrayToPackedBitmap(processedDepth, depthSize, depthSize)

        // Scale disparity from image-space to depth-space for passes 1-3
        val depthSpaceDisparity = halfDisparity * depthSize.toFloat() / bitmap.width.toFloat()

        val leftEye = warpOneEyeDisney(bitmap, depthBitmap, depthSize, config,
            halfDisparity, depthSpaceDisparity, true, gpu, dw, dd, filler)
        val rightEye = warpOneEyeDisney(bitmap, depthBitmap, depthSize, config,
            halfDisparity, depthSpaceDisparity, false, gpu, dw, dd, filler)

        depthBitmap.recycle()
        return Pair(leftEye, rightEye)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun warpOneEyeDisney(
        bitmap: Bitmap,
        depthBitmap: Bitmap,
        depthSize: Int,
        config: ProcessingConfig,
        halfDisparity: Float,
        depthSpaceDisparity: Float,
        isLeftEye: Boolean,
        gpu: GpuStereoWarper,
        dw: GpuDepthWarper,
        dd: GpuDepthDilator,
        filler: GpuDepthGuidedFiller?
    ): Bitmap {
        val eyeName = if (isLeftEye) "left" else "right"
        val t0 = System.currentTimeMillis()

        // Pass 1: Depth self-warp -> target-view depth with holes
        val warpedDepth = dw.warpDepth(
            depthBitmap, isLeftEye, depthSpaceDisparity,
            config.convergencePoint, EDGE_FADE_PERCENT
        )

        // Pass 2-3: Directional dilation -> dense target-view depth
        val denseTargetDepth = dd.fillHoles(warpedDepth, isLeftEye, iterations = 3)
        warpedDepth.recycle()

        // Pass 4: V2 warp with target depth validation
        var warped = gpu.warpEyeV2(
            bitmap, depthBitmap, denseTargetDepth,
            isLeftEye, halfDisparity, config.convergencePoint,
            EDGE_FADE_PERCENT
        )

        // Pass 5: Depth-guided hole fill
        if (filler != null && filler.gpuAvailable) {
            val filled = filler.fill(
                warped, bitmap, depthBitmap, denseTargetDepth,
                isLeftEye, halfDisparity, config.convergencePoint
            )
            warped.recycle()
            warped = filled
        }

        denseTargetDepth.recycle()
        val t1 = System.currentTimeMillis()
        Log.d(TAG, "Disney pipeline ($eyeName): ${t1 - t0}ms total")

        return warped
    }

    /**
     * Warp the source bitmap's gainmap and attach it to the SBS output.
     * The gainmap is warped with the same displacement as the base image so
     * HDR brightness boosts stay aligned with their corresponding pixels.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun attachWarpedGainmap(
        sourceBitmap: Bitmap,
        sbsBitmap: Bitmap,
        processedDepth: FloatArray,
        config: ProcessingConfig,
        maxDisparity: Float
    ) {
        val sourceGainmap = sourceBitmap.gainmap ?: return
        var gainmapBitmap = sourceGainmap.gainmapContents

        Log.d(TAG, "Ultra HDR: gainmap ${gainmapBitmap.width}x${gainmapBitmap.height} config=${gainmapBitmap.config}")

        // ALPHA_8 bitmaps produce half4(0,0,0,a) in shaders — convert to ARGB_8888
        val needsAlphaConversion = gainmapBitmap.config == Bitmap.Config.ALPHA_8
        if (needsAlphaConversion) {
            val converted = BitmapUtils.convertAlpha8ToArgb(gainmapBitmap)
            gainmapBitmap = converted
        }

        // Scale gainmap to match source dimensions for consistent warp coordinates
        val needsScale = gainmapBitmap.width != sourceBitmap.width || gainmapBitmap.height != sourceBitmap.height
        val prepared = if (needsScale) {
            val scaled = Bitmap.createScaledBitmap(gainmapBitmap, sourceBitmap.width, sourceBitmap.height, true)
            if (needsAlphaConversion) gainmapBitmap.recycle()
            scaled
        } else {
            gainmapBitmap
        }

        // Warp with identical displacement as the base image
        val (leftGm, rightGm) = warpPair(prepared, processedDepth, config, maxDisparity)
        val combinedGm = combineViews(leftGm, rightGm, sourceBitmap.width, sourceBitmap.height, config)
        leftGm.recycle()
        rightGm.recycle()
        if (prepared !== sourceGainmap.gainmapContents) prepared.recycle()

        // Copy all metadata from original gainmap
        val newGainmap = Gainmap(combinedGm)
        val ratioMin = sourceGainmap.ratioMin
        newGainmap.setRatioMin(ratioMin[0], ratioMin[1], ratioMin[2])
        val ratioMax = sourceGainmap.ratioMax
        newGainmap.setRatioMax(ratioMax[0], ratioMax[1], ratioMax[2])
        val gamma = sourceGainmap.gamma
        newGainmap.setGamma(gamma[0], gamma[1], gamma[2])
        val epsilonSdr = sourceGainmap.epsilonSdr
        newGainmap.setEpsilonSdr(epsilonSdr[0], epsilonSdr[1], epsilonSdr[2])
        val epsilonHdr = sourceGainmap.epsilonHdr
        newGainmap.setEpsilonHdr(epsilonHdr[0], epsilonHdr[1], epsilonHdr[2])
        newGainmap.setDisplayRatioForFullHdr(sourceGainmap.displayRatioForFullHdr)
        newGainmap.setMinDisplayRatioForHdrTransition(sourceGainmap.minDisplayRatioForHdrTransition)

        sbsBitmap.setGainmap(newGainmap)
        Log.d(TAG, "Ultra HDR: gainmap attached to SBS output (${combinedGm.width}x${combinedGm.height})")
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun getOrCreateGpuWarper(): GpuStereoWarper? {
        if (!gpuInitialized) {
            gpuInitialized = true
            gpuWarper = try {
                GpuStereoWarper()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create GPU warper: ${e.message}")
                null
            }
        }
        return gpuWarper
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun getOrCreateDepthWarper(): GpuDepthWarper? {
        if (!depthWarperInitialized) {
            depthWarperInitialized = true
            depthWarper = try {
                GpuDepthWarper()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create depth warper: ${e.message}")
                null
            }
        }
        return depthWarper
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun getOrCreateDepthDilator(): GpuDepthDilator? {
        if (!depthDilatorInitialized) {
            depthDilatorInitialized = true
            depthDilator = try {
                GpuDepthDilator()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create depth dilator: ${e.message}")
                null
            }
        }
        return depthDilator
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun getOrCreateDepthGuidedFiller(): GpuDepthGuidedFiller? {
        if (!depthGuidedFillerInitialized) {
            depthGuidedFillerInitialized = true
            depthGuidedFiller = try {
                GpuDepthGuidedFiller()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create depth-guided filler: ${e.message}")
                null
            }
        }
        return depthGuidedFiller
    }

    /** CPU mesh warp fallback for API < 33 or GPU failure */
    private fun warpEyeCpu(
        bitmap: Bitmap,
        depthMap: FloatArray,
        isLeftEye: Boolean,
        config: ProcessingConfig,
        maxDisparity: Float
    ): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val meshW = minOf(w / MESH_DIVISOR, MAX_MESH_DIM).coerceAtLeast(10)
        val meshH = minOf(h / MESH_DIVISOR, MAX_MESH_DIM).coerceAtLeast(10)

        val vertCount = (meshW + 1) * (meshH + 1)
        val verts = FloatArray(vertCount * 2)

        val direction = if (isLeftEye) -1f else 1f
        val fadeWidth = w * EDGE_FADE_PERCENT

        for (row in 0..meshH) {
            for (col in 0..meshW) {
                val idx = (row * (meshW + 1) + col) * 2
                val px = col.toFloat() / meshW * w
                val py = row.toFloat() / meshH * h

                val depthX = (px / w * (DEPTH_SIZE - 1)).coerceIn(0f, (DEPTH_SIZE - 1).toFloat())
                val depthY = (py / h * (DEPTH_SIZE - 1)).coerceIn(0f, (DEPTH_SIZE - 1).toFloat())
                val depth = bilinearSample(depthMap, DEPTH_SIZE, DEPTH_SIZE, depthX, depthY)

                val adjustedDepth = depth - config.convergencePoint
                val baseShift = adjustedDepth * maxDisparity * direction

                val edgeFade = if (isLeftEye) {
                    ((w - px) / fadeWidth).coerceIn(0f, 1f)
                } else {
                    (px / fadeWidth).coerceIn(0f, 1f)
                }

                val totalShift = baseShift * edgeFade
                verts[idx] = (px + totalShift).coerceIn(0f, w.toFloat())
                verts[idx + 1] = py
            }
        }

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmapMesh(bitmap, meshW, meshH, verts, 0, null, 0, null)
        return output
    }

    private fun bilinearSample(
        data: FloatArray,
        width: Int,
        height: Int,
        x: Float,
        y: Float
    ): Float {
        val x0 = x.toInt().coerceIn(0, width - 2)
        val y0 = y.toInt().coerceIn(0, height - 2)
        val x1 = x0 + 1
        val y1 = y0 + 1
        val fx = x - x0
        val fy = y - y0

        val v00 = data[y0 * width + x0]
        val v10 = data[y0 * width + x1]
        val v01 = data[y1 * width + x0]
        val v11 = data[y1 * width + x1]

        return v00 * (1 - fx) * (1 - fy) +
                v10 * fx * (1 - fy) +
                v01 * (1 - fx) * fy +
                v11 * fx * fy
    }

    private fun combineViews(
        leftView: Bitmap,
        rightView: Bitmap,
        sourceW: Int,
        sourceH: Int,
        config: ProcessingConfig
    ): Bitmap {
        val first = if (config.arrangement == Arrangement.CROSS_EYED) rightView else leftView
        val second = if (config.arrangement == Arrangement.CROSS_EYED) leftView else rightView

        val output = Bitmap.createBitmap(sourceW * 2, sourceH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(first, 0f, 0f, null)
        canvas.drawBitmap(second, sourceW.toFloat(), 0f, null)
        return output
    }

    override fun close() {
        gpuWarper?.close()
        gpuWarper = null
        depthWarper?.close()
        depthWarper = null
        depthDilator?.close()
        depthDilator = null
        depthGuidedFiller?.close()
        depthGuidedFiller = null
    }
}
