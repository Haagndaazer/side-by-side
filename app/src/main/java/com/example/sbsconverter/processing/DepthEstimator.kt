package com.example.sbsconverter.processing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer

class DepthEstimator(private val modelPath: String) : Closeable {

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    val isInitialized: Boolean
        get() = ortSession != null

    fun initialize() {
        val env = OrtEnvironment.getEnvironment()
        ortEnvironment = env

        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(
                minOf(4, Runtime.getRuntime().availableProcessors())
            )
            // Use NO_OPT — the FP16 model has pre-fused nodes (SimplifiedLayerNormFusion)
            // that conflict with ONNX Runtime's own graph optimization passes
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
        }

        ortSession = env.createSession(modelPath, options)
    }

    fun estimateDepth(inputTensor: FloatBuffer): FloatArray {
        val env = ortEnvironment ?: throw IllegalStateException("DepthEstimator not initialized")
        val session = ortSession ?: throw IllegalStateException("DepthEstimator not initialized")

        val shape = longArrayOf(1, 3, 518, 518)
        val onnxTensor = OnnxTensor.createTensor(env, inputTensor, shape)

        val results = session.run(mapOf("pixel_values" to onnxTensor))

        val outputTensor = results.get("predicted_depth")
            .orElseThrow { RuntimeException("No predicted_depth output from model") }

        // Output shape is [1, 518, 518] — Java represents as float[1][518][518] = Array<Array<FloatArray>>
        @Suppress("UNCHECKED_CAST")
        val depthMap3d = outputTensor.value as Array<Array<FloatArray>>
        // Flatten [518][518] into a single FloatArray of length 518*518
        val depth2d = depthMap3d[0]
        val flatDepth = FloatArray(518 * 518)
        for (row in depth2d.indices) {
            System.arraycopy(depth2d[row], 0, flatDepth, row * 518, 518)
        }

        onnxTensor.close()
        results.close()

        return flatDepth
    }

    override fun close() {
        ortSession?.close()
        ortSession = null
        ortEnvironment?.close()
        ortEnvironment = null
    }
}
