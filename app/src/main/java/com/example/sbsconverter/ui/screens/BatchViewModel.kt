package com.example.sbsconverter.ui.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sbsconverter.SbsApplication
import com.example.sbsconverter.model.BatchItem
import com.example.sbsconverter.model.BatchItemStatus
import com.example.sbsconverter.model.BatchMode
import com.example.sbsconverter.model.BatchPairItem
import com.example.sbsconverter.processing.BatchProcessingService
import com.example.sbsconverter.util.ExifDepthCalibrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BatchViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SbsApplication
    private val state = app.batchProcessingState

    val isModelReady: StateFlow<Boolean> = app.isModelReady
    val modelLoadProgress: StateFlow<Float> = app.modelLoadProgress

    val batchMode: StateFlow<BatchMode> = state.batchMode
    val items: StateFlow<List<BatchItem>> = state.items
    val pairItems: StateFlow<List<BatchPairItem>> = state.pairItems
    val isProcessing: StateFlow<Boolean> = state.isProcessing
    val currentIndex: StateFlow<Int> = state.currentIndex
    val completedCount: StateFlow<Int> = state.completedCount
    val errorCount: StateFlow<Int> = state.errorCount
    val oddImageWarning: StateFlow<Boolean> = state.oddImageWarning

    init {
        // Staleness detection: if state says processing but service isn't running, reset
        if (state.isProcessing.value && !BatchProcessingService.isServiceRunning) {
            state.revertProcessingItems()
            state.setProcessing(false)
            state.setCurrentIndex(-1)
        }
    }

    fun setBatchMode(mode: BatchMode) {
        if (state.isProcessing.value) return
        state.setBatchMode(mode)
        clearAll()
    }

    fun onImagesSelected(uris: List<Uri>) {
        when (state.batchMode.value) {
            BatchMode.AUTO_3D -> onAuto3DImagesSelected(uris)
            BatchMode.PAIR_ALIGN -> onPairImagesSelected(uris)
        }
    }

    private fun onAuto3DImagesSelected(uris: List<Uri>) {
        val context = getApplication<Application>()
        val existingUris = state.items.value.map { it.uri }.toSet()
        val newItems = uris
            .filter { it !in existingUris }
            .map { uri ->
                BatchItem(
                    id = state.nextId(),
                    uri = uri,
                    displayName = getDisplayName(context, uri)
                )
            }
        state.setItems(state.items.value + newItems)
        loadMetadataForItems(newItems)
    }

    private fun loadMetadataForItems(items: List<BatchItem>) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            for (item in items) {
                val info = withContext(Dispatchers.IO) {
                    ExifDepthCalibrator.readMetadata(context, item.uri)
                }
                if (info != null) state.updateItemCalibrationInfo(item.id, info)
            }
        }
    }

    private fun onPairImagesSelected(uris: List<Uri>) {
        val context = getApplication<Application>()
        state.setOddImageWarning(uris.size % 2 != 0)

        val pairs = uris.chunked(2).filter { it.size == 2 }.map { (left, right) ->
            BatchPairItem(
                id = state.nextId(),
                leftUri = left,
                rightUri = right,
                leftDisplayName = getDisplayName(context, left),
                rightDisplayName = getDisplayName(context, right)
            )
        }
        state.setPairItems(state.pairItems.value + pairs)
        loadMetadataForPairs(pairs)
    }

    private fun loadMetadataForPairs(pairs: List<BatchPairItem>) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            for (pair in pairs) {
                val info = withContext(Dispatchers.IO) {
                    ExifDepthCalibrator.readMetadata(context, pair.leftUri)
                }
                if (info != null) state.updatePairCalibrationInfo(pair.id, info)
            }
        }
    }

    fun removeItem(id: Long) {
        state.setItems(state.items.value.filter { it.id != id || it.status != BatchItemStatus.PENDING })
    }

    fun removePairItem(id: Long) {
        state.setPairItems(state.pairItems.value.filter { it.id != id || it.status != BatchItemStatus.PENDING })
    }

    fun clearAll() {
        if (state.isProcessing.value) return
        state.reset()
    }

    fun dismissOddWarning() {
        state.setOddImageWarning(false)
    }

    fun startProcessing() {
        if (state.isProcessing.value) return

        val action = when (state.batchMode.value) {
            BatchMode.AUTO_3D -> BatchProcessingService.ACTION_START_AUTO_3D
            BatchMode.PAIR_ALIGN -> BatchProcessingService.ACTION_START_PAIR_ALIGN
        }

        val intent = Intent(getApplication(), BatchProcessingService::class.java).apply {
            this.action = action
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun cancelProcessing() {
        val intent = Intent(getApplication(), BatchProcessingService::class.java).apply {
            action = BatchProcessingService.ACTION_CANCEL
        }
        getApplication<Application>().startService(intent)
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
}
