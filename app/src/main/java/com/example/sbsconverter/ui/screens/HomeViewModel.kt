package com.example.sbsconverter.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sbsconverter.model.ProcessingConfig
import com.example.sbsconverter.processing.DepthEstimator
import com.example.sbsconverter.processing.ImageProcessor
import com.example.sbsconverter.util.BitmapUtils
import com.example.sbsconverter.util.GalleryUtils
import com.example.sbsconverter.util.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val modelManager = ModelManager(application)
    private var depthEstimator: DepthEstimator? = null
    private val imageProcessor = ImageProcessor()

    // Cached raw depth map (518x518) — avoids re-running inference when tweaking settings
    private var cachedRawDepth: FloatArray? = null

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady

    private val _modelLoadProgress = MutableStateFlow(0f)
    val modelLoadProgress: StateFlow<Float> = _modelLoadProgress

    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap: StateFlow<Bitmap?> = _originalBitmap

    private val _depthBitmap = MutableStateFlow<Bitmap?>(null)
    val depthBitmap: StateFlow<Bitmap?> = _depthBitmap

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _inferenceTimeMs = MutableStateFlow<Long?>(null)
    val inferenceTimeMs: StateFlow<Long?> = _inferenceTimeMs

    private val _processingConfig = MutableStateFlow(ProcessingConfig())
    val processingConfig: StateFlow<ProcessingConfig> = _processingConfig

    private val _sbsBitmap = MutableStateFlow<Bitmap?>(null)
    val sbsBitmap: StateFlow<Bitmap?> = _sbsBitmap

    private val _sbsGenerationTimeMs = MutableStateFlow<Long?>(null)
    val sbsGenerationTimeMs: StateFlow<Long?> = _sbsGenerationTimeMs

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

    private val _saveSuccess = MutableStateFlow<Boolean?>(null)
    val saveSuccess: StateFlow<Boolean?> = _saveSuccess

    init {
        viewModelScope.launch {
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
                _errorMessage.value = "Failed to load model: ${e.message}"
            }
        }
    }

    fun onImageSelected(uri: Uri) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            _isProcessing.value = true
            _errorMessage.value = null
            _depthBitmap.value?.recycle()
            _depthBitmap.value = null
            _sbsBitmap.value?.recycle()
            _sbsBitmap.value = null
            _inferenceTimeMs.value = null
            _sbsGenerationTimeMs.value = null
            cachedRawDepth = null
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    BitmapUtils.loadBitmapFromUri(context, uri)
                } ?: throw IllegalStateException("Failed to decode image")

                _originalBitmap.value = bitmap
                runDepthEstimation(bitmap)
            } catch (e: Exception) {
                _errorMessage.value = "Error: ${e.message}"
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun onTestImageSelected(assetPath: String) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            _isProcessing.value = true
            _errorMessage.value = null
            _depthBitmap.value?.recycle()
            _depthBitmap.value = null
            _sbsBitmap.value?.recycle()
            _sbsBitmap.value = null
            _inferenceTimeMs.value = null
            _sbsGenerationTimeMs.value = null
            cachedRawDepth = null
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    BitmapUtils.loadBitmapFromAsset(context, assetPath)
                } ?: throw IllegalStateException("Failed to load test image")

                _originalBitmap.value = bitmap
                runDepthEstimation(bitmap)
            } catch (e: Exception) {
                _errorMessage.value = "Error: ${e.message}"
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private suspend fun runDepthEstimation(bitmap: Bitmap) {
        val estimator = depthEstimator ?: throw IllegalStateException("Model not ready")
        val startTime = System.currentTimeMillis()

        val depthMap = withContext(Dispatchers.Default) {
            val tensor = BitmapUtils.bitmapToFloatTensor(bitmap)
            estimator.estimateDepth(tensor)
        }

        cachedRawDepth = depthMap

        val depthBmp = withContext(Dispatchers.Default) {
            BitmapUtils.depthToGrayscaleBitmap(depthMap, bitmap.width, bitmap.height)
        }

        _inferenceTimeMs.value = System.currentTimeMillis() - startTime
        _depthBitmap.value = depthBmp
    }

    fun updateConfig(config: ProcessingConfig) {
        _processingConfig.value = config
    }

    fun generateSbs() {
        val bitmap = _originalBitmap.value ?: return
        val rawDepth = cachedRawDepth ?: return
        viewModelScope.launch {
            _isProcessing.value = true
            _errorMessage.value = null
            try {
                // Recycle previous SBS bitmap to prevent OOM
                _sbsBitmap.value?.recycle()
                _sbsBitmap.value = null

                val startTime = System.currentTimeMillis()
                val sbs = imageProcessor.processImage(bitmap, rawDepth, _processingConfig.value)
                _sbsGenerationTimeMs.value = System.currentTimeMillis() - startTime
                _sbsBitmap.value = sbs
            } catch (e: Exception) {
                _errorMessage.value = "SBS generation failed: ${e.message}"
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun saveSbsToGallery() {
        val sbs = _sbsBitmap.value ?: return
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val uri = GalleryUtils.saveBitmapToGallery(getApplication(), sbs)
                _saveSuccess.value = (uri != null)
            } catch (e: Exception) {
                _errorMessage.value = "Save failed: ${e.message}"
                _saveSuccess.value = false
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun clearSaveStatus() {
        _saveSuccess.value = null
    }

    override fun onCleared() {
        depthEstimator?.close()
        super.onCleared()
    }
}
