package com.example.sbsconverter.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sbsconverter.SbsApplication
import com.example.sbsconverter.model.ProcessingConfig
import com.example.sbsconverter.model.SbsResult
import com.example.sbsconverter.processing.DepthEstimator
import com.example.sbsconverter.util.BitmapUtils
import com.example.sbsconverter.util.GalleryUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ViewMode { DEPTH_COMPARE, SBS_RESULT, WIGGLEGRAM }

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SbsApplication

    // Cached raw depth map — avoids re-running inference when tweaking settings
    private var cachedRawDepth: FloatArray? = null

    // Keep source bitmap and URI for processing
    private var sourceBitmap: Bitmap? = null
    private var sourceUri: android.net.Uri? = null

    // Keep backing bitmaps alive while Compose references them via ImageBitmap
    private var depthBacking: Bitmap? = null

    // Debounced auto-generation job
    private var autoGenerateJob: Job? = null

    // Track whether depth params changed since last SBS generation
    private var depthDirty = false

    val isModelReady: StateFlow<Boolean> = app.isModelReady
    val modelLoadProgress: StateFlow<Float> = app.modelLoadProgress
    val modelStatusText: StateFlow<String> = app.modelStatusText

    private val _originalImage = MutableStateFlow<ImageBitmap?>(null)
    val originalImage: StateFlow<ImageBitmap?> = _originalImage

    private val _depthImage = MutableStateFlow<ImageBitmap?>(null)
    val depthImage: StateFlow<ImageBitmap?> = _depthImage

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

    // View mode for main preview area
    private val _viewMode = MutableStateFlow(ViewMode.DEPTH_COMPARE)
    val viewMode: StateFlow<ViewMode> = _viewMode

    // Normalized depth map for convergence visualization overlay
    private val _normalizedDepth = MutableStateFlow<FloatArray?>(null)
    val normalizedDepth: StateFlow<FloatArray?> = _normalizedDepth

    // Image dimensions for mesh coordinate mapping
    private val _imageDimensions = MutableStateFlow<Pair<Int, Int>?>(null)
    val imageDimensions: StateFlow<Pair<Int, Int>?> = _imageDimensions

    val isBatchProcessing: StateFlow<Boolean> = app.batchProcessingState.isProcessing

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

    private val _saveSuccess = MutableStateFlow<Boolean?>(null)
    val saveSuccess: StateFlow<Boolean?> = _saveSuccess

    fun onImageSelected(uri: Uri) {
        if (app.batchProcessingState.isProcessing.value) return
        val context = getApplication<Application>()
        viewModelScope.launch {
            resetForNewImage()
            _isEstimatingDepth.value = true
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    BitmapUtils.loadBitmapFromUri(context, uri)
                } ?: throw IllegalStateException("Failed to decode image")

                sourceBitmap = bitmap
                sourceUri = uri
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
        autoGenerateJob?.cancel()
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
        _viewMode.value = ViewMode.DEPTH_COMPARE
        _normalizedDepth.value = null
        cachedRawDepth = null

        sourceBitmap?.recycle()
        sourceBitmap = null
        sourceUri = null
        depthBacking?.recycle()
        depthBacking = null
        sbsResult?.sbsBitmap?.recycle()
        sbsResult = null
    }

    private suspend fun runDepthEstimation(bitmap: Bitmap) {
        val estimator = app.depthEstimator ?: throw IllegalStateException("Model not ready")
        val startTime = System.currentTimeMillis()

        val depthMap = withContext(Dispatchers.Default) {
            val tensor = BitmapUtils.bitmapToFloatTensor(bitmap)
            estimator.estimateDepth(tensor)
        }

        cachedRawDepth = depthMap

        val normalized = withContext(Dispatchers.Default) {
            BitmapUtils.normalizeDepthMap(depthMap)
        }
        _normalizedDepth.value = normalized

        val depthBmp = withContext(Dispatchers.Default) {
            BitmapUtils.depthToGrayscaleBitmap(depthMap, bitmap.width, bitmap.height)
        }

        _inferenceTimeMs.value = System.currentTimeMillis() - startTime

        depthBacking?.recycle()
        depthBacking = depthBmp
        _depthImage.value = depthBmp.asImageBitmap()

        // Auto-generate SBS immediately after depth estimation
        generateSbs()
    }

    fun updateConfig(config: ProcessingConfig) {
        _processingConfig.value = config
    }

    fun onSliderFinished() {
        if (cachedRawDepth != null && sourceBitmap != null) {
            autoGenerateJob?.cancel()
            autoGenerateJob = viewModelScope.launch {
                generateSbs()
            }
        }
    }

    fun generateSbs() {
        if (app.batchProcessingState.isProcessing.value) return
        val bitmap = sourceBitmap ?: return
        val rawDepth = cachedRawDepth ?: return
        viewModelScope.launch {
            _isGeneratingSbs.value = true
            _errorMessage.value = null
            try {
                _sbsImage.value = null
                _hasSbsResult.value = false
                val oldSbs = sbsResult
                sbsResult = null
                oldSbs?.sbsBitmap?.recycle()

                val startTime = System.currentTimeMillis()
                val result = app.imageProcessor.processImage(bitmap, rawDepth, _processingConfig.value)

                if (!isActive) {
                    result.sbsBitmap.recycle()
                    return@launch
                }

                _sbsGenerationTimeMs.value = System.currentTimeMillis() - startTime

                sbsResult = result
                _hasSbsResult.value = true
                depthDirty = false
                _sbsImage.value = result.sbsBitmap.asImageBitmap()

                if (_viewMode.value == ViewMode.DEPTH_COMPARE) {
                    _viewMode.value = ViewMode.SBS_RESULT
                }
            } catch (e: Exception) {
                _errorMessage.value = "SBS generation failed: ${e.message}"
            } finally {
                _isGeneratingSbs.value = false
            }
        }
    }

    fun setViewMode(mode: ViewMode) {
        _viewMode.value = mode
        if (depthDirty && (mode == ViewMode.SBS_RESULT || mode == ViewMode.WIGGLEGRAM)) {
            generateSbs()
        }
    }

    fun toggleMeshOverlay() {
        _showMeshOverlay.value = !_showMeshOverlay.value
    }

    fun saveSbsToGallery() {
        val sbs = sbsResult?.sbsBitmap ?: return
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val dateTaken = sourceUri?.let { GalleryUtils.getDateTaken(getApplication(), it) }
                val uri = GalleryUtils.saveBitmapToGallery(getApplication(), sbs, dateTakenMs = dateTaken)
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
        autoGenerateJob?.cancel()
        sourceBitmap?.recycle()
        depthBacking?.recycle()
        sbsResult?.sbsBitmap?.recycle()
        super.onCleared()
    }
}
