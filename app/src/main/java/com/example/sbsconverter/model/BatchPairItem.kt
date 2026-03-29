package com.example.sbsconverter.model

import android.net.Uri
import com.example.sbsconverter.util.CalibrationInfo

data class BatchPairItem(
    val id: Long,
    val leftUri: Uri,
    val rightUri: Uri,
    val leftDisplayName: String,
    val rightDisplayName: String,
    val status: BatchItemStatus = BatchItemStatus.PENDING,
    val errorMessage: String? = null,
    val resultUri: Uri? = null,
    val calibrationInfo: CalibrationInfo? = null
)
