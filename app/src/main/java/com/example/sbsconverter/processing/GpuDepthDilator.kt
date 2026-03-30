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
 * GPU-accelerated edge-aware foreground depth dilation.
 *
 * Runs a separable max-filter (horizontal then vertical) on the 24-bit packed
 * depth map. At each pixel, the filter picks the maximum (closest/foreground)
 * depth value within the dilation radius, but only across real depth
 * discontinuities (where the difference exceeds EDGE_THRESHOLD).
 *
 * This expands foreground depth outward by a few pixels so that foreground
 * objects "carry" their boundary pixels during the stereo warp, preventing
 * thin ghost gaps.
 *
 * Precision: the max-filter only selects existing depth values — zero precision
 * loss. The 24-bit encode/decode is lossless for values already quantized to
 * 24-bit (which they are, coming from the normalization step).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class GpuDepthDilator : Closeable {

    companion object {
        private const val TAG = "GpuDepthDilator"

        private val HORIZONTAL_SHADER = """
            uniform shader inputImage;
            uniform float2 iResolution;
            uniform float dilationRadius;
            const int MAX_DILATION_RADIUS = 5;
            const float EDGE_THRESHOLD = 0.02;

            ${BitmapUtils.AGSL_DECODE_DEPTH}
            ${BitmapUtils.AGSL_ENCODE_DEPTH}

            half4 main(float2 fragCoord) {
                float centerDepth = decodeDepth(inputImage.eval(fragCoord));
                float maxDepth = centerDepth;

                for (int i = -MAX_DILATION_RADIUS; i <= MAX_DILATION_RADIUS; i++) {
                    if (i == 0) continue;
                    if (abs(float(i)) > dilationRadius) continue;
                    float2 sc = float2(
                        clamp(fragCoord.x + float(i), 0.0, iResolution.x - 1.0),
                        fragCoord.y
                    );
                    float neighborDepth = decodeDepth(inputImage.eval(sc));
                    if (abs(neighborDepth - centerDepth) > EDGE_THRESHOLD) {
                        maxDepth = max(maxDepth, neighborDepth);
                    }
                }

                return encodeDepth(maxDepth);
            }
        """

        private val VERTICAL_SHADER = """
            uniform shader inputImage;
            uniform float2 iResolution;
            uniform float dilationRadius;
            const int MAX_DILATION_RADIUS = 5;
            const float EDGE_THRESHOLD = 0.02;

            ${BitmapUtils.AGSL_DECODE_DEPTH}
            ${BitmapUtils.AGSL_ENCODE_DEPTH}

            half4 main(float2 fragCoord) {
                float centerDepth = decodeDepth(inputImage.eval(fragCoord));
                float maxDepth = centerDepth;

                for (int i = -MAX_DILATION_RADIUS; i <= MAX_DILATION_RADIUS; i++) {
                    if (i == 0) continue;
                    if (abs(float(i)) > dilationRadius) continue;
                    float2 sc = float2(
                        fragCoord.x,
                        clamp(fragCoord.y + float(i), 0.0, iResolution.y - 1.0)
                    );
                    float neighborDepth = decodeDepth(inputImage.eval(sc));
                    if (abs(neighborDepth - centerDepth) > EDGE_THRESHOLD) {
                        maxDepth = max(maxDepth, neighborDepth);
                    }
                }

                return encodeDepth(maxDepth);
            }
        """
    }

    private var horizontalShader: RuntimeShader? = null
    private var verticalShader: RuntimeShader? = null
    var gpuAvailable = false
        private set

    init {
        try {
            horizontalShader = RuntimeShader(HORIZONTAL_SHADER)
            verticalShader = RuntimeShader(VERTICAL_SHADER)
            gpuAvailable = true
        } catch (e: Exception) {
            Log.w(TAG, "AGSL shader compilation failed: ${e.message}")
            gpuAvailable = false
        }
    }

    /**
     * Run separable edge-aware depth dilation on the GPU.
     *
     * @param depth       Normalized depth FloatArray [0,1], row-major, width x height
     * @param width       Depth map width
     * @param height      Depth map height
     * @param radius      Dilation radius in pixels (clamped to MAX_DILATION_RADIUS=5)
     * @return            Dilated depth FloatArray [0,1]
     */
    fun filter(
        depth: FloatArray,
        width: Int,
        height: Int,
        radius: Float
    ): FloatArray {
        if (!gpuAvailable) throw IllegalStateException("GPU not available")

        val t0 = System.currentTimeMillis()
        val inputBitmap = BitmapUtils.floatArrayToPackedBitmap(depth, width, height)
        val t1 = System.currentTimeMillis()
        Log.d(TAG, "24-bit encode: ${t1 - t0}ms")

        // Pass 1: Horizontal dilation
        val hShader = horizontalShader!!
        hShader.setFloatUniform("iResolution", width.toFloat(), height.toFloat())
        hShader.setFloatUniform("dilationRadius", radius.coerceAtMost(5f))
        val inputShader = BitmapShader(inputBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        inputShader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        hShader.setInputBuffer("inputImage", inputShader)

        val intermediate = renderShader(hShader, width, height)
        val t2 = System.currentTimeMillis()
        Log.d(TAG, "GPU horizontal dilation: ${t2 - t1}ms")
        inputBitmap.recycle()

        // Pass 2: Vertical dilation
        val vShader = verticalShader!!
        vShader.setFloatUniform("iResolution", width.toFloat(), height.toFloat())
        vShader.setFloatUniform("dilationRadius", radius.coerceAtMost(5f))
        val intermediateShader = BitmapShader(intermediate, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        intermediateShader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        vShader.setInputBuffer("inputImage", intermediateShader)

        val output = renderShader(vShader, width, height)
        val t3 = System.currentTimeMillis()
        Log.d(TAG, "GPU vertical dilation: ${t3 - t2}ms")
        intermediate.recycle()

        val result = BitmapUtils.packedBitmapToFloatArray(output)
        val t4 = System.currentTimeMillis()
        Log.d(TAG, "24-bit decode: ${t4 - t3}ms, total dilation: ${t4 - t0}ms")
        output.recycle()

        return result
    }

    private fun renderShader(shader: RuntimeShader, width: Int, height: Int): Bitmap {
        val imageReader = ImageReader.newInstance(
            width, height,
            PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )

        val renderNode = RenderNode("DepthDilation")
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
        horizontalShader = null
        verticalShader = null
    }
}
