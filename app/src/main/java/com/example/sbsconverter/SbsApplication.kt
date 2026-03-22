package com.example.sbsconverter

import android.app.Application
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

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady

    private val _modelLoadProgress = MutableStateFlow(0f)
    val modelLoadProgress: StateFlow<Float> = _modelLoadProgress

    override fun onCreate() {
        super.onCreate()
        modelManager = ModelManager(this)

        appScope.launch {
            try {
                modelManager.ensureModelReady { progress ->
                    _modelLoadProgress.value = progress
                }
                val estimator = DepthEstimator(modelManager.getModelPath())
                withContext(Dispatchers.Default) {
                    estimator.initialize()
                }
                depthEstimator = estimator
                _isModelReady.value = true
            } catch (e: Exception) {
                // Model load failure is observed by ViewModels via isModelReady staying false
            }
        }
    }
}
