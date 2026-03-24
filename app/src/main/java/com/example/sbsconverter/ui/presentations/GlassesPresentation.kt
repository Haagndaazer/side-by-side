package com.example.sbsconverter.ui.presentations

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.sbsconverter.ui.components.StereoViewer
import com.example.sbsconverter.ui.theme.SBSConverterTheme

/**
 * Renders StereoViewer on an external display (e.g., XREAL AR glasses).
 * Uses a custom LifecycleOwner since Presentation doesn't provide one
 * and ComposeView requires LifecycleOwner + SavedStateRegistryOwner +
 * ViewModelStoreOwner on its view tree.
 */
class GlassesPresentation(
    context: Context,
    display: Display,
    private val onDismissed: () -> Unit = {}
) : Presentation(context, display) {

    private val _sbsImage = mutableStateOf<ImageBitmap?>(null)
    private val _swapEyes = mutableStateOf(false)
    private val _scale = mutableStateOf(1f)
    private val _offsetX = mutableStateOf(0f)
    private val _offsetY = mutableStateOf(0f)

    private val lifecycleOwner = PresentationLifecycleOwner()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setOnDismissListener { onDismissed() }

        // Initialize lifecycle
        lifecycleOwner.performRestore(null)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // Set all three ViewTree owners on the Presentation's DecorView
        // so ComposeView finds them when walking up the hierarchy
        window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(lifecycleOwner)
            decorView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            decorView.setViewTreeViewModelStoreOwner(lifecycleOwner)
        }

        setContentView(ComposeView(context).apply {
            setContent {
                SBSConverterTheme {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                    ) {
                        _sbsImage.value?.let { image ->
                            StereoViewer(
                                sbsImage = image,
                                modifier = Modifier.fillMaxSize(),
                                halfSbsMode = true,
                                swapEyes = _swapEyes.value,
                                externalScale = _scale.value,
                                externalOffsetX = _offsetX.value,
                                externalOffsetY = _offsetY.value
                            )
                        }
                    }
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStop() {
        super.onStop()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun dismiss() {
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.dismiss()
    }

    fun updateImage(image: ImageBitmap) {
        _sbsImage.value = image
    }

    fun updateSwapEyes(swap: Boolean) {
        _swapEyes.value = swap
    }

    fun updateTransform(scale: Float, offsetX: Float, offsetY: Float) {
        _scale.value = scale
        _offsetX.value = offsetX
        _offsetY.value = offsetY
    }
}

/**
 * Custom LifecycleOwner for the Presentation window.
 * ComposeView requires all three: LifecycleOwner, SavedStateRegistryOwner,
 * and ViewModelStoreOwner on its ViewTree ancestors.
 */
private class PresentationLifecycleOwner :
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }

    fun performRestore(savedState: Bundle?) {
        savedStateRegistryController.performRestore(savedState)
    }
}
