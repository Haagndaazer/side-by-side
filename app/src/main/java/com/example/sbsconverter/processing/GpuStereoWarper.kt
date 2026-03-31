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

        /**
         * V2 warp shader: uses dense target-view depth for principled ghost detection.
         * Source depth drives the shift computation; target depth validates the result.
         * Produces soft alpha based on depth agreement instead of binary ghost threshold.
         */
        private val WARP_SHADER_V2 = """
            uniform shader sourceImage;
            uniform shader depthMap;
            uniform shader denseTargetDepth;
            uniform float2 imageSize;
            uniform float2 depthSize;
            uniform float maxDisparity;
            uniform float convergencePoint;
            uniform float direction;
            uniform float fadeWidth;
            uniform float depthTolerance;

            ${BitmapUtils.AGSL_DECODE_DEPTH}

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
                float2 normCoord = fragCoord / imageSize;
                float2 depthCoord = normCoord * (depthSize - 1.0);

                // Source depth drives the shift (same as V1)
                float depth = sampleDepth(depthCoord);
                float adjustedDepth = depth - convergencePoint;
                float baseShift = adjustedDepth * maxDisparity * direction;

                float edgeFade;
                if (direction < 0.0) {
                    edgeFade = clamp((imageSize.x - fragCoord.x) / fadeWidth, 0.0, 1.0);
                } else {
                    edgeFade = clamp(fragCoord.x / fadeWidth, 0.0, 1.0);
                }
                float totalShift = baseShift * edgeFade;

                float2 srcCoord = float2(
                    clamp(fragCoord.x - totalShift, 0.0, imageSize.x - 1.0),
                    fragCoord.y
                );

                float2 srcDepthCoord = (srcCoord / imageSize) * (depthSize - 1.0);
                float srcDepth = sampleDepth(srcDepthCoord);

                // V1 ghost check: source is significantly closer than output (foreground leak)
                bool v1Ghost = (srcDepth - depth > depthTolerance);

                // V2 check: source depth doesn't match expected target depth
                float expectedDepth = sampleTargetDepth(depthCoord);
                float depthError = abs(srcDepth - expectedDepth);
                bool v2Ghost = (depthError > depthTolerance * 5.0);

                // Only mark as hole when BOTH checks agree — prevents over-marking
                if (v1Ghost && v2Ghost) {
                    return half4(0.0, 0.0, 0.0, 0.0);
                }

                // Binary decision: if we get here, pixel is valid (full opacity)
                return sourceImage.eval(srcCoord);
            }
        """

        private val WARP_SHADER = """
            uniform shader sourceImage;
            uniform shader depthMap;
            uniform float2 imageSize;
            uniform float2 depthSize;
            uniform float maxDisparity;
            uniform float convergencePoint;
            uniform float direction;
            uniform float fadeWidth;
            uniform float ghostThreshold;

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

                // Depth discontinuity detection: compare depth at output position
                // vs depth at the backward-mapped source position. If the source is
                // significantly closer (foreground leaking into background), this is
                // a ghost pixel — mark as hole (alpha=0) for the hole filler.
                float2 srcDepthCoord = (srcCoord / imageSize) * (depthSize - 1.0);
                float srcDepth = sampleDepth(srcDepthCoord);
                if (srcDepth - depth > ghostThreshold) {
                    return half4(0.0, 0.0, 0.0, 0.0);
                }

                return sourceImage.eval(srcCoord);
            }
        """
    }

    private var shader: RuntimeShader? = null
    private var shaderV2: RuntimeShader? = null
    var gpuAvailable = false
        private set

    init {
        try {
            shader = RuntimeShader(WARP_SHADER)
            shaderV2 = RuntimeShader(WARP_SHADER_V2)
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
     * @param ghostThreshold    Depth difference threshold for ghost detection (0 = disabled)
     * @return Warped eye bitmap at source dimensions (may contain alpha=0 ghost holes)
     */
    fun warpEye(
        sourceBitmap: Bitmap,
        depthMap: FloatArray,
        isLeftEye: Boolean,
        maxDisparity: Float,
        convergencePoint: Float,
        edgeFadePercent: Float,
        ghostThreshold: Float = 0f
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
        s.setFloatUniform("ghostThreshold", ghostThreshold)

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

    /**
     * V2 warp using dense target-view depth for validation.
     *
     * @param sourceBitmap        The source photo
     * @param depthBitmap         24-bit packed source-view depth bitmap
     * @param denseTargetDepth    24-bit packed dense target-view depth bitmap (from depth warp + dilation)
     * @param isLeftEye           true for left eye, false for right
     * @param maxDisparity        Maximum pixel displacement
     * @param convergencePoint    Depth value at screen plane
     * @param edgeFadePercent     Fraction of width for edge fade ramp
     * @param depthTolerance      Depth error tolerance for soft compositing (~0.02)
     * @return Warped eye bitmap with alpha=0 for disocclusion holes
     */
    fun warpEyeV2(
        sourceBitmap: Bitmap,
        depthBitmap: Bitmap,
        denseTargetDepth: Bitmap,
        isLeftEye: Boolean,
        maxDisparity: Float,
        convergencePoint: Float,
        edgeFadePercent: Float,
        depthTolerance: Float = 0.02f
    ): Bitmap {
        if (!gpuAvailable) throw IllegalStateException("GPU not available")

        val w = sourceBitmap.width
        val h = sourceBitmap.height
        val depthSize = depthBitmap.width // Should be 1022

        val s = shaderV2!!
        s.setFloatUniform("imageSize", w.toFloat(), h.toFloat())
        s.setFloatUniform("depthSize", depthSize.toFloat(), depthSize.toFloat())
        s.setFloatUniform("maxDisparity", maxDisparity)
        s.setFloatUniform("convergencePoint", convergencePoint)
        s.setFloatUniform("direction", if (isLeftEye) -1f else 1f)
        s.setFloatUniform("fadeWidth", w * edgeFadePercent)
        s.setFloatUniform("depthTolerance", depthTolerance)

        // Source image — LINEAR for smooth color sampling
        val sourceShader = BitmapShader(sourceBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        sourceShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        s.setInputBuffer("sourceImage", sourceShader)

        // Source depth — NEAREST, shader decodes before manual bilinear
        val depthShader = BitmapShader(depthBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        depthShader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        s.setInputBuffer("depthMap", depthShader)

        // Dense target depth — NEAREST, same decode pattern
        val targetDepthShader = BitmapShader(denseTargetDepth, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        targetDepthShader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        s.setInputBuffer("denseTargetDepth", targetDepthShader)

        return renderShader(s, w, h)
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
        shaderV2 = null
    }
}
