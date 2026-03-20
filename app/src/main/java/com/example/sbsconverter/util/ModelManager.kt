package com.example.sbsconverter.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ModelManager(private val context: Context) {

    companion object {
        const val MODEL_FILENAME = "depth_anything_v2_vitb_756.onnx"
        private const val ASSET_NAME = "depth_anything_v2_vitb_756.onnx"
        private const val COPY_BUFFER_SIZE = 65536
    }

    fun getModelPath(): String {
        return File(context.filesDir, MODEL_FILENAME).absolutePath
    }

    fun isModelReady(): Boolean {
        val file = File(context.filesDir, MODEL_FILENAME)
        return file.exists() && file.length() > 0
    }

    suspend fun ensureModelReady(onProgress: (Float) -> Unit = {}) {
        if (isModelReady()) {
            onProgress(1f)
            return
        }
        withContext(Dispatchers.IO) {
            val outputFile = File(context.filesDir, MODEL_FILENAME)
            val assetFd = context.assets.openFd(ASSET_NAME)
            val totalBytes = assetFd.length
            assetFd.close()

            context.assets.open(ASSET_NAME).buffered(COPY_BUFFER_SIZE).use { input ->
                outputFile.outputStream().buffered(COPY_BUFFER_SIZE).use { output ->
                    var bytesCopied = 0L
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesCopied += bytesRead
                        if (totalBytes > 0) {
                            onProgress(bytesCopied.toFloat() / totalBytes.toFloat())
                        }
                    }
                }
            }
        }
    }
}
