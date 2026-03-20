package com.example.sbsconverter.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.sbsconverter.model.Arrangement as SbsArrangement
import com.example.sbsconverter.model.ProcessingConfig
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val isModelReady by viewModel.isModelReady.collectAsState()
    val modelLoadProgress by viewModel.modelLoadProgress.collectAsState()
    val originalImage by viewModel.originalImage.collectAsState()
    val depthImage by viewModel.depthImage.collectAsState()
    val isEstimatingDepth by viewModel.isEstimatingDepth.collectAsState()
    val isGeneratingSbs by viewModel.isGeneratingSbs.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val inferenceTimeMs by viewModel.inferenceTimeMs.collectAsState()
    val processingConfig by viewModel.processingConfig.collectAsState()
    val hasSbsResult by viewModel.hasSbsResult.collectAsState()
    val sbsImage by viewModel.sbsImage.collectAsState()
    val sbsGenerationTimeMs by viewModel.sbsGenerationTimeMs.collectAsState()
    val meshVerts by viewModel.meshVerts.collectAsState()
    val meshDimensions by viewModel.meshDimensions.collectAsState()
    val showMeshOverlay by viewModel.showMeshOverlay.collectAsState()
    val showWigglegram by viewModel.showWigglegram.collectAsState()
    val normalizedDepth by viewModel.normalizedDepth.collectAsState()
    val imageDimensions by viewModel.imageDimensions.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val saveSuccess by viewModel.saveSuccess.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.onImageSelected(it) }
    }

    // Show save feedback via snackbar
    LaunchedEffect(saveSuccess) {
        saveSuccess?.let { success ->
            snackbarHostState.showSnackbar(
                if (success) "Saved to Pictures/SBS Converter" else "Save failed"
            )
            viewModel.clearSaveStatus()
        }
    }

    val isAnyProcessing = isEstimatingDepth || isGeneratingSbs
    var isAdjustingConvergence by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // Auto-scroll to bottom when SBS result appears
    LaunchedEffect(sbsImage) {
        if (sbsImage != null) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Scrollable content area — image preview + SBS preview
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ImagePreviewArea(
                        isModelReady = isModelReady,
                        modelLoadProgress = modelLoadProgress,
                        originalImage = originalImage,
                        depthImage = depthImage,
                        isEstimatingDepth = isEstimatingDepth,
                        isGeneratingSbs = isGeneratingSbs,
                        errorMessage = errorMessage,
                        inferenceTimeMs = inferenceTimeMs,
                        sbsGenerationTimeMs = sbsGenerationTimeMs,
                        meshVerts = meshVerts,
                        meshDimensions = meshDimensions,
                        showMeshOverlay = showMeshOverlay,
                        showWigglegram = showWigglegram,
                        sbsImage = sbsImage,
                        imageDimensions = imageDimensions,
                        hasSbsResult = hasSbsResult,
                        isAdjustingConvergence = isAdjustingConvergence,
                        normalizedDepth = normalizedDepth,
                        convergencePoint = processingConfig.convergencePoint,
                        onToggleMesh = { viewModel.toggleMeshOverlay() },
                        onToggleWigglegram = { viewModel.toggleWigglegram() }
                    )
                }

                // SBS preview — shown after generation
                if (sbsImage != null) {
                    SbsPreview(sbsImage = sbsImage!!, arrangement = processingConfig.arrangement)
                }
            }

            // Pinned bottom control panel
            BottomControlPanel(
                config = processingConfig,
                onConfigChange = { viewModel.updateConfig(it) },
                onLoadImage = {
                    pickMedia.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onGenerate = { viewModel.generateSbs() },
                onSave = { viewModel.saveSbsToGallery() },
                isModelReady = isModelReady,
                hasDepth = depthImage != null,
                hasSbsResult = hasSbsResult,
                isAnyProcessing = isAnyProcessing,
                isSaving = isSaving,
                errorMessage = errorMessage,
                onConvergenceAdjusting = { isAdjustingConvergence = it }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImagePreviewArea(
    isModelReady: Boolean,
    modelLoadProgress: Float,
    originalImage: ImageBitmap?,
    depthImage: ImageBitmap?,
    isEstimatingDepth: Boolean,
    isGeneratingSbs: Boolean,
    errorMessage: String?,
    inferenceTimeMs: Long?,
    sbsGenerationTimeMs: Long?,
    meshVerts: FloatArray?,
    meshDimensions: Pair<Int, Int>?,
    showMeshOverlay: Boolean,
    showWigglegram: Boolean,
    sbsImage: ImageBitmap?,
    imageDimensions: Pair<Int, Int>?,
    hasSbsResult: Boolean,
    isAdjustingConvergence: Boolean,
    normalizedDepth: FloatArray?,
    convergencePoint: Float,
    onToggleMesh: () -> Unit,
    onToggleWigglegram: () -> Unit
) {
    if (!isModelReady) {
        ModelLoadingIndicator(modelLoadProgress, errorMessage)
        return
    }

    if (originalImage == null) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("No image loaded") } },
            state = rememberTooltipState()
        ) {
            Text(
                text = "Tap Load Image to get started",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { contentDescription = "Empty state placeholder" }
            )
        }
        return
    }

    val imageAspect = (imageDimensions?.first ?: originalImage.width).toFloat() /
            (imageDimensions?.second ?: originalImage.height).toFloat()

    Box(contentAlignment = Alignment.Center) {
        // Main preview: wigglegram OR reveal slider OR original
        if (showWigglegram && sbsImage != null) {
            WigglegramPreview(
                sbsImage = sbsImage,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(imageAspect)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else if (depthImage != null) {
            DepthRevealSlider(
                originalImage = originalImage,
                depthImage = depthImage,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            Image(
                bitmap = originalImage,
                contentDescription = "Original image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(imageAspect)
                    .clip(RoundedCornerShape(8.dp))
            )
        }

        // Mesh wireframe overlay
        if (showMeshOverlay && meshVerts != null && meshDimensions != null && imageDimensions != null) {
            MeshWireframeOverlay(
                verts = meshVerts,
                meshW = meshDimensions.first,
                meshH = meshDimensions.second,
                imageWidth = imageDimensions.first,
                imageHeight = imageDimensions.second,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(imageAspect)
            )
        }

        // Convergence visualization overlay (shown while dragging convergence slider)
        if (isAdjustingConvergence && normalizedDepth != null && depthImage != null) {
            ConvergenceOverlay(
                normalizedDepth = normalizedDepth,
                convergencePoint = convergencePoint,
                depthWidth = 756,
                depthHeight = 756,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(imageAspect)
                    .clip(RoundedCornerShape(8.dp))
            )
        }

        // Processing overlay (depth estimation)
        if (isEstimatingDepth) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(imageAspect)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Estimating depth...", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
            }
        }

        // Processing overlay (SBS generation)
        if (isGeneratingSbs) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(imageAspect)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Generating 3D...", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
            }
        }

        // Toggle buttons (bottom-right, only after SBS generation)
        if (hasSbsResult) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(imageAspect),
                contentAlignment = Alignment.BottomEnd
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Wigglegram toggle
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Toggle wigglegram preview") } },
                        state = rememberTooltipState()
                    ) {
                        IconButton(
                            onClick = onToggleWigglegram,
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    if (showWigglegram)
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                    else
                                        Color.Black.copy(alpha = 0.5f),
                                    CircleShape
                                )
                                .semantics { contentDescription = "Toggle wigglegram" }
                        ) {
                            // Left-right arrows icon
                            Canvas(modifier = Modifier.size(20.dp)) {
                                val iconColor = Color.White
                                val sw = 2.dp.toPx()
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val arrowLen = size.width * 0.35f
                                // Left arrow
                                drawLine(iconColor, Offset(cx - arrowLen, cy), Offset(cx - arrowLen / 3, cy - arrowLen / 2), sw, StrokeCap.Round)
                                drawLine(iconColor, Offset(cx - arrowLen, cy), Offset(cx - arrowLen / 3, cy + arrowLen / 2), sw, StrokeCap.Round)
                                // Right arrow
                                drawLine(iconColor, Offset(cx + arrowLen, cy), Offset(cx + arrowLen / 3, cy - arrowLen / 2), sw, StrokeCap.Round)
                                drawLine(iconColor, Offset(cx + arrowLen, cy), Offset(cx + arrowLen / 3, cy + arrowLen / 2), sw, StrokeCap.Round)
                            }
                        }
                    }

                    // Mesh toggle
                    if (meshVerts != null) {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text("Toggle mesh wireframe visualization") } },
                            state = rememberTooltipState()
                        ) {
                            IconButton(
                                onClick = onToggleMesh,
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(
                                        if (showMeshOverlay)
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                        else
                                            Color.Black.copy(alpha = 0.5f),
                                        CircleShape
                                    )
                                    .semantics { contentDescription = "Toggle mesh overlay" }
                            ) {
                                Canvas(modifier = Modifier.size(20.dp)) {
                                    val gridColor = Color.White
                                    val sw = 1.5.dp.toPx()
                                    val w = size.width
                                    val h = size.height
                                    for (i in 0..3) {
                                        val x = w * i / 3f
                                        val y = h * i / 3f
                                        drawLine(gridColor, Offset(x, 0f), Offset(x, h), sw)
                                        drawLine(gridColor, Offset(0f, y), Offset(w, y), sw)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Info badges (bottom-left)
        val infoText = buildString {
            inferenceTimeMs?.let { append("Depth: ${"%.1f".format(it / 1000f)}s") }
            sbsGenerationTimeMs?.let {
                if (isNotEmpty()) append("  |  ")
                append("SBS: ${"%.1f".format(it / 1000f)}s")
            }
        }
        if (infoText.isNotEmpty() && !isEstimatingDepth && !isGeneratingSbs) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(imageAspect),
                contentAlignment = Alignment.BottomStart
            ) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Processing times") } },
                    state = rememberTooltipState()
                ) {
                    Text(
                        text = infoText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .padding(8.dp)
                            .background(
                                Color.Black.copy(alpha = 0.5f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .semantics { contentDescription = "Processing times" }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelLoadingIndicator(progress: Float, errorMessage: String?) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("Loading model") } },
        state = rememberTooltipState()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.semantics { contentDescription = "Model loading section" }
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (errorMessage != null) errorMessage
                else "Preparing model... ${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodyLarge
            )
            if (errorMessage == null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(0.7f)
                )
            }
        }
    }
}

@Composable
private fun DepthRevealSlider(
    originalImage: ImageBitmap,
    depthImage: ImageBitmap,
    modifier: Modifier = Modifier
) {
    var progress by remember { mutableFloatStateOf(0.5f) }

    // Reset to center when images change
    LaunchedEffect(originalImage, depthImage) {
        progress = 0.5f
    }

    Box(
        modifier = modifier
            .aspectRatio(originalImage.width.toFloat() / originalImage.height.toFloat())
            .semantics { contentDescription = "Depth reveal slider" }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val position = event.changes.firstOrNull()?.position ?: continue
                        if (event.changes.any { it.pressed }) {
                            progress = (position.x / size.width).coerceIn(0f, 1f)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
    ) {
        // Bottom layer: depth image (full, unclipped)
        Image(
            bitmap = depthImage,
            contentDescription = "Depth map",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        // Top layer: original image (clipped from left to split point)
        Image(
            bitmap = originalImage,
            contentDescription = "Original image",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(
                        left = 0f,
                        top = 0f,
                        right = size.width * progress,
                        bottom = size.height
                    ) {
                        this@drawWithContent.drawContent()
                    }
                }
        )

        // Divider line + thumb
        Canvas(modifier = Modifier.fillMaxSize()) {
            val splitX = size.width * progress

            // Vertical divider line
            drawLine(
                color = Color.White,
                start = Offset(splitX, 0f),
                end = Offset(splitX, size.height),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Circular thumb
            val thumbCenter = Offset(splitX, size.height / 2f)
            drawCircle(
                color = Color.White,
                radius = 14.dp.toPx(),
                center = thumbCenter
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.3f),
                radius = 12.dp.toPx(),
                center = thumbCenter
            )

            // Arrow indicators on thumb
            val arrowSize = 6.dp.toPx()
            // Left arrow
            drawLine(
                color = Color.White,
                start = Offset(splitX - arrowSize, thumbCenter.y),
                end = Offset(splitX - arrowSize / 2, thumbCenter.y - arrowSize / 2),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.White,
                start = Offset(splitX - arrowSize, thumbCenter.y),
                end = Offset(splitX - arrowSize / 2, thumbCenter.y + arrowSize / 2),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
            // Right arrow
            drawLine(
                color = Color.White,
                start = Offset(splitX + arrowSize, thumbCenter.y),
                end = Offset(splitX + arrowSize / 2, thumbCenter.y - arrowSize / 2),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.White,
                start = Offset(splitX + arrowSize, thumbCenter.y),
                end = Offset(splitX + arrowSize / 2, thumbCenter.y + arrowSize / 2),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Labels
        Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Original",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Text(
                text = "Depth",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun WigglegramPreview(
    sbsImage: ImageBitmap,
    modifier: Modifier = Modifier,
    intervalMs: Long = 500L
) {
    // Alternate between left eye (first half) and right eye (second half)
    var showLeftEye by remember { mutableStateOf(true) }

    LaunchedEffect(sbsImage) {
        while (true) {
            delay(intervalMs)
            showLeftEye = !showLeftEye
        }
    }

    val halfWidth = sbsImage.width / 2
    val srcOffset = if (showLeftEye) IntOffset.Zero else IntOffset(halfWidth, 0)
    val srcSize = IntSize(halfWidth, sbsImage.height)

    Image(
        painter = BitmapPainter(sbsImage, srcOffset, srcSize),
        contentDescription = if (showLeftEye) "Left eye view" else "Right eye view",
        contentScale = ContentScale.Fit,
        modifier = modifier.semantics { contentDescription = "Wigglegram preview" }
    )
}

@Composable
private fun ConvergenceOverlay(
    normalizedDepth: FloatArray,
    convergencePoint: Float,
    depthWidth: Int,
    depthHeight: Int,
    modifier: Modifier = Modifier
) {
    val popOutColor = Color(0x400064FF) // semi-transparent blue = pops out
    val recedeColor = Color(0x40FF3200) // semi-transparent red = recedes
    val contourColor = Color(0xFFFFFF00) // yellow contour line

    Canvas(modifier = modifier.semantics { contentDescription = "Convergence visualization" }) {
        val scaleX = size.width / depthWidth
        val scaleY = size.height / depthHeight
        val contourThreshold = 0.02f // how close to convergence to draw contour
        val pixelW = scaleX.coerceAtLeast(1f)
        val pixelH = scaleY.coerceAtLeast(1f)

        // Sample at reduced resolution for performance (every 4th pixel)
        val step = 4
        for (y in 0 until depthHeight step step) {
            for (x in 0 until depthWidth step step) {
                val depth = normalizedDepth[y * depthWidth + x]
                val diff = depth - convergencePoint

                val color = if (diff > contourThreshold) {
                    popOutColor
                } else if (diff < -contourThreshold) {
                    recedeColor
                } else {
                    contourColor.copy(alpha = 0.7f)
                }

                drawRect(
                    color = color,
                    topLeft = Offset(x * scaleX, y * scaleY),
                    size = androidx.compose.ui.geometry.Size(pixelW * step, pixelH * step)
                )
            }
        }
    }
}

@Composable
private fun MeshWireframeOverlay(
    verts: FloatArray,
    meshW: Int,
    meshH: Int,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
    lineColor: Color = Color.Cyan.copy(alpha = 0.6f)
) {
    Canvas(
        modifier = modifier.semantics { contentDescription = "Mesh wireframe overlay" }
    ) {
        val scaleX = size.width / imageWidth
        val scaleY = size.height / imageHeight
        val strokeWidthPx = 1.dp.toPx()

        for (row in 0..meshH) {
            for (col in 0..meshW) {
                val idx = (row * (meshW + 1) + col) * 2
                val x = verts[idx] * scaleX
                val y = verts[idx + 1] * scaleY

                // Horizontal edge (connect to right neighbor)
                if (col < meshW) {
                    val idxRight = idx + 2
                    drawLine(
                        color = lineColor,
                        start = Offset(x, y),
                        end = Offset(verts[idxRight] * scaleX, verts[idxRight + 1] * scaleY),
                        strokeWidth = strokeWidthPx
                    )
                }

                // Vertical edge (connect to bottom neighbor)
                if (row < meshH) {
                    val idxBelow = ((row + 1) * (meshW + 1) + col) * 2
                    drawLine(
                        color = lineColor,
                        start = Offset(x, y),
                        end = Offset(verts[idxBelow] * scaleX, verts[idxBelow + 1] * scaleY),
                        strokeWidth = strokeWidthPx
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SbsPreview(sbsImage: ImageBitmap, arrangement: SbsArrangement) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("Side-by-side 3D preview") } },
        state = rememberTooltipState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .semantics { contentDescription = "SBS 3D preview" }
        ) {
            Text(
                "SBS 3D Result",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (arrangement == SbsArrangement.PARALLEL) "Parallel (wall-eyed)" else "Cross-eye",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Image(
                bitmap = sbsImage,
                contentDescription = "Side-by-side 3D result",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(sbsImage.width.toFloat() / sbsImage.height.toFloat())
                    .clip(RoundedCornerShape(6.dp))
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomControlPanel(
    config: ProcessingConfig,
    onConfigChange: (ProcessingConfig) -> Unit,
    onLoadImage: () -> Unit,
    onGenerate: () -> Unit,
    onSave: () -> Unit,
    isModelReady: Boolean,
    hasDepth: Boolean,
    hasSbsResult: Boolean,
    isAnyProcessing: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onConvergenceAdjusting: (Boolean) -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Bottom control panel" }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Error message
            errorMessage?.let {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Error message") } },
                    state = rememberTooltipState()
                ) {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Error message" }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 3D Strength slider
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Controls 3D depth intensity") } },
                state = rememberTooltipState()
            ) {
                Column(modifier = Modifier.semantics { contentDescription = "3D strength slider" }) {
                    Text(
                        "3D Strength: ${"%.1f".format(config.depthScale)}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = config.depthScale,
                        onValueChange = { onConfigChange(config.copy(depthScale = it)) },
                        valueRange = 0.5f..8f,
                        enabled = isModelReady && hasDepth && !isAnyProcessing
                    )
                }
            }

            // Convergence slider
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Sets which depth appears at screen level. Higher values bring more of the scene in front of the screen.") } },
                state = rememberTooltipState()
            ) {
                Column(modifier = Modifier.semantics { contentDescription = "Convergence point slider" }) {
                    Text(
                        "Convergence: ${(config.convergencePoint * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = config.convergencePoint,
                        onValueChange = {
                            onConvergenceAdjusting(true)
                            onConfigChange(config.copy(convergencePoint = it))
                        },
                        onValueChangeFinished = { onConvergenceAdjusting(false) },
                        valueRange = 0f..1f,
                        enabled = isModelReady && hasDepth && !isAnyProcessing
                    )
                }
            }

            // Depth Contrast (gamma) slider
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Amplifies subtle depth differences for more 3D pop") } },
                state = rememberTooltipState()
            ) {
                Column(modifier = Modifier.semantics { contentDescription = "Depth contrast slider" }) {
                    Text(
                        "Depth Contrast: ${"%.1f".format(config.depthGamma)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = config.depthGamma,
                        onValueChange = { onConfigChange(config.copy(depthGamma = it)) },
                        valueRange = 0.2f..2f,
                        enabled = isModelReady && hasDepth && !isAnyProcessing
                    )
                }
            }

            // Depth Smoothing slider
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Smooths depth boundaries to reduce artifacts") } },
                state = rememberTooltipState()
            ) {
                Column(modifier = Modifier.semantics { contentDescription = "Depth smoothing slider" }) {
                    Text(
                        "Depth Smoothing: ${config.depthBlurKernel}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = config.depthBlurKernel.toFloat(),
                        onValueChange = { newVal ->
                            val rounded = newVal.toInt().let { if (it % 2 == 0) it + 1 else it }
                            onConfigChange(config.copy(depthBlurKernel = rounded.coerceIn(1, 33)))
                        },
                        valueRange = 1f..33f,
                        steps = 15,
                        enabled = isModelReady && hasDepth && !isAnyProcessing
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Arrangement toggle
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Parallel for wall-eyed viewing, Cross-Eye for cross-eyed free-viewing") } },
                state = rememberTooltipState()
            ) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Arrangement toggle" }
                ) {
                    SegmentedButton(
                        selected = config.arrangement == SbsArrangement.PARALLEL,
                        onClick = { onConfigChange(config.copy(arrangement = SbsArrangement.PARALLEL)) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text("Parallel")
                    }
                    SegmentedButton(
                        selected = config.arrangement == SbsArrangement.CROSS_EYED,
                        onClick = { onConfigChange(config.copy(arrangement = SbsArrangement.CROSS_EYED)) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text("Cross-Eye")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Button row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Load Image button
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Select an image from gallery") } },
                    state = rememberTooltipState()
                ) {
                    Button(
                        onClick = onLoadImage,
                        enabled = isModelReady && !isAnyProcessing,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "Load image button" }
                    ) {
                        Text("Load Image")
                    }
                }

                // Generate 3D button
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Generate side-by-side 3D image") } },
                    state = rememberTooltipState()
                ) {
                    Button(
                        onClick = onGenerate,
                        enabled = isModelReady && hasDepth && !isAnyProcessing,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "Generate 3D button" }
                    ) {
                        Text("Generate 3D")
                    }
                }

                // Save button
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Save SBS image to gallery") } },
                    state = rememberTooltipState()
                ) {
                    Button(
                        onClick = onSave,
                        enabled = hasSbsResult && !isSaving && !isAnyProcessing,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "Save to gallery button" }
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(if (isSaving) "Saving" else "Save")
                    }
                }
            }
        }
    }
}
