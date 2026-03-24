package com.example.sbsconverter.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.Log
import com.example.sbsconverter.model.Arrangement
import com.example.sbsconverter.model.ProcessingConfig
import com.example.sbsconverter.model.SbsResult
import java.io.Closeable

class SbsWarper : Closeable {

    companion object {
        private const val DEPTH_SIZE = 1022
        private const val MAX_MESH_DIM = 256
        private const val MESH_DIVISOR = 8
        private const val EDGE_FADE_PERCENT = 0.02f
    }

    private var gpuWarper: GpuStereoWarper? = null
    private var gpuInitialized = false

    fun generateSbsPair(
        sourceBitmap: Bitmap,
        processedDepth: FloatArray,
        config: ProcessingConfig,
        depthRange: Float = 1f
    ): SbsResult {
        // Scene-aware disparity: deeper scenes (larger rawRange) get more parallax
        val maxDisparity = config.depthScale / 100f * sourceBitmap.width / 2f * depthRange

        // Try GPU per-pixel warp (API 33+), fall back to CPU mesh warp
        val eyes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val gpu = getOrCreateGpuWarper()
            if (gpu != null && gpu.gpuAvailable) {
                try {
                    Pair(
                        gpu.warpEye(sourceBitmap, processedDepth,
                            isLeftEye = true, maxDisparity, config.convergencePoint, EDGE_FADE_PERCENT),
                        gpu.warpEye(sourceBitmap, processedDepth,
                            isLeftEye = false, maxDisparity, config.convergencePoint, EDGE_FADE_PERCENT)
                    )
                } catch (e: Exception) {
                    Log.w("SbsWarper", "GPU warp failed, falling back to CPU: ${e.message}")
                    null
                }
            } else null
        } else null

        val (leftEye, rightEye) = eyes ?: Pair(
            warpEyeCpu(sourceBitmap, processedDepth, true, config, maxDisparity),
            warpEyeCpu(sourceBitmap, processedDepth, false, config, maxDisparity)
        )

        val combined = combineViews(leftEye, rightEye, sourceBitmap.width, sourceBitmap.height, config)
        leftEye.recycle()
        rightEye.recycle()

        return SbsResult(sbsBitmap = combined)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun getOrCreateGpuWarper(): GpuStereoWarper? {
        if (!gpuInitialized) {
            gpuInitialized = true
            gpuWarper = try {
                GpuStereoWarper()
            } catch (e: Exception) {
                Log.w("SbsWarper", "Failed to create GPU warper: ${e.message}")
                null
            }
        }
        return gpuWarper
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
    }
}
