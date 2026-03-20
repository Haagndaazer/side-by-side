package com.example.sbsconverter.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sbsconverter.model.ProcessingConfig
import com.example.sbsconverter.model.SbsResult
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

    // Keep source bitmap for processing (used by SbsWarper)
    private var sourceBitmap: Bitmap? = null

    // Keep backing bitmaps alive while Compose references them via ImageBitmap.
    // asImageBitmap() wraps the original — recycling it while Compose is drawing crashes.
    private var depthBacking: Bitmap? = null

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady

    private val _modelLoadProgress = MutableStateFlow(0f)
    val modelLoadProgress: StateFlow<Float> = _modelLoadProgress

    private val _originalImage = MutableStateFlow<ImageBitmap?>(null)
    val originalImage: StateFlow<ImageBitmap?> = _originalImage

    private val _depthImage = MutableStateFlow<ImageBitmap?>(null)
    val depthImage: StateFlow<ImageBitmap?> = _depthImage

    // Split processing states for distinct UI overlays
    private val _isEstimatingDepth = MutableStateFlow(false)
    val isEstimatingDepth: StateFlow<Boolean> = _isEstimatingDepth

    private val _isGeneratingSbs = MutableStateFlow(false)
    val isGeneratingSbs: StateFlow<Boolean> = _isGeneratingSbs

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _inferenceTimeMs = MutableStateFlow<Long?>(null)
    val inferenceTimeMs: StateFlow<Long?> = _inferenceTimeMs

    private val _processingConfig = MutableStateFlow(ProcessingConfig())
    val processingConfig: StateFlow<ProcessingConfig> = _processingConfig

    // SBS result with mesh data
    private var sbsResult: SbsResult? = null

    private val _hasSbsResult = MutableStateFlow(false)
    val hasSbsResult: StateFlow<Boolean> = _hasSbsResult

    private val _sbsImage = MutableStateFlow<ImageBitmap?>(null)
    val sbsImage: StateFlow<ImageBitmap?> = _sbsImage

    private val _sbsGenerationTimeMs = MutableStateFlow<Long?>(null)
    val sbsGenerationTimeMs: StateFlow<Long?> = _sbsGenerationTimeMs

    // Mesh visualization
    private val _meshVerts = MutableStateFlow<FloatArray?>(null)
    val meshVerts: StateFlow<FloatArray?> = _meshVerts

    private val _meshDimensions = MutableStateFlow<Pair<Int, Int>?>(null)
    val meshDimensions: StateFlow<Pair<Int, Int>?> = _meshDimensions

    private val _showMeshOverlay = MutableStateFlow(false)
    val showMeshOverlay: StateFlow<Boolean> = _showMeshOverlay

    private val _showWigglegram = MutableStateFlow(false)
    val showWigglegram: StateFlow<Boolean> = _showWigglegram

    // Image dimensions for mesh coordinate mapping
    private val _imageDimensions = MutableStateFlow<Pair<Int, Int>?>(null)
    val imageDimensions: StateFlow<Pair<Int, Int>?> = _imageDimensions

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
            resetForNewImage()
            _isEstimatingDepth.value = true
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    BitmapUtils.loadBitmapFromUri(context, uri)
                } ?: throw IllegalStateException("Failed to decode image")

                sourceBitmap = bitmap
                _originalImage.value = bitmap.asImageBitmap()
                _imageDimensions.value = Pair(bitmap.width, bitmap.height)
                runDepthEstimation(bitmap)
            } catch (e: Exception) {
                _errorMessage.value = "Error: ${e.message}"
            } finally {
                _isEstimatingDepth.value = false
            }
        }
    }

    private fun resetForNewImage() {
        // Clear UI state first so Compose stops referencing old bitmaps
        _errorMessage.value = null
        _depthImage.value = null
        _originalImage.value = null
        _imageDimensions.value = null
        _hasSbsResult.value = false
        _sbsImage.value = null
        _inferenceTimeMs.value = null
        _sbsGenerationTimeMs.value = null
        _meshVerts.value = null
        _meshDimensions.value = null
        _showMeshOverlay.value = false
        _showWigglegram.value = false
        cachedRawDepth = null

        // Recycle old backing bitmaps now that UI refs are cleared.
        // The previous sourceBitmap backed _originalImage, and depthBacking backed _depthImage.
        sourceBitmap?.recycle()
        sourceBitmap = null
        depthBacking?.recycle()
        depthBacking = null
        sbsResult?.sbsBitmap?.recycle()
        sbsResult = null
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

        // Recycle previous depth backing, then store new one
        depthBacking?.recycle()
        depthBacking = depthBmp
        _depthImage.value = depthBmp.asImageBitmap()
    }

    fun updateConfig(config: ProcessingConfig) {
        _processingConfig.value = config
    }

    fun generateSbs() {
        val bitmap = sourceBitmap ?: return
        val rawDepth = cachedRawDepth ?: return
        viewModelScope.launch {
            _isGeneratingSbs.value = true
            _errorMessage.value = null
            try {
                // Clear UI ref first, then recycle previous SBS result
                _sbsImage.value = null
                _hasSbsResult.value = false
                val oldSbs = sbsResult
                sbsResult = null
                oldSbs?.sbsBitmap?.recycle()

                val startTime = System.currentTimeMillis()
                val result = imageProcessor.processImage(bitmap, rawDepth, _processingConfig.value)
                _sbsGenerationTimeMs.value = System.currentTimeMillis() - startTime

                sbsResult = result
                _hasSbsResult.value = true
                _sbsImage.value = result.sbsBitmap.asImageBitmap()
                _meshVerts.value = result.meshVerts
                _meshDimensions.value = Pair(result.meshW, result.meshH)
            } catch (e: Exception) {
                _errorMessage.value = "SBS generation failed: ${e.message}"
            } finally {
                _isGeneratingSbs.value = false
            }
        }
    }

    fun toggleMeshOverlay() {
        _showMeshOverlay.value = !_showMeshOverlay.value
    }

    fun toggleWigglegram() {
        _showWigglegram.value = !_showWigglegram.value
    }

    fun saveSbsToGallery() {
        val sbs = sbsResult?.sbsBitmap ?: return
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
        sourceBitmap?.recycle()
        depthBacking?.recycle()
        sbsResult?.sbsBitmap?.recycle()
        super.onCleared()
    }
}
