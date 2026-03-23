package com.example.sbsconverter.processing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer

class DepthEstimator(private val modelPath: String) : Closeable {

    companion object {
        const val MODEL_INPUT_SIZE = 770
    }

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
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }

        ortSession = env.createSession(modelPath, options)
    }

    fun estimateDepth(inputTensor: FloatBuffer): FloatArray {
        val env = ortEnvironment ?: throw IllegalStateException("DepthEstimator not initialized")
        val session = ortSession ?: throw IllegalStateException("DepthEstimator not initialized")

        val shape = longArrayOf(1, 3, MODEL_INPUT_SIZE.toLong(), MODEL_INPUT_SIZE.toLong())
        val onnxTensor = OnnxTensor.createTensor(env, inputTensor, shape)

        // fabio-sim export uses "image" / "depth" tensor names
        val results = session.run(mapOf("image" to onnxTensor))

        val outputTensor = results.get("depth")
            .orElseThrow { RuntimeException("No depth output from model") }

        // Output shape is [1, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE] — Java represents as Array<Array<FloatArray>>
        @Suppress("UNCHECKED_CAST")
        val depthMap3d = outputTensor.value as Array<Array<FloatArray>>
        val depth2d = depthMap3d[0]
        val flatDepth = FloatArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        for (row in depth2d.indices) {
            System.arraycopy(depth2d[row], 0, flatDepth, row * MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)
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
