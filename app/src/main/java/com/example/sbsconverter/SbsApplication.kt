package com.example.sbsconverter

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.example.sbsconverter.processing.BatchProcessingState
import com.example.sbsconverter.processing.DepthEstimator
import com.example.sbsconverter.processing.ImageProcessor
import com.example.sbsconverter.util.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SbsApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var modelManager: ModelManager
        private set

    var depthEstimator: DepthEstimator? = null
        private set

    val imageProcessor = ImageProcessor()
    val batchProcessingState = BatchProcessingState()

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady

    private val _modelLoadProgress = MutableStateFlow(0f)
    val modelLoadProgress: StateFlow<Float> = _modelLoadProgress

    private val _modelStatusText = MutableStateFlow("Preparing model...")
    val modelStatusText: StateFlow<String> = _modelStatusText

    override fun onCreate() {
        super.onCreate()
        modelManager = ModelManager(this)

        val channel = NotificationChannel(
            CHANNEL_BATCH_PROCESSING,
            "Batch Processing",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        appScope.launch {
            try {
                _modelStatusText.value = "Copying model..."
                modelManager.ensureModelReady { progress ->
                    _modelLoadProgress.value = progress * 0.5f // first 50% is file copy
                }
                _modelLoadProgress.value = 0.5f
                _modelStatusText.value = "Optimizing model..."
                val estimator = DepthEstimator(modelManager.getModelPath())
                withContext(Dispatchers.Default) {
                    estimator.initialize()
                }
                _modelLoadProgress.value = 1f
                depthEstimator = estimator
                _isModelReady.value = true
            } catch (e: Exception) {
                android.util.Log.e("SbsApp", "Model load failed", e)
                _modelStatusText.value = "Failed to load model: ${e.message}"
            }
        }
    }

    companion object {
        const val CHANNEL_BATCH_PROCESSING = "batch_processing"
    }
}
