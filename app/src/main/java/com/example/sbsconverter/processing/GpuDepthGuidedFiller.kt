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
 * GPU-accelerated depth-guided hole filler (Disney pipeline Pass 5).
 *
 * Instead of grabbing the nearest opaque pixel (old GpuHoleFiller), this shader
 * computes where in source space each hole pixel should map based on the dense
 * target-view depth, then scans the source image for pixels with matching depth.
 *
 * This produces fills using actual background content at the correct depth,
 * avoiding the smearing artifacts of nearest-neighbor filling.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class GpuDepthGuidedFiller : Closeable {

    companion object {
        private const val TAG = "GpuDepthGuidedFiller"

        private val FILL_SHADER = """
            uniform shader warpedImage;
            uniform shader sourceImage;
            uniform shader depthMap;
            uniform shader denseTargetDepth;
            uniform float2 imageSize;
            uniform float2 depthSize;
            uniform float maxDisparity;
            uniform float convergencePoint;
            uniform float direction;
            uniform float scanDirection;
            uniform float depthMatchTolerance;
            const int MAX_SEARCH = 64;

            ${BitmapUtils.AGSL_DECODE_DEPTH}

            float sampleSourceDepth(float2 coord) {
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

            float sampleTargetDepth(float2 coord) {
                coord = clamp(coord, float2(0.0), depthSize - 1.0);
                float2 base = floor(coord);
                float2 f = coord - base;
                float2 next = min(base + 1.0, depthSize - 1.0);

                float d00 = decodeDepth(denseTargetDepth.eval(base + 0.5));
                float d10 = decodeDepth(denseTargetDepth.eval(float2(next.x, base.y) + 0.5));
                float d01 = decodeDepth(denseTargetDepth.eval(float2(base.x, next.y) + 0.5));
                float d11 = decodeDepth(denseTargetDepth.eval(next + 0.5));

                return mix(mix(d00, d10, f.x), mix(d01, d11, f.x), f.y);
            }

            half4 main(float2 fragCoord) {
                half4 center = warpedImage.eval(fragCoord);
                if (center.a > 0.5) return half4(center.rgb, 1.0);

                // Expected depth for this hole pixel from dense target depth
                float2 normCoord = fragCoord / imageSize;
                float2 depthCoord = normCoord * (depthSize - 1.0);
                float expectedDepth = sampleTargetDepth(depthCoord);

                // Compute where in source space this pixel would map with expectedDepth
                float adjustedDepth = expectedDepth - convergencePoint;
                float expectedShift = adjustedDepth * maxDisparity * direction;
                float2 expectedSrcCoord = float2(fragCoord.x - expectedShift, fragCoord.y);

                // Scan source image near expected position for best depth match
                float bestWeight = 0.0;
                half3 bestColor = half3(0.0);

                for (int i = -32; i <= 32; i++) {
                    float2 srcSample = float2(
                        clamp(expectedSrcCoord.x + float(i), 0.0, imageSize.x - 1.0),
                        expectedSrcCoord.y
                    );

                    float2 srcDepthCoord = (srcSample / imageSize) * (depthSize - 1.0);
                    float srcDepth = sampleSourceDepth(srcDepthCoord);
                    float depthDiff = abs(srcDepth - expectedDepth);
                    float weight = exp(-depthDiff * depthDiff / (2.0 * depthMatchTolerance * depthMatchTolerance));

                    // Weight by proximity to expected position
                    float distWeight = exp(-float(i * i) / 512.0);
                    weight *= distWeight;

                    if (weight > bestWeight) {
                        bestWeight = weight;
                        bestColor = sourceImage.eval(srcSample).rgb;
                    }
                    if (weight > 0.8) break;
                }

                if (bestWeight > 0.1) return half4(bestColor, 1.0);

                // Fallback: nearest opaque in warped image (same as old filler)
                for (int i = 1; i <= MAX_SEARCH; i++) {
                    float2 sc = float2(
                        clamp(fragCoord.x + float(i) * scanDirection, 0.0, imageSize.x - 1.0),
                        fragCoord.y
                    );
                    half4 s = warpedImage.eval(sc);
                    if (s.a > 0.5) return half4(s.rgb, 1.0);
                }

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
            Log.w(TAG, "AGSL depth-guided fill shader compilation failed: ${e.message}")
            gpuAvailable = false
        }
    }

    /**
     * Fill holes in a warped eye bitmap using depth-guided source sampling.
     *
     * @param warpedBitmap      Warped eye with alpha=0 holes (from V2 warp)
     * @param sourceBitmap      Original source photo
     * @param depthBitmap       24-bit packed source-view depth bitmap
     * @param denseTargetDepth  24-bit packed dense target-view depth bitmap
     * @param isLeftEye         Determines scan direction for fallback fill
     * @param maxDisparity      Maximum pixel displacement
     * @param convergencePoint  Depth value at screen plane
     * @param depthMatchTolerance  How closely source depth must match expected (~0.04)
     * @return Fully opaque bitmap with holes filled using depth-guided sampling
     */
    fun fill(
        warpedBitmap: Bitmap,
        sourceBitmap: Bitmap,
        depthBitmap: Bitmap,
        denseTargetDepth: Bitmap,
        isLeftEye: Boolean,
        maxDisparity: Float,
        convergencePoint: Float,
        depthMatchTolerance: Float = 0.04f
    ): Bitmap {
        if (!gpuAvailable) throw IllegalStateException("GPU not available")

        val w = warpedBitmap.width
        val h = warpedBitmap.height
        val depthSize = depthBitmap.width
        val t0 = System.currentTimeMillis()

        val s = shader!!
        s.setFloatUniform("imageSize", w.toFloat(), h.toFloat())
        s.setFloatUniform("depthSize", depthSize.toFloat(), depthSize.toFloat())
        s.setFloatUniform("maxDisparity", maxDisparity)
        s.setFloatUniform("convergencePoint", convergencePoint)
        s.setFloatUniform("direction", if (isLeftEye) -1f else 1f)
        s.setFloatUniform("scanDirection", if (isLeftEye) 1f else -1f)
        s.setFloatUniform("depthMatchTolerance", depthMatchTolerance)

        // Warped image — NEAREST to preserve alpha=0 hole boundaries
        val warpedShader = BitmapShader(warpedBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        warpedShader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        s.setInputBuffer("warpedImage", warpedShader)

        // Source image — LINEAR for smooth sampling
        val sourceShader = BitmapShader(sourceBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        sourceShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        s.setInputBuffer("sourceImage", sourceShader)

        // Source depth — NEAREST
        val depthShader = BitmapShader(depthBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        depthShader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        s.setInputBuffer("depthMap", depthShader)

        // Dense target depth — NEAREST
        val targetDepthShader = BitmapShader(denseTargetDepth, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        targetDepthShader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        s.setInputBuffer("denseTargetDepth", targetDepthShader)

        val result = renderShader(s, w, h)
        val t1 = System.currentTimeMillis()
        Log.d(TAG, "Depth-guided fill (${if (isLeftEye) "left" else "right"}): ${t1 - t0}ms")

        return result
    }

    private fun renderShader(shader: RuntimeShader, width: Int, height: Int): Bitmap {
        val imageReader = ImageReader.newInstance(
            width, height,
            PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )

        val renderNode = RenderNode("DepthGuidedFill")
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
