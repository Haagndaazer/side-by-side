package com.example.sbsconverter.model

import android.net.Uri

enum class BatchItemStatus { PENDING, PROCESSING, DONE, ERROR }

data class BatchItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val status: BatchItemStatus = BatchItemStatus.PENDING,
    val errorMessage: String? = null,
    val resultUri: Uri? = null
)
