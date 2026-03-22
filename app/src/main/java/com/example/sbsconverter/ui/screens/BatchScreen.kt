package com.example.sbsconverter.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.sbsconverter.model.BatchItem
import com.example.sbsconverter.model.BatchItemStatus
import com.example.sbsconverter.util.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScreen(
    viewModel: BatchViewModel,
    onNavigateBack: () -> Unit
) {
    val items by viewModel.items.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val completedCount by viewModel.completedCount.collectAsState()
    val errorCount by viewModel.errorCount.collectAsState()
    val isModelReady by viewModel.isModelReady.collectAsState()
    val modelLoadProgress by viewModel.modelLoadProgress.collectAsState()

    // Block back while processing
    BackHandler(enabled = isProcessing) { /* blocked */ }
    BackHandler(enabled = !isProcessing) { onNavigateBack() }

    val pickMultipleMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.onImagesSelected(uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Batch Convert") },
                navigationIcon = {
                    if (!isProcessing) {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text("Return to single image mode") } },
                            state = rememberTooltipState()
                        ) {
                            IconButton(
                                onClick = onNavigateBack,
                                modifier = Modifier.semantics { contentDescription = "Back button" }
                            ) {
                                Text("←", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!isModelReady) {
                // Model still loading
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Preparing model... ${(modelLoadProgress * 100).toInt()}%")
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { modelLoadProgress },
                            modifier = Modifier.fillMaxWidth(0.7f)
                        )
                    }
                }
            } else {
                // Summary card
                if (items.isNotEmpty()) {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Batch processing progress") } },
                        state = rememberTooltipState()
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .semantics { contentDescription = "Batch progress summary" }
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                val pendingCount = items.count { it.status == BatchItemStatus.PENDING }
                                Text(
                                    "${items.size} photos  |  $completedCount done  |  $errorCount errors  |  $pendingCount pending",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (isProcessing || completedCount > 0) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = {
                                            if (items.isEmpty()) 0f
                                            else (completedCount + errorCount).toFloat() / items.size
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }

                // Queue list
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (items.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Tap Select Photos to add images",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    items(items, key = { it.id }) { item ->
                        BatchItemRow(
                            item = item,
                            onRemove = { viewModel.removeItem(item.id) },
                            canRemove = !isProcessing && item.status == BatchItemStatus.PENDING
                        )
                    }
                }
            }

            // Bottom buttons
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Add photos from gallery") } },
                        state = rememberTooltipState()
                    ) {
                        Button(
                            onClick = {
                                pickMultipleMedia.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            enabled = isModelReady && !isProcessing,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = "Select photos button" }
                        ) { Text("Select Photos") }
                    }

                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = {
                            PlainTooltip {
                                Text(if (isProcessing) "Stop processing" else "Begin batch processing")
                            }
                        },
                        state = rememberTooltipState()
                    ) {
                        Button(
                            onClick = {
                                if (isProcessing) viewModel.cancelProcessing()
                                else viewModel.startProcessing()
                            },
                            enabled = isModelReady && (isProcessing || items.any { it.status == BatchItemStatus.PENDING }),
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription = if (isProcessing) "Cancel button" else "Start processing button"
                                }
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Cancel")
                            } else {
                                Text("Start")
                            }
                        }
                    }

                    if (!isProcessing && items.isNotEmpty()) {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text("Clear all items from queue") } },
                            state = rememberTooltipState()
                        ) {
                            Button(
                                onClick = { viewModel.clearAll() },
                                modifier = Modifier.semantics { contentDescription = "Clear all button" }
                            ) { Text("Clear") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchItemRow(
    item: BatchItem,
    onRemove: () -> Unit,
    canRemove: Boolean
) {
    val context = LocalContext.current

    // Load thumbnail asynchronously
    var thumbnail by remember(item.uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.uri) {
        val bmp = withContext(Dispatchers.IO) {
            BitmapUtils.loadThumbnail(context, item.uri, 128)
        }
        if (bmp != null) {
            thumbnail = bmp.asImageBitmap()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = when (item.status) {
                    BatchItemStatus.PENDING -> "Queued: ${item.displayName}"
                    BatchItemStatus.PROCESSING -> "Processing: ${item.displayName}"
                    BatchItemStatus.DONE -> "Completed: ${item.displayName}"
                    BatchItemStatus.ERROR -> "Error: ${item.displayName}"
                }
            }
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbnail != null) {
                        Image(
                            bitmap = thumbnail!!,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Name + status
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when (item.status) {
                            BatchItemStatus.PENDING -> "Pending"
                            BatchItemStatus.PROCESSING -> "Processing..."
                            BatchItemStatus.DONE -> "Saved"
                            BatchItemStatus.ERROR -> item.errorMessage ?: "Error"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (item.status) {
                            BatchItemStatus.DONE -> MaterialTheme.colorScheme.primary
                            BatchItemStatus.ERROR -> MaterialTheme.colorScheme.error
                            BatchItemStatus.PROCESSING -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                // Status icon / remove button
                when {
                    item.status == BatchItemStatus.PROCESSING -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    item.status == BatchItemStatus.DONE -> {
                        Text("✓", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    item.status == BatchItemStatus.ERROR -> {
                        Text("✗", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    }
                    canRemove -> {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text("Remove from queue") } },
                            state = rememberTooltipState()
                        ) {
                            IconButton(
                                onClick = onRemove,
                                modifier = Modifier
                                    .size(32.dp)
                                    .semantics { contentDescription = "Remove ${item.displayName}" }
                            ) {
                                Text("×", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }

            // Full-width SBS result preview for completed items
            if (item.status == BatchItemStatus.DONE && item.resultUri != null) {
                var resultImage by remember(item.resultUri) { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(item.resultUri) {
                    val bmp = withContext(Dispatchers.IO) {
                        BitmapUtils.loadThumbnail(context, item.resultUri, 1024)
                    }
                    if (bmp != null) {
                        resultImage = bmp.asImageBitmap()
                    }
                }
                resultImage?.let { img ->
                    Image(
                        bitmap = img,
                        contentDescription = "SBS result for ${item.displayName}",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                    )
                }
            }
        }
    }
}
