package com.example.sbsconverter.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.sbsconverter.model.ProcessingConfig
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val isModelReady by viewModel.isModelReady.collectAsState()
    val modelLoadProgress by viewModel.modelLoadProgress.collectAsState()
    val originalBitmap by viewModel.originalBitmap.collectAsState()
    val depthBitmap by viewModel.depthBitmap.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val inferenceTimeMs by viewModel.inferenceTimeMs.collectAsState()
    val processingConfig by viewModel.processingConfig.collectAsState()
    val sbsBitmap by viewModel.sbsBitmap.collectAsState()
    val sbsGenerationTimeMs by viewModel.sbsGenerationTimeMs.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val saveSuccess by viewModel.saveSuccess.collectAsState()

    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.onImageSelected(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "SBS 3D Converter",
                        modifier = Modifier.semantics { contentDescription = "App title" }
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isModelReady) {
                ModelLoadingSection(modelLoadProgress, errorMessage)
            } else {
                ImageResultsSection(originalBitmap, depthBitmap, isProcessing, inferenceTimeMs)

                Spacer(modifier = Modifier.height(16.dp))

                errorMessage?.let {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Error message") } },
                        state = rememberTooltipState()
                    ) {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { contentDescription = "Error message" }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Select an image from gallery") } },
                    state = rememberTooltipState()
                ) {
                    Button(
                        onClick = {
                            pickMedia.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        enabled = !isProcessing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Select image button" }
                    ) {
                        Text("Select Image")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                TestImagesSection(
                    enabled = !isProcessing,
                    onTestImageSelected = { viewModel.onTestImageSelected(it) }
                )

                // Settings section — shown after depth estimation
                if (depthBitmap != null && !isProcessing) {
                    Spacer(modifier = Modifier.height(24.dp))
                    SettingsSection(
                        config = processingConfig,
                        onConfigChange = { viewModel.updateConfig(it) },
                        onGenerate = { viewModel.generateSbs() },
                        isProcessing = isProcessing
                    )
                }

                // SBS result section
                if (sbsBitmap != null) {
                    Spacer(modifier = Modifier.height(24.dp))
                    SbsResultSection(
                        sbsBitmap = sbsBitmap,
                        generationTimeMs = sbsGenerationTimeMs,
                        onSave = { viewModel.saveSbsToGallery() },
                        isSaving = isSaving,
                        saveSuccess = saveSuccess,
                        onClearSaveStatus = { viewModel.clearSaveStatus() }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelLoadingSection(progress: Float, errorMessage: String?) {
    Spacer(modifier = Modifier.height(48.dp))

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageResultsSection(
    originalBitmap: Bitmap?,
    depthBitmap: Bitmap?,
    isProcessing: Boolean,
    inferenceTimeMs: Long?
) {
    if (originalBitmap != null) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Original image") } },
            state = rememberTooltipState()
        ) {
            Column(modifier = Modifier.semantics { contentDescription = "Original image" }) {
                Text("Original", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Image(
                    bitmap = originalBitmap.asImageBitmap(),
                    contentDescription = "Original image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (isProcessing) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Processing") } },
                state = rememberTooltipState()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .semantics { contentDescription = "Processing indicator" },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Processing...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (depthBitmap != null) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Depth map visualization") } },
                state = rememberTooltipState()
            ) {
                Column(modifier = Modifier.semantics { contentDescription = "Depth map" }) {
                    Text("Depth Map", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Image(
                        bitmap = depthBitmap.asImageBitmap(),
                        contentDescription = "Depth map visualization",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
            }

            inferenceTimeMs?.let { ms ->
                Spacer(modifier = Modifier.height(8.dp))
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Depth estimation time") } },
                    state = rememberTooltipState()
                ) {
                    Text(
                        text = "Inference time: ${"%.1f".format(ms / 1000f)}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { contentDescription = "Inference time" }
                    )
                }
            }
        }
    } else {
        Spacer(modifier = Modifier.height(48.dp))
        Text(
            text = "Select an image or tap a test image below",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(48.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSection(
    config: ProcessingConfig,
    onConfigChange: (ProcessingConfig) -> Unit,
    onGenerate: () -> Unit,
    isProcessing: Boolean
) {
    Text(
        "3D Settings",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "3D settings section" }
    )
    Spacer(modifier = Modifier.height(8.dp))

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
                valueRange = 0.5f..8f
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
                steps = 15
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Generate button
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("Generate side-by-side 3D image") } },
        state = rememberTooltipState()
    ) {
        Button(
            onClick = onGenerate,
            enabled = !isProcessing,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Generate 3D button" }
        ) {
            Text("Generate 3D")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SbsResultSection(
    sbsBitmap: Bitmap?,
    generationTimeMs: Long?,
    onSave: () -> Unit,
    isSaving: Boolean,
    saveSuccess: Boolean?,
    onClearSaveStatus: () -> Unit
) {
    if (sbsBitmap == null) return

    Text(
        "SBS 3D Result",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "SBS result section" }
    )
    Spacer(modifier = Modifier.height(8.dp))

    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("SBS 3D result") } },
        state = rememberTooltipState()
    ) {
        Image(
            bitmap = sbsBitmap.asImageBitmap(),
            contentDescription = "SBS 3D result image",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(sbsBitmap.width.toFloat() / sbsBitmap.height.toFloat())
                .clip(RoundedCornerShape(8.dp))
                .semantics { contentDescription = "SBS 3D result" }
        )
    }

    generationTimeMs?.let { ms ->
        Spacer(modifier = Modifier.height(8.dp))
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("SBS generation time") } },
            state = rememberTooltipState()
        ) {
            Text(
                text = "Generation time: ${"%.1f".format(ms / 1000f)}s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "Generation time" }
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("Save SBS image to gallery") } },
        state = rememberTooltipState()
    ) {
        Button(
            onClick = onSave,
            enabled = !isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Save to gallery button" }
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (isSaving) "Saving..." else "Save to Gallery")
        }
    }

    saveSuccess?.let { success ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (success) "Saved to Pictures/SBS Converter" else "Save failed",
            color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
        LaunchedEffect(success) {
            delay(3000)
            onClearSaveStatus()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestImagesSection(
    enabled: Boolean,
    onTestImageSelected: (String) -> Unit
) {
    Text(
        "Test Images",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Test images section" }
    )
    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val testImages = listOf(
            "test_images/altar_2d.jpg" to "Altar",
            "test_images/church_2d.jpg" to "Church",
            "test_images/coast_2d.jpg" to "Coast"
        )

        for ((path, label) in testImages) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Load $label test image") } },
                state = rememberTooltipState()
            ) {
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clickable(enabled = enabled) { onTestImageSelected(path) }
                        .semantics { contentDescription = "$label test image" }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}
