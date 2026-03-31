package com.example.sbsconverter.processing

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.sbsconverter.util.BitmapUtils
import java.io.Closeable

/**
 * GPU-accelerated depth self-warp: warps the depth map with itself to produce
 * target-view depth. Disocclusion holes are marked with alpha=0 for subsequent
 * directional dilation to fill.
 *
 * This is Pass 1 of the Disney-inspired pipeline. The resulting target-view
 * depth map tells us what depth each output pixel *should* have, enabling
 * principled ghost detection and depth-guided hole filling downstream.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class GpuDepthWarper : Closeable {

    companion object {
        private const val TAG = "GpuDepthWarper"

        private val DEPTH_WARP_SHADER = """
            uniform shader depthMap;
            uniform float2 depthSize;
            uniform float maxDisparity;
            uniform float convergencePoint;
            uniform float direction;
            uniform float fadeWidth;
            uniform float depthDiscontinuityThreshold;

            ${BitmapUtils.AGSL_DECODE_DEPTH}
            ${BitmapUtils.AGSL_ENCODE_DEPTH}

            float sampleDepth(float2 coord) {
                coord = clamp(coord, float2(0.0), depthSize - 1.0);
                float2 base = floor(coord);
                float2 f = coord - base;
                float2 next = min(base + 1.0, depthSize - 1.0);

                float d00 = decodeDepth(depthMap.eval(base + 0.5));
                float d10 = decodeDepth(depthMap.eval(float2(next.x, base.y) + 0.5));
                float d01 = decodeDepth(depthMap.eval(float2(base.x, next.y) + 0.5));
                float d11 = decodeDepth(depthMap.eval(next + 0.5));

                return mix(mix(d00, d10, f.x), mix(d01, d11, f.x), f.y);
            }

            half4 main(float2 fragCoord) {
                float depth = sampleDepth(fragCoord);
                float adjustedDepth = depth - convergencePoint;
                float baseShift = adjustedDepth * maxDisparity * direction;

                // Edge fade (same as image warper)
                float edgeFade;
                if (direction < 0.0) {
                    edgeFade = clamp((depthSize.x - fragCoord.x) / fadeWidth, 0.0, 1.0);
                } else {
                    edgeFade = clamp(fragCoord.x / fadeWidth, 0.0, 1.0);
                }
                float totalShift = baseShift * edgeFade;

                float2 srcCoord = float2(
                    clamp(fragCoord.x - totalShift, 0.0, depthSize.x - 1.0),
                    fragCoord.y
                );

                float srcDepth = sampleDepth(srcCoord);

                // Depth consistency: if source is significantly closer (foreground
                // leaking into background region), this is a disocclusion hole.
                if (srcDepth - depth > depthDiscontinuityThreshold) {
                    return half4(0.0, 0.0, 0.0, 0.0);
                }

                return encodeDepth(srcDepth);
            }
        """
    }

    private var shader: RuntimeShader? = null
    var gpuAvailable = false
        private set

    init {
        try {
            shader = RuntimeShader(DEPTH_WARP_SHADER)
            gpuAvailable = true
        } catch (e: Exception) {
            Log.w(TAG, "AGSL depth warp shader compilation failed: ${e.message}")
            gpuAvailable = false
        }
    }

    /**
     * Warp the depth map to a target eye viewpoint.
     *
     * @param depthBitmap               24-bit packed depth bitmap (1022x1022)
     * @param isLeftEye                 true for left eye, false for right
     * @param maxDisparity              Maximum displacement in depth-space pixels
     * @param convergencePoint          Depth value at screen plane
     * @param edgeFadePercent           Fraction of width for edge fade ramp
     * @param depthDiscontinuityThreshold  Depth difference threshold for hole detection (~0.02)
     * @return Target-view depth bitmap (24-bit packed, alpha=0 for holes)
     */
    fun warpDepth(
        depthBitmap: Bitmap,
        isLeftEye: Boolean,
        maxDisparity: Float,
        convergencePoint: Float,
        edgeFadePercent: Float,
        depthDiscontinuityThreshold: Float = 0.02f
    ): Bitmap {
        if (!gpuAvailable) throw IllegalStateException("GPU not available")

        val w = depthBitmap.width
        val h = depthBitmap.height
        val t0 = System.currentTimeMillis()

        val s = shader!!
        s.setFloatUniform("depthSize", w.toFloat(), h.toFloat())
        s.setFloatUniform("maxDisparity", maxDisparity)
        s.setFloatUniform("convergencePoint", convergencePoint)
        s.setFloatUniform("direction", if (isLeftEye) -1f else 1f)
        s.setFloatUniform("fadeWidth", w * edgeFadePercent)
        s.setFloatUniform("depthDiscontinuityThreshold", depthDiscontinuityThreshold)

        val depthShader = BitmapShader(depthBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        depthShader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        s.setInputBuffer("depthMap", depthShader)

        val result = renderShader(s, w, h)
        val t1 = System.currentTimeMillis()
        Log.d(TAG, "Depth self-warp (${if (isLeftEye) "left" else "right"}): ${t1 - t0}ms")

        return result
    }

    private fun renderShader(shader: RuntimeShader, width: Int, height: Int): Bitmap {
        val imageReader = ImageReader.newInstance(
            width, height,
            PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )

        val renderNode = RenderNode("DepthWarp")
        renderNode.setPosition(0, 0, width, height)

        val renderer = HardwareRenderer()
        renderer.setSurface(imageReader.surface)
        renderer.setContentRoot(renderNode)

        val canvas = renderNode.beginRecording()
        val paint = Paint()
        paint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        renderNode.endRecording()

        renderer.createRenderRequest()
            .setWaitForPresent(true)
            .syncAndDraw()

        val image = imageReader.acquireNextImage()!!
        val hwBuffer = image.hardwareBuffer!!
        val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, null)!!
        val softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)

        hwBitmap.recycle()
        hwBuffer.close()
        image.close()
        imageReader.close()
        renderNode.discardDisplayList()
        renderer.destroy()

        return softBitmap
    }

    override fun close() {
        shader = null
    }
}
