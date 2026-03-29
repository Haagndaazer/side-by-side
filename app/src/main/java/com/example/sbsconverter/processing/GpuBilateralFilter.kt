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
class GpuBilateralFilter : Closeable {

    companion object {
        private const val TAG = "GpuBilateralFilter"

        private val HORIZONTAL_SHADER = """
            uniform shader inputImage;
            uniform float2 iResolution;
            uniform float spatialSigma;
            uniform float rangeSigma;
            const int KERNEL_RADIUS = 42;

            ${BitmapUtils.AGSL_DECODE_DEPTH}
            ${BitmapUtils.AGSL_ENCODE_DEPTH}

            half4 main(float2 fragCoord) {
                float centerDepth = decodeDepth(inputImage.eval(fragCoord));
                float spatialDenom = 2.0 * spatialSigma * spatialSigma;
                float rangeDenom = 2.0 * rangeSigma * rangeSigma;

                float weightedSum = 0.0;
                float totalWeight = 0.0;

                for (int i = -KERNEL_RADIUS; i <= KERNEL_RADIUS; i += 1) {
                    float2 sc = float2(
                        clamp(fragCoord.x + float(i), 0.0, iResolution.x - 1.0),
                        fragCoord.y
                    );
                    float sampledDepth = decodeDepth(inputImage.eval(sc));
                    float spatialW = exp(-float(i * i) / spatialDenom);
                    float depthDiff = sampledDepth - centerDepth;
                    float rangeW = exp(-(depthDiff * depthDiff) / rangeDenom);
                    float w = spatialW * rangeW;
                    weightedSum += w * sampledDepth;
                    totalWeight += w;
                }

                float result = weightedSum / max(totalWeight, 0.0001);
                return encodeDepth(result);
            }
        """

        private val VERTICAL_SHADER = """
            uniform shader inputImage;
            uniform float2 iResolution;
            uniform float spatialSigma;
            uniform float rangeSigma;
            const int KERNEL_RADIUS = 42;

            ${BitmapUtils.AGSL_DECODE_DEPTH}
            ${BitmapUtils.AGSL_ENCODE_DEPTH}

            half4 main(float2 fragCoord) {
                float centerDepth = decodeDepth(inputImage.eval(fragCoord));
                float spatialDenom = 2.0 * spatialSigma * spatialSigma;
                float rangeDenom = 2.0 * rangeSigma * rangeSigma;

                float weightedSum = 0.0;
                float totalWeight = 0.0;

                for (int i = -KERNEL_RADIUS; i <= KERNEL_RADIUS; i += 1) {
                    float2 sc = float2(
                        fragCoord.x,
                        clamp(fragCoord.y + float(i), 0.0, iResolution.y - 1.0)
                    );
                    float sampledDepth = decodeDepth(inputImage.eval(sc));
                    float spatialW = exp(-float(i * i) / spatialDenom);
                    float depthDiff = sampledDepth - centerDepth;
                    float rangeW = exp(-(depthDiff * depthDiff) / rangeDenom);
                    float w = spatialW * rangeW;
                    weightedSum += w * sampledDepth;
                    totalWeight += w;
                }

                float result = weightedSum / max(totalWeight, 0.0001);
                return encodeDepth(result);
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
            Log.w(TAG, "AGSL shader compilation failed, falling back to CPU: ${e.message}")
            gpuAvailable = false
        }
    }

    /**
     * Run the separable bilateral filter on the GPU.
     * Input/output are FloatArrays of depth values (0-1), row-major, width x height.
     */
    fun filter(
        depth: FloatArray,
        width: Int,
        height: Int,
        spatialSigma: Float,
        depthSigma: Float
    ): FloatArray {
        if (!gpuAvailable) throw IllegalStateException("GPU not available")

        val t0 = System.currentTimeMillis()
        val inputBitmap = BitmapUtils.floatArrayToPackedBitmap(depth, width, height)
        val t1 = System.currentTimeMillis()
        Log.d(TAG, "24-bit encode: ${t1-t0}ms")

        // Pass 1: Horizontal
        val hShader = horizontalShader!!
        hShader.setFloatUniform("iResolution", width.toFloat(), height.toFloat())
        hShader.setFloatUniform("spatialSigma", spatialSigma)
        hShader.setFloatUniform("rangeSigma", depthSigma)
        val inputShader = BitmapShader(inputBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        inputShader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        hShader.setInputBuffer("inputImage", inputShader)

        val intermediate = renderShader(hShader, width, height)
        val t2 = System.currentTimeMillis()
        Log.d(TAG, "GPU horizontal: ${t2-t1}ms")
        inputBitmap.recycle()

        // Pass 2: Vertical
        val vShader = verticalShader!!
        vShader.setFloatUniform("iResolution", width.toFloat(), height.toFloat())
        vShader.setFloatUniform("spatialSigma", spatialSigma)
        vShader.setFloatUniform("rangeSigma", depthSigma)
        val intermediateShader = BitmapShader(intermediate, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        intermediateShader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        vShader.setInputBuffer("inputImage", intermediateShader)

        val output = renderShader(vShader, width, height)
        val t3 = System.currentTimeMillis()
        Log.d(TAG, "GPU vertical: ${t3-t2}ms")
        intermediate.recycle()

        val result = BitmapUtils.packedBitmapToFloatArray(output)
        val t4 = System.currentTimeMillis()
        Log.d(TAG, "24-bit decode: ${t4-t3}ms, total: ${t4-t0}ms")
        output.recycle()

        return result
    }

    private fun renderShader(shader: RuntimeShader, width: Int, height: Int): Bitmap {
        val imageReader = ImageReader.newInstance(
            width, height,
            PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )

        val renderNode = RenderNode("BilateralPass")
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
