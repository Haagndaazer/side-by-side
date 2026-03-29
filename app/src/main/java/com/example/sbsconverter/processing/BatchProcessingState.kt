package com.example.sbsconverter.processing

import android.net.Uri
import com.example.sbsconverter.model.AlignmentConfig
import com.example.sbsconverter.model.BatchItem
import com.example.sbsconverter.model.BatchItemStatus
import com.example.sbsconverter.model.BatchMode
import com.example.sbsconverter.model.BatchPairItem
import com.example.sbsconverter.util.CalibrationInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

class BatchProcessingState {

    private val _nextId = AtomicLong(0)

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    private val _batchMode = MutableStateFlow(BatchMode.AUTO_3D)
    val batchMode: StateFlow<BatchMode> = _batchMode

    private val _items = MutableStateFlow<List<BatchItem>>(emptyList())
    val items: StateFlow<List<BatchItem>> = _items

    private val _pairItems = MutableStateFlow<List<BatchPairItem>>(emptyList())
    val pairItems: StateFlow<List<BatchPairItem>> = _pairItems

    private val _alignmentConfig = MutableStateFlow(AlignmentConfig())
    val alignmentConfig: StateFlow<AlignmentConfig> = _alignmentConfig

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex

    private val _completedCount = MutableStateFlow(0)
    val completedCount: StateFlow<Int> = _completedCount

    private val _errorCount = MutableStateFlow(0)
    val errorCount: StateFlow<Int> = _errorCount

    private val _oddImageWarning = MutableStateFlow(false)
    val oddImageWarning: StateFlow<Boolean> = _oddImageWarning

    fun nextId(): Long = _nextId.getAndIncrement()

    fun setProcessing(value: Boolean) {
        _isProcessing.value = value
    }

    fun setBatchMode(mode: BatchMode) {
        _batchMode.value = mode
    }

    fun setItems(items: List<BatchItem>) {
        _items.value = items
    }

    fun setPairItems(items: List<BatchPairItem>) {
        _pairItems.value = items
    }

    fun setAlignmentConfig(config: AlignmentConfig) {
        _alignmentConfig.value = config
    }

    fun setCurrentIndex(index: Int) {
        _currentIndex.value = index
    }

    fun setOddImageWarning(value: Boolean) {
        _oddImageWarning.value = value
    }

    fun updateItemStatus(id: Long, status: BatchItemStatus, errorMessage: String? = null, resultUri: Uri? = null) {
        _items.value = _items.value.map {
            if (it.id == id) it.copy(status = status, errorMessage = errorMessage, resultUri = resultUri)
            else it
        }
    }

    fun updateItemCalibrationInfo(id: Long, info: CalibrationInfo?) {
        _items.value = _items.value.map {
            if (it.id == id) it.copy(calibrationInfo = info) else it
        }
    }

    fun updatePairCalibrationInfo(id: Long, info: CalibrationInfo?) {
        _pairItems.value = _pairItems.value.map {
            if (it.id == id) it.copy(calibrationInfo = info) else it
        }
    }

    fun updatePairStatus(id: Long, status: BatchItemStatus, errorMessage: String? = null, resultUri: Uri? = null) {
        _pairItems.value = _pairItems.value.map {
            if (it.id == id) it.copy(status = status, errorMessage = errorMessage, resultUri = resultUri)
            else it
        }
    }

    fun incrementCompleted() {
        _completedCount.value++
    }

    fun incrementErrors() {
        _errorCount.value++
    }

    fun revertProcessingItems() {
        _items.value = _items.value.map {
            if (it.status == BatchItemStatus.PROCESSING) it.copy(status = BatchItemStatus.PENDING)
            else it
        }
        _pairItems.value = _pairItems.value.map {
            if (it.status == BatchItemStatus.PROCESSING) it.copy(status = BatchItemStatus.PENDING)
            else it
        }
    }

    fun reset() {
        _isProcessing.value = false
        _currentIndex.value = -1
        _completedCount.value = 0
        _errorCount.value = 0
        _items.value = emptyList()
        _pairItems.value = emptyList()
        _oddImageWarning.value = false
    }
}
