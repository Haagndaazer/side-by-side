package com.example.sbsconverter.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.sbsconverter.model.Arrangement as SbsArrangement
import com.example.sbsconverter.model.ProcessingConfig
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, onNavigateToBatch: () -> Unit = {}) {
    val isModelReady by viewModel.isModelReady.collectAsState()
    val modelLoadProgress by viewModel.modelLoadProgress.collectAsState()
    val originalImage by viewModel.originalImage.collectAsState()
    val depthImage by viewModel.depthImage.collectAsState()
    val isEstimatingDepth by viewModel.isEstimatingDepth.collectAsState()
    val isGeneratingSbs by viewModel.isGeneratingSbs.collectAsState()
    val isEnhancingDepth by viewModel.isEnhancingDepth.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val inferenceTimeMs by viewModel.inferenceTimeMs.collectAsState()
    val processingConfig by viewModel.processingConfig.collectAsState()
    val hasSbsResult by viewModel.hasSbsResult.collectAsState()
    val sbsImage by viewModel.sbsImage.collectAsState()
    val sbsGenerationTimeMs by viewModel.sbsGenerationTimeMs.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val normalizedDepth by viewModel.normalizedDepth.collectAsState()
    val imageDimensions by viewModel.imageDimensions.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val saveSuccess by viewModel.saveSuccess.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var isAdjustingConvergence by remember { mutableStateOf(false) }
    var isAdjustingSurfaceDetail by remember { mutableStateOf(false) }

    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.onImageSelected(it) }
    }

    LaunchedEffect(saveSuccess) {
        saveSuccess?.let { success ->
            snackbarHostState.showSnackbar(
                if (success) "Saved to Pictures/SBS Converter" else "Save failed"
            )
            viewModel.clearSaveStatus()
        }
    }

    val isAnyProcessing = isEstimatingDepth || isGeneratingSbs

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // View mode tabs (only shown after SBS generation)
            if (hasSbsResult) {
                TabRow(
                    selectedTabIndex = viewMode.ordinal,
                    modifier = Modifier.semantics { contentDescription = "Preview mode tabs" }
                ) {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Compare original photo with depth map") } },
                        state = rememberTooltipState()
                    ) {
                        Tab(
                            selected = viewMode == ViewMode.DEPTH_COMPARE,
                            onClick = { viewModel.setViewMode(ViewMode.DEPTH_COMPARE) },
                            text = { Text("Original") }
                        )
                    }
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("View generated side-by-side 3D image") } },
                        state = rememberTooltipState()
                    ) {
                        Tab(
                            selected = viewMode == ViewMode.SBS_RESULT,
                            onClick = { viewModel.setViewMode(ViewMode.SBS_RESULT) },
                            text = { Text("3D Result") }
                        )
                    }
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Animated depth preview alternating left/right eyes") } },
                        state = rememberTooltipState()
                    ) {
                        Tab(
                            selected = viewMode == ViewMode.WIGGLEGRAM,
                            onClick = { viewModel.setViewMode(ViewMode.WIGGLEGRAM) },
                            text = { Text("Wigglegram") }
                        )
                    }
                }
            }

            // Image preview area
            Box(
                modifier = Modifier
                    .weight(1f)
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
                    isEnhancingDepth = isEnhancingDepth,
                    errorMessage = errorMessage,
                    inferenceTimeMs = inferenceTimeMs,
                    sbsGenerationTimeMs = sbsGenerationTimeMs,
                    viewMode = viewMode,
                    sbsImage = sbsImage,
                    imageDimensions = imageDimensions,
                    hasSbsResult = hasSbsResult,
                    isAdjustingConvergence = isAdjustingConvergence,
                    isAdjustingSurfaceDetail = isAdjustingSurfaceDetail,
                    normalizedDepth = normalizedDepth,
                    convergencePoint = processingConfig.convergencePoint,
                    arrangement = processingConfig.arrangement
                )
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
                onSave = { viewModel.saveSbsToGallery() },
                onBatch = onNavigateToBatch,
                onSliderFinished = { viewModel.onSliderFinished() },
                isModelReady = isModelReady,
                hasDepth = depthImage != null,
                hasSbsResult = hasSbsResult,
                isAnyProcessing = isAnyProcessing,
                isSaving = isSaving,
                errorMessage = errorMessage,
                onConvergenceAdjusting = { isAdjustingConvergence = it },
                onSurfaceDetailAdjusting = { isAdjustingSurfaceDetail = it },
                onSurfaceDetailFinished = { viewModel.refreshEnhancedDepthPreview() }
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
    isEnhancingDepth: Boolean,
    errorMessage: String?,
    inferenceTimeMs: Long?,
    sbsGenerationTimeMs: Long?,
    viewMode: ViewMode,
    sbsImage: ImageBitmap?,
    imageDimensions: Pair<Int, Int>?,
    hasSbsResult: Boolean,
    isAdjustingConvergence: Boolean,
    isAdjustingSurfaceDetail: Boolean,
    normalizedDepth: FloatArray?,
    convergencePoint: Float,
    arrangement: SbsArrangement
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
        // Main preview based on view mode (force depth compare while adjusting convergence or surface detail)
        val effectiveMode = if ((isAdjustingConvergence || isAdjustingSurfaceDetail) && depthImage != null) null else viewMode
        when {
            hasSbsResult && effectiveMode == ViewMode.SBS_RESULT && sbsImage != null -> {
                // SBS result view
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Side-by-side 3D result") } },
                    state = rememberTooltipState()
                ) {
                    Column(
                        modifier = Modifier.semantics { contentDescription = "SBS 3D result" },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            bitmap = sbsImage,
                            contentDescription = "Side-by-side 3D result",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(sbsImage.width.toFloat() / sbsImage.height.toFloat(), matchHeightConstraintsFirst = true)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (arrangement == SbsArrangement.PARALLEL) "Parallel (wall-eyed)" else "Cross-eye",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            hasSbsResult && effectiveMode == ViewMode.WIGGLEGRAM && sbsImage != null -> {
                WigglegramPreview(
                    sbsImage = sbsImage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(imageAspect, matchHeightConstraintsFirst = true)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
            depthImage != null -> {
                // Depth compare (reveal slider) — default view
                DepthRevealSlider(
                    originalImage = originalImage,
                    depthImage = depthImage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                )
            }
            else -> {
                Image(
                    bitmap = originalImage,
                    contentDescription = "Original image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(imageAspect, matchHeightConstraintsFirst = true)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
        }

        // Convergence visualization overlay
        if (isAdjustingConvergence && normalizedDepth != null && depthImage != null) {
            ConvergenceOverlay(
                normalizedDepth = normalizedDepth,
                convergencePoint = convergencePoint,
                depthWidth = 770,
                depthHeight = 770,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(imageAspect, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(8.dp))
            )
        }

        // Processing overlay (depth estimation)
        if (isEstimatingDepth) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(imageAspect, matchHeightConstraintsFirst = true)
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
                    .aspectRatio(imageAspect, matchHeightConstraintsFirst = true)
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

        // Processing overlay (enhancing depth)
        if (isEnhancingDepth) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(imageAspect, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Enhancing depth...", style = MaterialTheme.typography.bodyMedium, color = Color.White)
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
                    .aspectRatio(imageAspect, matchHeightConstraintsFirst = true),
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

    LaunchedEffect(originalImage, depthImage) {
        progress = 0.5f
    }

    Box(
        modifier = modifier
            .aspectRatio(originalImage.width.toFloat() / originalImage.height.toFloat(), matchHeightConstraintsFirst = true)
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
        Image(
            bitmap = depthImage,
            contentDescription = "Depth map",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        Image(
            bitmap = originalImage,
            contentDescription = "Original image",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(
                        left = 0f, top = 0f,
                        right = size.width * progress,
                        bottom = size.height
                    ) {
                        this@drawWithContent.drawContent()
                    }
                }
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val splitX = size.width * progress
            drawLine(Color.White, Offset(splitX, 0f), Offset(splitX, size.height), 3.dp.toPx(), StrokeCap.Round)
            val thumbCenter = Offset(splitX, size.height / 2f)
            drawCircle(Color.White, 14.dp.toPx(), thumbCenter)
            drawCircle(Color.Black.copy(alpha = 0.3f), 12.dp.toPx(), thumbCenter)
            val arrowSize = 6.dp.toPx()
            drawLine(Color.White, Offset(splitX - arrowSize, thumbCenter.y), Offset(splitX - arrowSize / 2, thumbCenter.y - arrowSize / 2), 2.dp.toPx(), StrokeCap.Round)
            drawLine(Color.White, Offset(splitX - arrowSize, thumbCenter.y), Offset(splitX - arrowSize / 2, thumbCenter.y + arrowSize / 2), 2.dp.toPx(), StrokeCap.Round)
            drawLine(Color.White, Offset(splitX + arrowSize, thumbCenter.y), Offset(splitX + arrowSize / 2, thumbCenter.y - arrowSize / 2), 2.dp.toPx(), StrokeCap.Round)
            drawLine(Color.White, Offset(splitX + arrowSize, thumbCenter.y), Offset(splitX + arrowSize / 2, thumbCenter.y + arrowSize / 2), 2.dp.toPx(), StrokeCap.Round)
        }

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
    val popOutColor = Color(0x400064FF)
    val recedeColor = Color(0x40FF3200)
    val contourColor = Color(0xFFFFFF00)

    Canvas(modifier = modifier.semantics { contentDescription = "Convergence visualization" }) {
        val scaleX = size.width / depthWidth
        val scaleY = size.height / depthHeight
        val contourThreshold = 0.02f
        val pixelW = scaleX.coerceAtLeast(1f)
        val pixelH = scaleY.coerceAtLeast(1f)
        val step = 4

        for (y in 0 until depthHeight step step) {
            for (x in 0 until depthWidth step step) {
                val depth = normalizedDepth[y * depthWidth + x]
                val diff = depth - convergencePoint
                val color = if (diff > contourThreshold) popOutColor
                else if (diff < -contourThreshold) recedeColor
                else contourColor.copy(alpha = 0.7f)

                drawRect(
                    color = color,
                    topLeft = Offset(x * scaleX, y * scaleY),
                    size = androidx.compose.ui.geometry.Size(pixelW * step, pixelH * step)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomControlPanel(
    config: ProcessingConfig,
    onConfigChange: (ProcessingConfig) -> Unit,
    onLoadImage: () -> Unit,
    onSave: () -> Unit,
    onBatch: () -> Unit,
    onSliderFinished: () -> Unit,
    isModelReady: Boolean,
    hasDepth: Boolean,
    hasSbsResult: Boolean,
    isAnyProcessing: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onConvergenceAdjusting: (Boolean) -> Unit,
    onSurfaceDetailAdjusting: (Boolean) -> Unit,
    onSurfaceDetailFinished: () -> Unit
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

            // Primary: 3D Strength slider
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Controls 3D depth intensity") } },
                state = rememberTooltipState()
            ) {
                Column(modifier = Modifier.semantics { contentDescription = "3D strength slider" }) {
                    Text("3D Strength: ${"%.1f".format(config.depthScale)}%", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = config.depthScale,
                        onValueChange = { onConfigChange(config.copy(depthScale = it)) },
                        onValueChangeFinished = onSliderFinished,
                        valueRange = 0.5f..8f,
                        enabled = isModelReady && hasDepth && !isAnyProcessing
                    )
                }
            }

            // Primary: Convergence slider
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Sets which depth appears at screen level. Higher values bring more of the scene in front of the screen.") } },
                state = rememberTooltipState()
            ) {
                Column(modifier = Modifier.semantics { contentDescription = "Convergence point slider" }) {
                    Text("Convergence: ${(config.convergencePoint * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = config.convergencePoint,
                        onValueChange = {
                            onConvergenceAdjusting(true)
                            onConfigChange(config.copy(convergencePoint = it))
                        },
                        onValueChangeFinished = {
                            onConvergenceAdjusting(false)
                            onSliderFinished()
                        },
                        valueRange = 0f..1f,
                        enabled = isModelReady && hasDepth && !isAnyProcessing
                    )
                }
            }

            // Advanced section (collapsed by default)
            var showAdvanced by remember { mutableStateOf(false) }

            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Show additional depth processing controls") } },
                state = rememberTooltipState()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAdvanced = !showAdvanced }
                        .padding(vertical = 4.dp)
                        .semantics { contentDescription = "Advanced settings toggle" },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Advanced", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (showAdvanced) "▲" else "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = showAdvanced) {
                Column {
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
                                onClick = {
                                    onConfigChange(config.copy(arrangement = SbsArrangement.PARALLEL))
                                    onSliderFinished()
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                            ) { Text("Parallel") }
                            SegmentedButton(
                                selected = config.arrangement == SbsArrangement.CROSS_EYED,
                                onClick = {
                                    onConfigChange(config.copy(arrangement = SbsArrangement.CROSS_EYED))
                                    onSliderFinished()
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                            ) { Text("Cross-Eye") }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Depth Contrast slider
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Amplifies subtle depth differences for more 3D pop") } },
                        state = rememberTooltipState()
                    ) {
                        Column(modifier = Modifier.semantics { contentDescription = "Depth contrast slider" }) {
                            Text("Depth Contrast: ${"%.1f".format(config.depthGamma)}", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = config.depthGamma,
                                onValueChange = { onConfigChange(config.copy(depthGamma = it)) },
                                onValueChangeFinished = onSliderFinished,
                                valueRange = 0.2f..2f,
                                enabled = isModelReady && hasDepth && !isAnyProcessing
                            )
                        }
                    }

                    // Edge Smoothing slider
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Smooths depth boundaries to reduce artifacts") } },
                        state = rememberTooltipState()
                    ) {
                        Column(modifier = Modifier.semantics { contentDescription = "Edge smoothing slider" }) {
                            Text("Edge Smoothing: ${config.smoothingLabel}", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = config.depthBlurKernel.toFloat(),
                                onValueChange = { newVal ->
                                    val rounded = newVal.toInt().let { if (it % 2 == 0) it + 1 else it }
                                    onConfigChange(config.copy(depthBlurKernel = rounded.coerceIn(1, 33)))
                                },
                                onValueChangeFinished = onSliderFinished,
                                valueRange = 1f..33f,
                                steps = 15,
                                enabled = isModelReady && hasDepth && !isAnyProcessing
                            )
                        }
                    }

                    // Surface Detail slider
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Enhances micro-depth surface detail like bumps, folds, and contours") } },
                        state = rememberTooltipState()
                    ) {
                        Column(modifier = Modifier.semantics { contentDescription = "Surface detail slider" }) {
                            Text("Surface Detail: ${"%.1f".format(config.surfaceDetail)}", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = config.surfaceDetail,
                                onValueChange = {
                                    onSurfaceDetailAdjusting(true)
                                    onConfigChange(config.copy(surfaceDetail = it))
                                },
                                onValueChangeFinished = {
                                    onSurfaceDetailAdjusting(false)
                                    onSurfaceDetailFinished()
                                },
                                valueRange = 0f..3f,
                                enabled = isModelReady && hasDepth && !isAnyProcessing
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Button row: Load Image + Save
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                    ) { Text("Load Image") }
                }

                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Convert multiple photos to 3D") } },
                    state = rememberTooltipState()
                ) {
                    Button(
                        onClick = onBatch,
                        enabled = isModelReady && !isAnyProcessing,
                        modifier = Modifier
                            .weight(0.7f)
                            .semantics { contentDescription = "Batch convert button" }
                    ) { Text("Batch") }
                }

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
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(if (isSaving) "Saving" else "Save")
                    }
                }
            }
        }
    }
}
