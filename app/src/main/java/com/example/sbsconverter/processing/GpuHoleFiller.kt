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
import java.io.Closeable

/**
 * GPU-accelerated hole filler for ghost-marked pixels in stereo warp output.
 *
 * After the warp shader marks ghost pixels with alpha=0, this shader fills
 * those holes by scanning horizontally for the nearest valid (alpha>0) pixel
 * in the background direction.
 *
 * Disocclusions always appear on one side of a foreground object:
 * - Left eye: disocclusions are to the RIGHT of foreground → scan RIGHT
 * - Right eye: disocclusions are to the LEFT of foreground → scan LEFT
 *
 * The scan direction is the opposite of the warp direction.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class GpuHoleFiller : Closeable {

    companion object {
        private const val TAG = "GpuHoleFiller"

        private val FILL_SHADER = """
            uniform shader warpedImage;
            uniform float2 imageSize;
            uniform float scanDirection;
            const int MAX_SEARCH = 64;

            half4 main(float2 fragCoord) {
                half4 center = warpedImage.eval(fragCoord);
                if (center.a > 0.5) return center;

                // Primary scan: background direction
                for (int i = 1; i <= MAX_SEARCH; i++) {
                    float2 sc = float2(
                        clamp(fragCoord.x + float(i) * scanDirection, 0.0, imageSize.x - 1.0),
                        fragCoord.y
                    );
                    half4 s = warpedImage.eval(sc);
                    if (s.a > 0.5) return half4(s.rgb, 1.0);
                }

                // Fallback: scan opposite direction
                for (int i = 1; i <= MAX_SEARCH; i++) {
                    float2 sc = float2(
                        clamp(fragCoord.x - float(i) * scanDirection, 0.0, imageSize.x - 1.0),
                        fragCoord.y
                    );
                    half4 s = warpedImage.eval(sc);
                    if (s.a > 0.5) return half4(s.rgb, 1.0);
                }

                // Last resort: use whatever color was there (premultiplied zero → black)
                return half4(0.0, 0.0, 0.0, 1.0);
            }
        """
    }

    private var shader: RuntimeShader? = null
    var gpuAvailable = false
        private set

    init {
        try {
            shader = RuntimeShader(FILL_SHADER)
            gpuAvailable = true
        } catch (e: Exception) {
            Log.w(TAG, "AGSL hole fill shader compilation failed: ${e.message}")
            gpuAvailable = false
        }
    }

    /**
     * Fill ghost-marked holes (alpha=0) in a warped eye bitmap.
     *
     * @param warpedBitmap  Warped eye output with alpha=0 ghost holes
     * @param isLeftEye     true for left eye, false for right (determines scan direction)
     * @return              Fully opaque bitmap with holes filled from background direction
     */
    fun fill(warpedBitmap: Bitmap, isLeftEye: Boolean): Bitmap {
        if (!gpuAvailable) throw IllegalStateException("GPU not available")

        val t0 = System.currentTimeMillis()
        val s = shader!!
        s.setFloatUniform("imageSize", warpedBitmap.width.toFloat(), warpedBitmap.height.toFloat())
        // Left eye warp direction is -1, so scan direction is +1 (right)
        // Right eye warp direction is +1, so scan direction is -1 (left)
        s.setFloatUniform("scanDirection", if (isLeftEye) 1f else -1f)

        val inputShader = BitmapShader(warpedBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        inputShader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        s.setInputBuffer("warpedImage", inputShader)

        val result = renderShader(s, warpedBitmap.width, warpedBitmap.height)
        val t1 = System.currentTimeMillis()
        Log.d(TAG, "Hole fill (${if (isLeftEye) "left" else "right"} eye): ${t1 - t0}ms")

        return result
    }

    private fun renderShader(shader: RuntimeShader, width: Int, height: Int): Bitmap {
        val imageReader = ImageReader.newInstance(
            width, height,
            PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )

        val renderNode = RenderNode("HoleFill")
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
