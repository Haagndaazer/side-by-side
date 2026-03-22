package com.example.sbsconverter.ui.screens

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sbsconverter.SbsApplication
import com.example.sbsconverter.model.BatchItem
import com.example.sbsconverter.model.BatchItemStatus
import com.example.sbsconverter.model.ProcessingConfig
import com.example.sbsconverter.util.BitmapUtils
import com.example.sbsconverter.util.DepthAnalyzer
import com.example.sbsconverter.util.GalleryUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BatchViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SbsApplication

    val isModelReady: StateFlow<Boolean> = app.isModelReady
    val modelLoadProgress: StateFlow<Float> = app.modelLoadProgress

    private var nextId = 0L
    private var processingJob: Job? = null

    private val _items = MutableStateFlow<List<BatchItem>>(emptyList())
    val items: StateFlow<List<BatchItem>> = _items

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex

    private val _completedCount = MutableStateFlow(0)
    val completedCount: StateFlow<Int> = _completedCount

    private val _errorCount = MutableStateFlow(0)
    val errorCount: StateFlow<Int> = _errorCount

    fun onImagesSelected(uris: List<Uri>) {
        val context = getApplication<Application>()
        val existingUris = _items.value.map { it.uri }.toSet()
        val newItems = uris
            .filter { it !in existingUris }
            .map { uri ->
                BatchItem(
                    id = nextId++,
                    uri = uri,
                    displayName = getDisplayName(context, uri)
                )
            }
        _items.value = _items.value + newItems
    }

    fun removeItem(id: Long) {
        _items.value = _items.value.filter { it.id != id || it.status != BatchItemStatus.PENDING }
    }

    fun clearAll() {
        if (_isProcessing.value) return
        _items.value = emptyList()
        _completedCount.value = 0
        _errorCount.value = 0
    }

    fun startProcessing() {
        if (_isProcessing.value) return
        val estimator = app.depthEstimator ?: return
        val processor = app.imageProcessor
        val context = getApplication<Application>()

        processingJob = viewModelScope.launch {
            _isProcessing.value = true

            val itemsSnapshot = _items.value
            for (i in itemsSnapshot.indices) {
                if (!isActive) break
                val item = itemsSnapshot[i]
                if (item.status != BatchItemStatus.PENDING) continue

                _currentIndex.value = i
                updateItemStatus(item.id, BatchItemStatus.PROCESSING)

                var bitmap: android.graphics.Bitmap? = null
                try {
                    // 1. Load image
                    bitmap = withContext(Dispatchers.IO) {
                        BitmapUtils.loadBitmapFromUri(context, item.uri)
                    } ?: throw IllegalStateException("Failed to decode image")

                    // 2. Depth estimation
                    val rawDepth = withContext(Dispatchers.Default) {
                        val tensor = BitmapUtils.bitmapToFloatTensor(bitmap)
                        estimator.estimateDepth(tensor)
                    }

                    // 3. Auto-tune parameters
                    val normalized = BitmapUtils.normalizeDepthMap(rawDepth)
                    val autoParams = DepthAnalyzer.analyze(normalized)
                    val config = ProcessingConfig(
                        depthScale = autoParams.depthScale,
                        convergencePoint = autoParams.convergencePoint
                    )

                    // 4. Generate SBS
                    val result = withContext(Dispatchers.Default) {
                        processor.processImage(bitmap, rawDepth, config)
                    }

                    if (!isActive) {
                        result.sbsBitmap.recycle()
                        bitmap.recycle()
                        break
                    }

                    // 5. Save to gallery
                    val savedUri = withContext(Dispatchers.IO) {
                        GalleryUtils.saveBitmapToGallery(
                            context,
                            result.sbsBitmap,
                            "SBS_${item.displayName.substringBeforeLast(".")}_${System.currentTimeMillis()}"
                        )
                    }

                    // 6. Cleanup
                    result.sbsBitmap.recycle()
                    bitmap.recycle()
                    bitmap = null

                    updateItemStatus(item.id, BatchItemStatus.DONE, resultUri = savedUri)
                    _completedCount.value++
                } catch (e: Exception) {
                    bitmap?.recycle()
                    updateItemStatus(item.id, BatchItemStatus.ERROR, e.message)
                    _errorCount.value++
                }
            }

            _isProcessing.value = false
            _currentIndex.value = -1
        }
    }

    fun cancelProcessing() {
        processingJob?.cancel()
        processingJob = null
        _isProcessing.value = false

        // Reset current PROCESSING item back to PENDING
        _items.value = _items.value.map {
            if (it.status == BatchItemStatus.PROCESSING) it.copy(status = BatchItemStatus.PENDING)
            else it
        }
        _currentIndex.value = -1
    }

    private fun updateItemStatus(
        id: Long,
        status: BatchItemStatus,
        errorMessage: String? = null,
        resultUri: android.net.Uri? = null
    ) {
        _items.value = _items.value.map {
            if (it.id == id) it.copy(status = status, errorMessage = errorMessage, resultUri = resultUri)
            else it
        }
    }

    private fun getDisplayName(context: android.content.Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) return cursor.getString(nameIndex)
            }
        }
        return uri.lastPathSegment ?: "photo"
    }

    override fun onCleared() {
        processingJob?.cancel()
        super.onCleared()
    }
}
