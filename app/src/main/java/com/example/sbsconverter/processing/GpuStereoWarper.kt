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
import android.util.Half
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.Closeable

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class GpuStereoWarper : Closeable {

    companion object {
        private const val TAG = "GpuStereoWarper"

        private const val WARP_SHADER = """
            uniform shader sourceImage;
            uniform shader depthMap;
            uniform float2 imageSize;
            uniform float2 depthSize;
            uniform float maxDisparity;
            uniform float convergencePoint;
            uniform float direction;
            uniform float fadeWidth;

            half4 main(float2 fragCoord) {
                // Map output pixel to depth map coordinates
                float2 normCoord = fragCoord / imageSize;
                float2 depthCoord = normCoord * (depthSize - 1.0);

                // Sample depth (F16 precision) and compute base shift
                float depth = float(depthMap.eval(depthCoord).r);
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

        // Source image
        s.setInputBuffer("sourceImage",
            BitmapShader(sourceBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))

        // Depth map — F16 encoding for ~2048 depth levels (vs 256 with 8-bit)
        val depthBitmap = floatArrayToF16Bitmap(depthMap, depthSize, depthSize)
        s.setInputBuffer("depthMap",
            BitmapShader(depthBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))

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

    /**
     * Encode depth FloatArray as RGBA_F16 bitmap for full half-float precision.
     * F16 gives ~2048 usable depth levels in [0,1] vs 256 with 8-bit grayscale.
     * Proven pattern from GpuBilateralFilter — F16 input bitmaps work on Pixel 10.
     */
    private fun floatArrayToF16Bitmap(data: FloatArray, width: Int, height: Int): Bitmap {
        val buffer = java.nio.ShortBuffer.allocate(data.size * 4)
        val oneHalf = Half.toHalf(1f)
        for (i in data.indices) {
            val h = Half.toHalf(data[i].coerceIn(0f, 1f))
            buffer.put(h)       // R
            buffer.put(h)       // G
            buffer.put(h)       // B
            buffer.put(oneHalf) // A
        }
        buffer.rewind()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    override fun close() {
        shader = null
    }
}
