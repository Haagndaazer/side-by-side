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

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class GpuStereoWarper : Closeable {

    companion object {
        private const val TAG = "GpuStereoWarper"

        private val WARP_SHADER = """
            uniform shader sourceImage;
            uniform shader depthMap;
            uniform float2 imageSize;
            uniform float2 depthSize;
            uniform float maxDisparity;
            uniform float convergencePoint;
            uniform float direction;
            uniform float fadeWidth;

            ${BitmapUtils.AGSL_DECODE_DEPTH}

            // Manual bilinear interpolation of packed depth.
            // Hardware bilinear would interpolate raw bytes, not decoded depth.
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
                // Map output pixel to depth map coordinates
                float2 normCoord = fragCoord / imageSize;
                float2 depthCoord = normCoord * (depthSize - 1.0);

                // Sample depth (24-bit packed precision) and compute base shift
                float depth = sampleDepth(depthCoord);
                float adjustedDepth = depth - convergencePoint;
                float baseShift = adjustedDepth * maxDisparity * direction;

                // Edge fade on trailing side
                float edgeFade;
                if (direction < 0.0) {
                    edgeFade = clamp((imageSize.x - fragCoord.x) / fadeWidth, 0.0, 1.0);
                } else {
                    edgeFade = clamp(fragCoord.x / fadeWidth, 0.0, 1.0);
                }

                float totalShift = baseShift * edgeFade;

                // Backward warp: sample source at shifted position
                float2 srcCoord = float2(
                    clamp(fragCoord.x - totalShift, 0.0, imageSize.x - 1.0),
                    fragCoord.y
                );

                return sourceImage.eval(srcCoord);
            }
        """
    }

    private var shader: RuntimeShader? = null
    var gpuAvailable = false
        private set

    init {
        try {
            shader = RuntimeShader(WARP_SHADER)
            gpuAvailable = true
        } catch (e: Exception) {
            Log.w(TAG, "AGSL warp shader compilation failed: ${e.message}")
            gpuAvailable = false
        }
    }

    /**
     * Warp a single eye view using the GPU shader.
     *
     * @param sourceBitmap      The source photo
     * @param depthMap          1022x1022 processed depth FloatArray (0-1, linearly normalized)
     * @param isLeftEye         true for left eye, false for right
     * @param maxDisparity      Maximum pixel displacement (scene-aware, includes rawRange)
     * @param convergencePoint  Depth value at screen plane
     * @param edgeFadePercent   Fraction of width for edge fade ramp
     * @return Warped eye bitmap at source dimensions
     */
    fun warpEye(
        sourceBitmap: Bitmap,
        depthMap: FloatArray,
        isLeftEye: Boolean,
        maxDisparity: Float,
        convergencePoint: Float,
        edgeFadePercent: Float
    ): Bitmap {
        if (!gpuAvailable) throw IllegalStateException("GPU not available")

        val w = sourceBitmap.width
        val h = sourceBitmap.height
        val depthSize = DepthEstimator.MODEL_INPUT_SIZE

        val s = shader!!
        s.setFloatUniform("imageSize", w.toFloat(), h.toFloat())
        s.setFloatUniform("depthSize", depthSize.toFloat(), depthSize.toFloat())
        s.setFloatUniform("maxDisparity", maxDisparity)
        s.setFloatUniform("convergencePoint", convergencePoint)
        s.setFloatUniform("direction", if (isLeftEye) -1f else 1f)
        s.setFloatUniform("fadeWidth", w * edgeFadePercent)

        // Source image — LINEAR for smooth color sampling during backward warp
        val sourceShader = BitmapShader(sourceBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        sourceShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        s.setInputBuffer("sourceImage", sourceShader)

        // Depth map — 24-bit packed encoding (~16.7M depth levels)
        // NEAREST required: shader decodes each texel before manual bilinear interpolation
        val depthBitmap = BitmapUtils.floatArrayToPackedBitmap(depthMap, depthSize, depthSize)
        val depthShader = BitmapShader(depthBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        depthShader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        s.setInputBuffer("depthMap", depthShader)

        val result = renderShader(s, w, h)
        depthBitmap.recycle()
        return result
    }

    private fun renderShader(shader: RuntimeShader, width: Int, height: Int): Bitmap {
        val imageReader = ImageReader.newInstance(
            width, height,
            PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )

        val renderNode = RenderNode("StereoWarp")
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
