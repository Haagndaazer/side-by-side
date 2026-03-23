package com.example.sbsconverter.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

@Composable
fun StereoViewer(
    sbsImage: ImageBitmap,
    modifier: Modifier = Modifier,
    halfSbsMode: Boolean = false,
    swapEyes: Boolean = false,
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
    onTap: (() -> Unit)? = null
) {
    val halfWidth = sbsImage.width / 2
    val leftPainter = remember(sbsImage) {
        BitmapPainter(sbsImage, IntOffset.Zero, IntSize(halfWidth, sbsImage.height))
    }
    val rightPainter = remember(sbsImage) {
        BitmapPainter(sbsImage, IntOffset(halfWidth, 0), IntSize(halfWidth, sbsImage.height))
    }

    // Swap eyes if toggled
    val firstPainter = if (swapEyes) rightPainter else leftPainter
    val secondPainter = if (swapEyes) leftPainter else rightPainter

    // Detect if each half-image is portrait (taller than wide)
    val isPortraitHalf = sbsImage.height > halfWidth

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var lastDoubleTapTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(sbsImage) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "Stereo 3D viewer" }
            .pointerInput(onSwipeLeft, onSwipeRight) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    firstDown.consume()

                    // Double-tap detection
                    val now = System.currentTimeMillis()
                    if (now - lastDoubleTapTime < 300L) {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                        lastDoubleTapTime = 0L
                        return@awaitEachGesture
                    }
                    lastDoubleTapTime = now

                    var swipeDelta = 0f
                    var didZoom = false

                    // Track all pointer movement until all pointers are up
                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }

                        if (pointerCount >= 2) {
                            // Multi-touch: zoom + pan
                            didZoom = true
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, 5f)

                            offsetX = offsetX * (newScale / scale) + pan.x
                            offsetY = offsetY * (newScale / scale) + pan.y
                            scale = newScale

                            val maxPanX = size.width * (scale - 1f) / 2f
                            val maxPanY = size.height * (scale - 1f) / 2f
                            offsetX = offsetX.coerceIn(-maxPanX, maxPanX)
                            offsetY = offsetY.coerceIn(-maxPanY, maxPanY)
                        } else if (pointerCount == 1) {
                            // Single finger
                            val pan = event.calculatePan()
                            if (scale <= 1.01f && !didZoom) {
                                // At 1x zoom: accumulate for swipe
                                swipeDelta += pan.x
                            } else {
                                // Zoomed in: pan
                                offsetX += pan.x
                                offsetY += pan.y

                                val maxPanX = size.width * (scale - 1f) / 2f
                                val maxPanY = size.height * (scale - 1f) / 2f
                                offsetX = offsetX.coerceIn(-maxPanX, maxPanX)
                                offsetY = offsetY.coerceIn(-maxPanY, maxPanY)
                            }
                        }

                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    } while (event.changes.any { it.pressed })

                    // Gesture ended — check for swipe navigation
                    if (scale <= 1.01f && !didZoom) {
                        val threshold = size.width * 0.15f
                        if (abs(swipeDelta) > threshold) {
                            if (swipeDelta < 0) onSwipeLeft?.invoke()
                            else onSwipeRight?.invoke()
                        }
                    }

                    // Reset double-tap timer if there was significant movement
                    if (abs(swipeDelta) > 20f || didZoom) {
                        lastDoubleTapTime = 0L
                    } else if (abs(swipeDelta) < 10f && !didZoom) {
                        // Minimal movement — treat as tap (after double-tap window)
                        onTap?.invoke()
                    }
                }
            }
    ) {
        StereoPane(
            painter = firstPainter,
            contentDescription = "Left eye",
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
            halfSbsMode = halfSbsMode,
            isPortraitHalf = isPortraitHalf,
            modifier = Modifier.weight(1f)
        )
        StereoPane(
            painter = secondPainter,
            contentDescription = "Right eye",
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
            halfSbsMode = halfSbsMode,
            isPortraitHalf = isPortraitHalf,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StereoPane(
    painter: BitmapPainter,
    contentDescription: String,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    halfSbsMode: Boolean = false,
    isPortraitHalf: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Half SBS mode ContentScale:
    // - Landscape half: FillBounds (squeezes horizontally to fill pane, glasses unsqueeze)
    // - Portrait half: FillHeight (fills pane height, pane width naturally squeezes, glasses unsqueeze)
    val contentScale = when {
        !halfSbsMode -> ContentScale.Fit
        isPortraitHalf -> ContentScale.FillHeight
        else -> ContentScale.FillBounds
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                }
        )
    }
}
