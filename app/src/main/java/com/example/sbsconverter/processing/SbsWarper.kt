package com.example.sbsconverter.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import com.example.sbsconverter.model.Arrangement
import com.example.sbsconverter.model.ProcessingConfig

class SbsWarper {

    companion object {
        private const val DEPTH_SIZE = 518
        private const val MAX_MESH_DIM = 256
        private const val MESH_DIVISOR = 8
    }

    fun generateSbsPair(
        sourceBitmap: Bitmap,
        normalizedDepth518: FloatArray,
        config: ProcessingConfig
    ): Bitmap {
        val leftEye = warpEye(sourceBitmap, normalizedDepth518, isLeftEye = true, config)
        val rightEye = warpEye(sourceBitmap, normalizedDepth518, isLeftEye = false, config)
        val result = combineViews(leftEye, rightEye, sourceBitmap.width, sourceBitmap.height, config)
        leftEye.recycle()
        rightEye.recycle()
        return result
    }

    private fun warpEye(
        bitmap: Bitmap,
        depth518: FloatArray,
        isLeftEye: Boolean,
        config: ProcessingConfig
    ): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val meshW = minOf(w / MESH_DIVISOR, MAX_MESH_DIM).coerceAtLeast(10)
        val meshH = minOf(h / MESH_DIVISOR, MAX_MESH_DIM).coerceAtLeast(10)

        val vertCount = (meshW + 1) * (meshH + 1)
        val verts = FloatArray(vertCount * 2)

        val maxDisparity = config.depthScale / 100f * w
        val direction = if (isLeftEye) -1f else 1f

        for (row in 0..meshH) {
            for (col in 0..meshW) {
                val idx = (row * (meshW + 1) + col) * 2
                val px = col.toFloat() / meshW * w
                val py = row.toFloat() / meshH * h

                val depthX = (px / w * (DEPTH_SIZE - 1)).coerceIn(0f, (DEPTH_SIZE - 1).toFloat())
                val depthY = (py / h * (DEPTH_SIZE - 1)).coerceIn(0f, (DEPTH_SIZE - 1).toFloat())
                val depth = bilinearSample(depth518, DEPTH_SIZE, DEPTH_SIZE, depthX, depthY)

                val shift = depth * maxDisparity * direction
                verts[idx] = (px + shift).coerceIn(0f, w.toFloat())
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
}
