package com.example.sbsconverter.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sbsconverter.SbsApplication
import com.example.sbsconverter.model.AlignmentConfig
import com.example.sbsconverter.model.Arrangement
import com.example.sbsconverter.model.BatchItem
import com.example.sbsconverter.model.BatchItemStatus
import com.example.sbsconverter.model.BatchMode
import com.example.sbsconverter.model.BatchPairItem
import com.example.sbsconverter.model.ProcessingConfig
import com.example.sbsconverter.processing.StereoAligner
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

    // Mode
    private val _batchMode = MutableStateFlow(BatchMode.AUTO_3D)
    val batchMode: StateFlow<BatchMode> = _batchMode

    // Auto 3D items
    private val _items = MutableStateFlow<List<BatchItem>>(emptyList())
    val items: StateFlow<List<BatchItem>> = _items

    // Pair alignment items
    private val _pairItems = MutableStateFlow<List<BatchPairItem>>(emptyList())
    val pairItems: StateFlow<List<BatchPairItem>> = _pairItems

    private val _alignmentConfig = MutableStateFlow(AlignmentConfig())
    val alignmentConfig: StateFlow<AlignmentConfig> = _alignmentConfig

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex

    private val _completedCount = MutableStateFlow(0)
    val completedCount: StateFlow<Int> = _completedCount

    private val _errorCount = MutableStateFlow(0)
    val errorCount: StateFlow<Int> = _errorCount

    private val _oddImageWarning = MutableStateFlow(false)
    val oddImageWarning: StateFlow<Boolean> = _oddImageWarning

    fun setBatchMode(mode: BatchMode) {
        if (_isProcessing.value) return
        _batchMode.value = mode
        clearAll()
    }

    fun onImagesSelected(uris: List<Uri>) {
        when (_batchMode.value) {
            BatchMode.AUTO_3D -> onAuto3DImagesSelected(uris)
            BatchMode.PAIR_ALIGN -> onPairImagesSelected(uris)
        }
    }

    private fun onAuto3DImagesSelected(uris: List<Uri>) {
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

    private fun onPairImagesSelected(uris: List<Uri>) {
        val context = getApplication<Application>()
        _oddImageWarning.value = uris.size % 2 != 0

        val pairs = uris.chunked(2).filter { it.size == 2 }.map { (left, right) ->
            BatchPairItem(
                id = nextId++,
                leftUri = left,
                rightUri = right,
                leftDisplayName = getDisplayName(context, left),
                rightDisplayName = getDisplayName(context, right)
            )
        }
        _pairItems.value = _pairItems.value + pairs
    }

    fun removeItem(id: Long) {
        _items.value = _items.value.filter { it.id != id || it.status != BatchItemStatus.PENDING }
    }

    fun removePairItem(id: Long) {
        _pairItems.value = _pairItems.value.filter { it.id != id || it.status != BatchItemStatus.PENDING }
    }

    fun clearAll() {
        if (_isProcessing.value) return
        _items.value = emptyList()
        _pairItems.value = emptyList()
        _completedCount.value = 0
        _errorCount.value = 0
        _oddImageWarning.value = false
    }

    fun dismissOddWarning() {
        _oddImageWarning.value = false
    }

    fun startProcessing() {
        when (_batchMode.value) {
            BatchMode.AUTO_3D -> startAuto3DProcessing()
            BatchMode.PAIR_ALIGN -> startPairAlignProcessing()
        }
    }

    private fun startAuto3DProcessing() {
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

                var bitmap: Bitmap? = null
                try {
                    bitmap = withContext(Dispatchers.IO) {
                        BitmapUtils.loadBitmapFromUri(context, item.uri)
                    } ?: throw IllegalStateException("Failed to decode image")

                    val rawDepth = withContext(Dispatchers.Default) {
                        val tensor = BitmapUtils.bitmapToFloatTensor(bitmap)
                        estimator.estimateDepth(tensor)
                    }

                    val normalized = BitmapUtils.normalizeDepthMap(rawDepth)
                    val autoParams = DepthAnalyzer.analyze(normalized)
                    val config = ProcessingConfig(
                        depthScale = autoParams.depthScale,
                        convergencePoint = autoParams.convergencePoint
                    )

                    val result = withContext(Dispatchers.Default) {
                        processor.processImage(bitmap, rawDepth, config)
                    }

                    if (!isActive) {
                        result.sbsBitmap.recycle()
                        bitmap.recycle()
                        break
                    }

                    val savedUri = withContext(Dispatchers.IO) {
                        GalleryUtils.saveBitmapToGallery(
                            context, result.sbsBitmap,
                            "SBS_${item.displayName.substringBeforeLast(".")}_${System.currentTimeMillis()}"
                        )
                    }

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

    private fun startPairAlignProcessing() {
        if (_isProcessing.value) return
        val aligner = StereoAligner()
        val context = getApplication<Application>()

        processingJob = viewModelScope.launch {
            _isProcessing.value = true

            val snapshot = _pairItems.value
            for (i in snapshot.indices) {
                if (!isActive) break
                val pair = snapshot[i]
                if (pair.status != BatchItemStatus.PENDING) continue

                _currentIndex.value = i
                updatePairStatus(pair.id, BatchItemStatus.PROCESSING)

                var leftBitmap: Bitmap? = null
                var rightBitmap: Bitmap? = null
                try {
                    leftBitmap = withContext(Dispatchers.IO) {
                        BitmapUtils.loadBitmapFromUri(context, pair.leftUri)
                    } ?: throw IllegalStateException("Failed to decode left image")

                    rightBitmap = withContext(Dispatchers.IO) {
                        BitmapUtils.loadBitmapFromUri(context, pair.rightUri)
                    } ?: throw IllegalStateException("Failed to decode right image")

                    val alignResult = withContext(Dispatchers.Default) {
                        aligner.align(leftBitmap, rightBitmap, _alignmentConfig.value)
                    }

                    // Combine into SBS using both transformed images
                    val arrangement = _alignmentConfig.value.arrangement
                    val sbsBitmap = combinePairSbs(alignResult.transformedLeft, alignResult.transformedRight, arrangement)

                    // Cleanup intermediates
                    alignResult.transformedLeft.recycle()
                    alignResult.transformedRight.recycle()
                    leftBitmap.recycle()
                    leftBitmap = null
                    rightBitmap.recycle()
                    rightBitmap = null

                    if (!isActive) {
                        sbsBitmap.recycle()
                        break
                    }

                    val savedUri = withContext(Dispatchers.IO) {
                        GalleryUtils.saveBitmapToGallery(
                            context, sbsBitmap,
                            "SBS_PAIR_${pair.leftDisplayName.substringBeforeLast(".")}_${System.currentTimeMillis()}"
                        )
                    }
                    sbsBitmap.recycle()

                    updatePairStatus(pair.id, BatchItemStatus.DONE, resultUri = savedUri)
                    _completedCount.value++
                } catch (e: Exception) {
                    leftBitmap?.recycle()
                    rightBitmap?.recycle()
                    updatePairStatus(pair.id, BatchItemStatus.ERROR, e.message)
                    _errorCount.value++
                }
            }

            _isProcessing.value = false
            _currentIndex.value = -1
        }
    }

    private fun combinePairSbs(left: Bitmap, right: Bitmap, arrangement: Arrangement): Bitmap {
        val w = minOf(left.width, right.width)
        val h = minOf(left.height, right.height)
        val first = if (arrangement == Arrangement.CROSS_EYED) right else left
        val second = if (arrangement == Arrangement.CROSS_EYED) left else right
        val output = Bitmap.createBitmap(w * 2, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(first, 0f, 0f, null)
        canvas.drawBitmap(second, w.toFloat(), 0f, null)
        return output
    }

    fun cancelProcessing() {
        processingJob?.cancel()
        processingJob = null
        _isProcessing.value = false

        _items.value = _items.value.map {
            if (it.status == BatchItemStatus.PROCESSING) it.copy(status = BatchItemStatus.PENDING)
            else it
        }
        _pairItems.value = _pairItems.value.map {
            if (it.status == BatchItemStatus.PROCESSING) it.copy(status = BatchItemStatus.PENDING)
            else it
        }
        _currentIndex.value = -1
    }

    private fun updateItemStatus(id: Long, status: BatchItemStatus, errorMessage: String? = null, resultUri: Uri? = null) {
        _items.value = _items.value.map {
            if (it.id == id) it.copy(status = status, errorMessage = errorMessage, resultUri = resultUri)
            else it
        }
    }

    private fun updatePairStatus(id: Long, status: BatchItemStatus, errorMessage: String? = null, resultUri: Uri? = null) {
        _pairItems.value = _pairItems.value.map {
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
