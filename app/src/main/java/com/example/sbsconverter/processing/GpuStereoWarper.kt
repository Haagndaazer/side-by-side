package com.example.sbsconverter.processing

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
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
import java.io.Closeable
import kotlin.math.abs

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class GpuStereoWarper : Closeable {

    companion object {
        private const val TAG = "GpuStereoWarper"

        private const val WARP_SHADER = """
            uniform shader sourceImage;
            uniform shader depthMap;
            uniform shader gradientMap;
            uniform float2 imageSize;
            uniform float2 depthSize;
            uniform float maxDisparity;
            uniform float convergencePoint;
            uniform float direction;
            uniform float fadeWidth;
            uniform float hasGradient;
            uniform float gradientScale;

            half4 main(float2 fragCoord) {
                // Map output pixel to depth map coordinates
                float2 normCoord = fragCoord / imageSize;
                float2 depthCoord = normCoord * (depthSize - 1.0);

                // Sample depth and compute base shift
                float depth = float(depthMap.eval(depthCoord).r);
                float adjustedDepth = depth - convergencePoint;
                float baseShift = adjustedDepth * maxDisparity * direction;

                // Gradient micro-parallax (if enabled)
                float gradShift = 0.0;
                if (hasGradient > 0.5) {
                    float encodedGrad = float(gradientMap.eval(depthCoord).r);
                    gradShift = (encodedGrad * 2.0 - 1.0) * gradientScale * direction;
                }

                // Edge fade on trailing side
                float edgeFade;
                if (direction < 0.0) {
                    // Left eye shifts left -> right edge loses pixels
                    edgeFade = clamp((imageSize.x - fragCoord.x) / fadeWidth, 0.0, 1.0);
                } else {
                    // Right eye shifts right -> left edge loses pixels
                    edgeFade = clamp(fragCoord.x / fadeWidth, 0.0, 1.0);
                }

                float totalShift = (baseShift + gradShift) * edgeFade;

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
     * @param sourceBitmap  The source photo
     * @param depthMap      770x770 processed depth FloatArray (0-1)
     * @param gradientMap   770x770 gradient FloatArray (signed), or null
     * @param isLeftEye     true for left eye, false for right
     * @param maxDisparity  Maximum pixel displacement
     * @param convergencePoint  Depth value at screen plane
     * @param edgeFadePercent   Fraction of width for edge fade ramp
     * @return Warped eye bitmap at source dimensions
     */
    fun warpEye(
        sourceBitmap: Bitmap,
        depthMap: FloatArray,
        gradientMap: FloatArray?,
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

        // Depth map (FloatArray -> grayscale bitmap)
        val depthBitmap = floatArrayToGrayscaleBitmap(depthMap, depthSize, depthSize)
        s.setInputBuffer("depthMap",
            BitmapShader(depthBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))

        // Gradient map (optional, bias-encoded)
        if (gradientMap != null) {
            s.setFloatUniform("hasGradient", 1f)
            val maxAbsGrad = gradientMap.maxOfOrNull { abs(it) } ?: 1f
            val gradScale = if (maxAbsGrad > 0f) maxAbsGrad else 1f
            s.setFloatUniform("gradientScale", gradScale)
            val gradBitmap = encodedGradientToBitmap(gradientMap, depthSize, depthSize, gradScale)
            s.setInputBuffer("gradientMap",
                BitmapShader(gradBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))

            val result = renderShader(s, w, h)
            depthBitmap.recycle()
            gradBitmap.recycle()
            return result
        } else {
            s.setFloatUniform("hasGradient", 0f)
            s.setFloatUniform("gradientScale", 1f)
            // Need a dummy gradient shader — use depth bitmap as placeholder
            s.setInputBuffer("gradientMap",
                BitmapShader(depthBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))

            val result = renderShader(s, w, h)
            depthBitmap.recycle()
            return result
        }
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

    private fun floatArrayToGrayscaleBitmap(data: FloatArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(data.size)
        for (i in data.indices) {
            val v = (data[i].coerceIn(0f, 1f) * 255f).toInt()
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * Encode signed gradient values into a grayscale bitmap with bias 0.5.
     * Encoded = (gradient / (2 * maxAbsGrad)) + 0.5, stored in all RGB channels.
     * Shader decodes as: (sample * 2.0 - 1.0) * gradientScale
     */
    private fun encodedGradientToBitmap(
        gradient: FloatArray, width: Int, height: Int, maxAbsGrad: Float
    ): Bitmap {
        val pixels = IntArray(gradient.size)
        val scale = if (maxAbsGrad > 0f) 1f / (2f * maxAbsGrad) else 0f
        for (i in gradient.indices) {
            val encoded = (gradient[i] * scale + 0.5f).coerceIn(0f, 1f)
            val v = (encoded * 255f).toInt()
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    override fun close() {
        shader = null
    }
}
