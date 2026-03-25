package com.example.sbsconverter.ui.screens

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sbsconverter.ui.components.StereoViewer
import com.example.sbsconverter.ui.presentations.GlassesPresentation
import com.example.sbsconverter.util.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─── ViewModel ───────────────────────────────────────────────────────────────

class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val _sbsImage = MutableStateFlow<ImageBitmap?>(null)
    val sbsImage: StateFlow<ImageBitmap?> = _sbsImage

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _imageList = MutableStateFlow<List<Uri>>(emptyList())
    val imageList: StateFlow<List<Uri>> = _imageList

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex

    private val _displayName = MutableStateFlow<String?>(null)
    val displayName: StateFlow<String?> = _displayName

    private val _showGallery = MutableStateFlow(true)
    val showGallery: StateFlow<Boolean> = _showGallery

    private val _hasMediaPermission = MutableStateFlow(false)
    val hasMediaPermission: StateFlow<Boolean> = _hasMediaPermission

    private var currentBitmap: Bitmap? = null

    fun checkMediaPermission(context: android.content.Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        _hasMediaPermission.value = granted
        return granted
    }

    fun onPermissionResult(granted: Boolean) {
        _hasMediaPermission.value = granted
        if (granted) refreshFolder()
    }

    fun initialize(initialUri: Uri?) {
        viewModelScope.launch {
            refreshFolder()
            if (initialUri != null) {
                val list = _imageList.value
                val idx = list.indexOfFirst { it == initialUri }
                if (idx >= 0) {
                    _currentIndex.value = idx
                    loadImageAtIndex(idx)
                } else {
                    loadImage(initialUri)
                }
                _showGallery.value = false
            }
        }
    }

    fun refreshFolder() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val uris = withContext(Dispatchers.IO) {
                val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(MediaStore.Images.Media._ID)
                val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("${Environment.DIRECTORY_PICTURES}/SBS Converter%")
                val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

                val result = mutableListOf<Uri>()
                context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        result.add(Uri.withAppendedPath(collection, id.toString()))
                    }
                }
                result
            }
            _imageList.value = uris
        }
    }

    fun openImage(index: Int) {
        loadImageAtIndex(index)
        _showGallery.value = false
    }

    fun showGalleryView() {
        _showGallery.value = true
        _sbsImage.value = null
        currentBitmap?.recycle()
        currentBitmap = null
        refreshFolder()
    }

    fun loadImage(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                currentBitmap?.recycle()
                val bitmap = withContext(Dispatchers.IO) {
                    BitmapUtils.loadBitmapFromUri(getApplication(), uri, maxDimension = 4096)
                } ?: throw IllegalStateException("Failed to decode")
                currentBitmap = bitmap
                _sbsImage.value = bitmap.asImageBitmap()

                val context = getApplication<Application>()
                context.contentResolver.query(
                    uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                        if (idx >= 0) _displayName.value = cursor.getString(idx)
                    }
                }
            } catch (_: Exception) { }
            finally { _isLoading.value = false }
        }
    }

    private fun loadImageAtIndex(index: Int) {
        val list = _imageList.value
        if (index in list.indices) {
            _currentIndex.value = index
            loadImage(list[index])
        }
    }

    fun loadNext() {
        val list = _imageList.value
        val idx = _currentIndex.value
        if (idx < list.size - 1) loadImageAtIndex(idx + 1)
    }

    fun loadPrevious() {
        val idx = _currentIndex.value
        if (idx > 0) loadImageAtIndex(idx - 1)
    }

    fun deleteCurrentImage() {
        val list = _imageList.value
        val idx = _currentIndex.value
        if (idx !in list.indices) return
        deleteImages(listOf(list[idx]))
    }

    fun deleteImages(uris: List<Uri>) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) { }
                }
            }
            refreshFolder()
            val newList = _imageList.value
            if (_showGallery.value) return@launch
            if (newList.isEmpty()) {
                _showGallery.value = true
                _sbsImage.value = null
            } else {
                val newIdx = _currentIndex.value.coerceAtMost(newList.size - 1)
                loadImageAtIndex(newIdx)
            }
        }
    }

    // ─── External Display (AR Glasses) ─────────────────────────────────────

    private val _isGlassesConnected = MutableStateFlow(false)
    val isGlassesConnected: StateFlow<Boolean> = _isGlassesConnected

    private val _isGlassesActive = MutableStateFlow(false)
    val isGlassesActive: StateFlow<Boolean> = _isGlassesActive

    private var glassesPresentation: GlassesPresentation? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    fun setupDisplayDetection(context: android.content.Context) {
        val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE) as DisplayManager

        // Check current displays
        _isGlassesConnected.value = dm.displays.any { it.displayId != Display.DEFAULT_DISPLAY }

        // Listen for changes
        if (displayListener == null) {
            displayListener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {
                    _isGlassesConnected.value = true
                }
                override fun onDisplayRemoved(displayId: Int) {
                    if (dm.displays.none { it.displayId != Display.DEFAULT_DISPLAY }) {
                        _isGlassesConnected.value = false
                        dismissGlasses()
                    }
                }
                override fun onDisplayChanged(displayId: Int) {}
            }
            dm.registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))
        }
    }

    fun toggleGlasses(context: android.content.Context, swapEyes: Boolean) {
        if (glassesPresentation != null) {
            dismissGlasses()
            return
        }

        val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE) as DisplayManager
        val externalDisplay = dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY } ?: return
        val image = _sbsImage.value ?: return

        glassesPresentation = GlassesPresentation(context, externalDisplay) {
            _isGlassesActive.value = false
            glassesPresentation = null
        }.apply {
            updateImage(image)
            updateSwapEyes(swapEyes)
            show()
        }
        _isGlassesActive.value = true
    }

    fun updateGlassesContent(image: ImageBitmap, swapEyes: Boolean) {
        glassesPresentation?.updateImage(image)
        glassesPresentation?.updateSwapEyes(swapEyes)
    }

    fun updateGlassesTransform(scale: Float, offsetX: Float, offsetY: Float) {
        glassesPresentation?.updateTransform(scale, offsetX, offsetY)
    }

    fun dismissGlasses() {
        glassesPresentation?.dismiss()
        glassesPresentation = null
        _isGlassesActive.value = false
    }

    override fun onCleared() {
        dismissGlasses()
        currentBitmap?.recycle()
        super.onCleared()
    }
}

// ─── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    initialUri: Uri?,
    onNavigateBack: () -> Unit
) {
    val showGallery by viewModel.showGallery.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.initialize(initialUri)
    }

    if (showGallery) {
        GalleryView(viewModel = viewModel, onNavigateBack = onNavigateBack)
    } else {
        SingleImageView(viewModel = viewModel, onBackToGallery = { viewModel.showGalleryView() })
    }
}

// ─── Gallery Grid View ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun GalleryView(
    viewModel: ViewerViewModel,
    onNavigateBack: () -> Unit
) {
    val imageList by viewModel.imageList.collectAsState()
    val hasMediaPermission by viewModel.hasMediaPermission.collectAsState()
    var selectedUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    val isSelecting = selectedUris.isNotEmpty()
    var showDeleteDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onPermissionResult(granted)
    }

    LaunchedEffect(Unit) {
        if (!viewModel.checkMediaPermission(context)) {
            permissionLauncher.launch(mediaPermission)
        }
    }

    BackHandler {
        if (isSelecting) selectedUris = emptySet()
        else onNavigateBack()
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete ${selectedUris.size} image${if (selectedUris.size > 1) "s" else ""}") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteImages(selectedUris.toList())
                    selectedUris = emptySet()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (isSelecting) "${selectedUris.size} selected" else "3D Gallery")
                },
                navigationIcon = {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(if (isSelecting) "Cancel selection" else "Back to main screen") } },
                        state = rememberTooltipState()
                    ) {
                        IconButton(
                            onClick = {
                                if (isSelecting) selectedUris = emptySet()
                                else onNavigateBack()
                            },
                            modifier = Modifier.semantics { contentDescription = "Back button" }
                        ) {
                            Text("←", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                },
                actions = {
                    if (isSelecting) {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text("Delete selected images") } },
                            state = rememberTooltipState()
                        ) {
                            IconButton(
                                onClick = { showDeleteDialog = true },
                                modifier = Modifier.semantics { contentDescription = "Delete selected" }
                            ) {
                                Text("🗑️")
                            }
                        }

                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text("Select all") } },
                            state = rememberTooltipState()
                        ) {
                            IconButton(
                                onClick = { selectedUris = imageList.toSet() },
                                modifier = Modifier.semantics { contentDescription = "Select all" }
                            ) {
                                Text("☑", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (imageList.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (!hasMediaPermission) "Grant photo access to see all images"
                        else "No SBS images found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!hasMediaPermission) {
                        Spacer(modifier = Modifier.height(12.dp))
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text("Request permission to read photos from gallery") } },
                            state = rememberTooltipState()
                        ) {
                            Button(
                                onClick = { permissionLauncher.launch(mediaPermission) },
                                modifier = Modifier.semantics { contentDescription = "Grant photo access button" }
                            ) {
                                Text("Grant Access")
                            }
                        }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(imageList, key = { it.toString() }) { uri ->
                    val index = imageList.indexOf(uri)
                    val isSelected = uri in selectedUris

                    GalleryThumbnail(
                        uri = uri,
                        isSelected = isSelected,
                        onClick = {
                            if (isSelecting) {
                                selectedUris = if (isSelected) selectedUris - uri else selectedUris + uri
                            } else {
                                viewModel.openImage(index)
                            }
                        },
                        onLongClick = {
                            selectedUris = if (isSelected) selectedUris - uri else selectedUris + uri
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryThumbnail(
    uri: Uri,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var thumbnail by remember(uri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(uri) {
        val bmp = withContext(Dispatchers.IO) {
            val fullBmp = BitmapUtils.loadThumbnail(context, uri, 512) ?: return@withContext null
            val halfW = fullBmp.width / 2
            if (halfW > 0) {
                val cropped = Bitmap.createBitmap(fullBmp, 0, 0, halfW, fullBmp.height)
                if (cropped !== fullBmp) fullBmp.recycle()
                cropped
            } else fullBmp
        }
        if (bmp != null) thumbnail = bmp.asImageBitmap()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                else Modifier
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .semantics { contentDescription = "SBS image thumbnail" },
        contentAlignment = Alignment.Center
    ) {
        thumbnail?.let { img ->
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Selection checkmark
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ─── Single Image Viewer ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleImageView(
    viewModel: ViewerViewModel,
    onBackToGallery: () -> Unit
) {
    val sbsImage by viewModel.sbsImage.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val imageList by viewModel.imageList.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val displayName by viewModel.displayName.collectAsState()
    val isGlassesConnected by viewModel.isGlassesConnected.collectAsState()
    val isGlassesActive by viewModel.isGlassesActive.collectAsState()

    val context = LocalContext.current

    // Detect external display (AR glasses)
    LaunchedEffect(Unit) {
        viewModel.setupDisplayDetection(context)
    }

    BackHandler { onBackToGallery() }

    var showControls by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableStateOf(System.currentTimeMillis()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var halfSbsMode by remember { mutableStateOf(false) }
    var swapEyes by remember { mutableStateOf(false) }

    LaunchedEffect(lastInteraction) {
        delay(4000)
        showControls = false
    }

    // Keep glasses presentation in sync with current image and settings
    LaunchedEffect(sbsImage, swapEyes) {
        if (isGlassesActive && sbsImage != null) {
            viewModel.updateGlassesContent(sbsImage!!, swapEyes)
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Image") },
            text = { Text("Delete \"${displayName ?: "this image"}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteCurrentImage()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (sbsImage != null) {
            StereoViewer(
                sbsImage = sbsImage!!,
                modifier = Modifier.fillMaxSize(),
                halfSbsMode = halfSbsMode,
                swapEyes = swapEyes,
                onTransformChanged = { s, ox, oy ->
                    if (isGlassesActive) viewModel.updateGlassesTransform(s, ox, oy)
                },
                onSwipeLeft = { viewModel.loadNext() },
                onSwipeRight = { viewModel.loadPrevious() },
                onTap = {
                    showControls = !showControls
                    if (showControls) lastInteraction = System.currentTimeMillis()
                }
            )
        } else if (!isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No image", color = Color.White, style = MaterialTheme.typography.bodyLarge)
            }
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // Floating controls
        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Gallery button (top-left)
                Box(modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Back to gallery") } },
                        state = rememberTooltipState()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .clickable { onBackToGallery() }
                                .semantics { contentDescription = "Back to gallery" },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("←", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }

                // Bottom center toolbar: Half SBS, Delete, Swap (50dp up)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = (-50).dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Half SBS toggle
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text("Half SBS mode for AR glasses") } },
                            state = rememberTooltipState()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .background(
                                        if (halfSbsMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                        else Color.Black.copy(alpha = 0.6f),
                                        CircleShape
                                    )
                                    .clickable {
                                        halfSbsMode = !halfSbsMode
                                        lastInteraction = System.currentTimeMillis()
                                    }
                                    .semantics { contentDescription = "Toggle half SBS mode" },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("H", color = Color.White, style = MaterialTheme.typography.titleMedium)
                            }
                        }

                        // Delete
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text("Delete this image") } },
                            state = rememberTooltipState()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                    .clickable {
                                        showDeleteDialog = true
                                        lastInteraction = System.currentTimeMillis()
                                    }
                                    .semantics { contentDescription = "Delete image" },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🗑️", color = Color.White, style = MaterialTheme.typography.titleLarge)
                            }
                        }

                        // Swap eyes toggle
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text("Swap left and right eyes") } },
                            state = rememberTooltipState()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .background(
                                        if (swapEyes) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                        else Color.Black.copy(alpha = 0.6f),
                                        CircleShape
                                    )
                                    .clickable {
                                        swapEyes = !swapEyes
                                        lastInteraction = System.currentTimeMillis()
                                    }
                                    .semantics { contentDescription = "Toggle swap eyes" },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("⇄", color = Color.White, style = MaterialTheme.typography.titleMedium)
                            }
                        }

                        // Glasses button (only shown when external display detected)
                        if (isGlassesConnected) {
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = { PlainTooltip { Text(if (isGlassesActive) "Disconnect from glasses" else "Send to AR glasses") } },
                                state = rememberTooltipState()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .background(
                                            if (isGlassesActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                            else Color.Black.copy(alpha = 0.6f),
                                            CircleShape
                                        )
                                        .clickable {
                                            viewModel.toggleGlasses(context, swapEyes)
                                            lastInteraction = System.currentTimeMillis()
                                        }
                                        .semantics { contentDescription = "Toggle AR glasses display" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("👓", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                }

                // Image counter (top-center)
                if (imageList.isNotEmpty() && currentIndex >= 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${currentIndex + 1} / ${imageList.size}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                // Display name (bottom-left)
                displayName?.let { name ->
                    Text(
                        text = name,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}

