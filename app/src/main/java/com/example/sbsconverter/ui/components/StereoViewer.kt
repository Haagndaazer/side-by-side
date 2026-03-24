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
import androidx.compose.ui.graphics.FilterQuality
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
    externalScale: Float? = null,
    externalOffsetX: Float? = null,
    externalOffsetY: Float? = null,
    onTransformChanged: ((scale: Float, offsetX: Float, offsetY: Float) -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
    onTap: (() -> Unit)? = null
) {
    val halfWidth = sbsImage.width / 2
    val leftPainter = remember(sbsImage) {
        BitmapPainter(sbsImage, IntOffset.Zero, IntSize(halfWidth, sbsImage.height),
            filterQuality = FilterQuality.High)
    }
    val rightPainter = remember(sbsImage) {
        BitmapPainter(sbsImage, IntOffset(halfWidth, 0), IntSize(halfWidth, sbsImage.height),
            filterQuality = FilterQuality.High)
    }

    // Swap eyes if toggled
    val firstPainter = if (swapEyes) rightPainter else leftPainter
    val secondPainter = if (swapEyes) leftPainter else rightPainter

    // Use external state if provided (glasses follow phone), otherwise internal
    var internalScale by remember { mutableFloatStateOf(1f) }
    var internalOffsetX by remember { mutableFloatStateOf(0f) }
    var internalOffsetY by remember { mutableFloatStateOf(0f) }
    var lastDoubleTapTime by remember { mutableLongStateOf(0L) }

    val scale = externalScale ?: internalScale
    val offsetX = externalOffsetX ?: internalOffsetX
    val offsetY = externalOffsetY ?: internalOffsetY

    val isExternallyControlled = externalScale != null

    LaunchedEffect(sbsImage) {
        internalScale = 1f
        internalOffsetX = 0f
        internalOffsetY = 0f
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "Stereo 3D viewer" }
            .then(if (!isExternallyControlled) Modifier.pointerInput(onSwipeLeft, onSwipeRight) {
                fun updateTransform(s: Float, ox: Float, oy: Float) {
                    internalScale = s
                    internalOffsetX = ox
                    internalOffsetY = oy
                    onTransformChanged?.invoke(s, ox, oy)
                }

                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    firstDown.consume()

                    val now = System.currentTimeMillis()
                    if (now - lastDoubleTapTime < 300L) {
                        updateTransform(1f, 0f, 0f)
                        lastDoubleTapTime = 0L
                        return@awaitEachGesture
                    }
                    lastDoubleTapTime = now

                    var swipeDelta = 0f
                    var didZoom = false
                    var s = internalScale
                    var ox = internalOffsetX
                    var oy = internalOffsetY

                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }

                        if (pointerCount >= 2) {
                            didZoom = true
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (s * zoom).coerceIn(1f, 5f)

                            ox = ox * (newScale / s) + pan.x
                            oy = oy * (newScale / s) + pan.y
                            s = newScale

                            val maxPanX = size.width * (s - 1f) / 2f
                            val maxPanY = size.height * (s - 1f) / 2f
                            ox = ox.coerceIn(-maxPanX, maxPanX)
                            oy = oy.coerceIn(-maxPanY, maxPanY)
                            updateTransform(s, ox, oy)
                        } else if (pointerCount == 1) {
                            val pan = event.calculatePan()
                            if (s <= 1.01f && !didZoom) {
                                swipeDelta += pan.x
                            } else {
                                ox += pan.x
                                oy += pan.y

                                val maxPanX = size.width * (s - 1f) / 2f
                                val maxPanY = size.height * (s - 1f) / 2f
                                ox = ox.coerceIn(-maxPanX, maxPanX)
                                oy = oy.coerceIn(-maxPanY, maxPanY)
                                updateTransform(s, ox, oy)
                            }
                        }

                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    } while (event.changes.any { it.pressed })

                    if (s <= 1.01f && !didZoom) {
                        val threshold = size.width * 0.15f
                        if (abs(swipeDelta) > threshold) {
                            if (swipeDelta < 0) onSwipeLeft?.invoke()
                            else onSwipeRight?.invoke()
                        }
                    }

                    if (abs(swipeDelta) > 20f || didZoom) {
                        lastDoubleTapTime = 0L
                    } else if (abs(swipeDelta) < 10f && !didZoom) {
                        onTap?.invoke()
                    }
                }
            } else Modifier)
    ) {
        StereoPane(
            painter = firstPainter,
            contentDescription = "Left eye",
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
            halfSbsMode = halfSbsMode,
            modifier = Modifier.weight(1f)
        )
        StereoPane(
            painter = secondPainter,
            contentDescription = "Right eye",
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
            halfSbsMode = halfSbsMode,
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
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Half SBS: pre-squeeze horizontally by 50% so glasses' unsqueeze
                    // (2x width) restores correct aspect ratio. Works for all aspect ratios.
                    scaleX = if (halfSbsMode) scale * 0.5f else scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                }
        )
    }
}
