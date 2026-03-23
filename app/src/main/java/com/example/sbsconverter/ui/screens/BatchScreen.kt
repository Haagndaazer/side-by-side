package com.example.sbsconverter.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.example.sbsconverter.model.BatchMode
import com.example.sbsconverter.model.BatchPairItem
import com.example.sbsconverter.util.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScreen(
    viewModel: BatchViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToViewer: (android.net.Uri) -> Unit = {}
) {
    val batchMode by viewModel.batchMode.collectAsState()
    val items by viewModel.items.collectAsState()
    val pairItems by viewModel.pairItems.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val completedCount by viewModel.completedCount.collectAsState()
    val errorCount by viewModel.errorCount.collectAsState()
    val isModelReady by viewModel.isModelReady.collectAsState()
    val modelLoadProgress by viewModel.modelLoadProgress.collectAsState()
    val oddImageWarning by viewModel.oddImageWarning.collectAsState()

    BackHandler(enabled = isProcessing) { /* blocked */ }
    BackHandler(enabled = !isProcessing) { onNavigateBack() }

    val pickMultipleMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.onImagesSelected(uris)
    }

    // Determine if ready to work based on mode
    val canOperate = if (batchMode == BatchMode.AUTO_3D) isModelReady else true
    val totalCount = if (batchMode == BatchMode.AUTO_3D) items.size else pairItems.size
    val hasPending = if (batchMode == BatchMode.AUTO_3D)
        items.any { it.status == BatchItemStatus.PENDING }
    else
        pairItems.any { it.status == BatchItemStatus.PENDING }

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
            // Mode selector
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Switch between auto 3D from depth and stereo pair alignment") } },
                state = rememberTooltipState()
            ) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { contentDescription = "Batch mode selector" }
                ) {
                    SegmentedButton(
                        selected = batchMode == BatchMode.AUTO_3D,
                        onClick = { viewModel.setBatchMode(BatchMode.AUTO_3D) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        enabled = !isProcessing
                    ) { Text("Auto 3D") }
                    SegmentedButton(
                        selected = batchMode == BatchMode.PAIR_ALIGN,
                        onClick = { viewModel.setBatchMode(BatchMode.PAIR_ALIGN) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        enabled = !isProcessing
                    ) { Text("Pair Align") }
                }
            }

            // Model loading (only blocks Auto 3D mode)
            if (batchMode == BatchMode.AUTO_3D && !isModelReady) {
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
                // Odd image warning (pair mode)
                if (oddImageWarning) {
                    Text(
                        "Odd number of photos — last image was dropped",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                // Summary card
                if (totalCount > 0) {
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
                                val unitLabel = if (batchMode == BatchMode.PAIR_ALIGN) "pairs" else "photos"
                                Text(
                                    "$totalCount $unitLabel  |  $completedCount done  |  $errorCount errors",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (isProcessing || completedCount > 0) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = {
                                            if (totalCount == 0) 0f
                                            else (completedCount + errorCount).toFloat() / totalCount
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
                    if (totalCount == 0) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (batchMode == BatchMode.AUTO_3D)
                                        "Tap Select Photos to add images"
                                    else
                                        "Select photos in pairs (1st=left, 2nd=right)",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    when (batchMode) {
                        BatchMode.AUTO_3D -> {
                            items(items, key = { it.id }) { item ->
                                BatchItemRow(
                                    item = item,
                                    onRemove = { viewModel.removeItem(item.id) },
                                    canRemove = !isProcessing && item.status == BatchItemStatus.PENDING,
                                    onViewResult = item.resultUri?.let { uri -> { onNavigateToViewer(uri) } }
                                )
                            }
                        }
                        BatchMode.PAIR_ALIGN -> {
                            items(pairItems, key = { it.id }) { pair ->
                                BatchPairItemRow(
                                    pair = pair,
                                    onRemove = { viewModel.removePairItem(pair.id) },
                                    canRemove = !isProcessing && pair.status == BatchItemStatus.PENDING,
                                    onViewResult = pair.resultUri?.let { uri -> { onNavigateToViewer(uri) } }
                                )
                            }
                        }
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
                            enabled = canOperate && !isProcessing,
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
                            enabled = canOperate && (isProcessing || hasPending),
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

                    if (!isProcessing && totalCount > 0) {
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
    canRemove: Boolean,
    onViewResult: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var thumbnail by remember(item.uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.uri) {
        val bmp = withContext(Dispatchers.IO) { BitmapUtils.loadThumbnail(context, item.uri, 128) }
        if (bmp != null) thumbnail = bmp.asImageBitmap()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${item.status.name}: ${item.displayName}" }
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    thumbnail?.let {
                        Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StatusText(item.status, item.errorMessage)
                }
                StatusIcon(item.status, canRemove, onRemove, item.displayName)
            }

            // SBS result preview
            if (item.status == BatchItemStatus.DONE && item.resultUri != null) {
                ResultPreview(item.resultUri, item.displayName, onTap = onViewResult)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchPairItemRow(
    pair: BatchPairItem,
    onRemove: () -> Unit,
    canRemove: Boolean,
    onViewResult: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var leftThumb by remember(pair.leftUri) { mutableStateOf<ImageBitmap?>(null) }
    var rightThumb by remember(pair.rightUri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pair.leftUri) {
        val bmp = withContext(Dispatchers.IO) { BitmapUtils.loadThumbnail(context, pair.leftUri, 96) }
        if (bmp != null) leftThumb = bmp.asImageBitmap()
    }
    LaunchedEffect(pair.rightUri) {
        val bmp = withContext(Dispatchers.IO) { BitmapUtils.loadThumbnail(context, pair.rightUri, 96) }
        if (bmp != null) rightThumb = bmp.asImageBitmap()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${pair.status.name}: ${pair.leftDisplayName} + ${pair.rightDisplayName}" }
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Two thumbnails with "+" between
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    leftThumb?.let {
                        Image(bitmap = it, contentDescription = "Left eye", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                Text("+", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 4.dp))
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    rightThumb?.let {
                        Image(bitmap = it, contentDescription = "Right eye", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }

                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${pair.leftDisplayName.take(15)} + ${pair.rightDisplayName.take(15)}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    StatusText(pair.status, pair.errorMessage)
                }
                StatusIcon(pair.status, canRemove, onRemove,
                    "${pair.leftDisplayName} + ${pair.rightDisplayName}")
            }

            // SBS result preview
            if (pair.status == BatchItemStatus.DONE && pair.resultUri != null) {
                ResultPreview(pair.resultUri, pair.leftDisplayName, onTap = onViewResult)
            }
        }
    }
}

@Composable
private fun StatusText(status: BatchItemStatus, errorMessage: String?) {
    Text(
        text = when (status) {
            BatchItemStatus.PENDING -> "Pending"
            BatchItemStatus.PROCESSING -> "Processing..."
            BatchItemStatus.DONE -> "Saved"
            BatchItemStatus.ERROR -> errorMessage ?: "Error"
        },
        style = MaterialTheme.typography.bodySmall,
        color = when (status) {
            BatchItemStatus.DONE -> MaterialTheme.colorScheme.primary
            BatchItemStatus.ERROR -> MaterialTheme.colorScheme.error
            BatchItemStatus.PROCESSING -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusIcon(
    status: BatchItemStatus,
    canRemove: Boolean,
    onRemove: () -> Unit,
    displayName: String
) {
    when {
        status == BatchItemStatus.PROCESSING -> {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        status == BatchItemStatus.DONE -> {
            Text("✓", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        status == BatchItemStatus.ERROR -> {
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
                    modifier = Modifier.size(32.dp)
                        .semantics { contentDescription = "Remove $displayName" }
                ) {
                    Text("×", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun ResultPreview(resultUri: android.net.Uri, displayName: String, onTap: (() -> Unit)? = null) {
    val context = LocalContext.current
    var resultImage by remember(resultUri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(resultUri) {
        val bmp = withContext(Dispatchers.IO) {
            BitmapUtils.loadBitmapFromUri(context, resultUri)
        }
        if (bmp != null) resultImage = bmp.asImageBitmap()
    }
    resultImage?.let { img ->
        Image(
            bitmap = img,
            contentDescription = "SBS result for $displayName",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                .then(if (onTap != null) Modifier.clickable { onTap() } else Modifier)
        )
    }
}
