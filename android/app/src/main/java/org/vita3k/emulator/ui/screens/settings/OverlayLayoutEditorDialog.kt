package org.vita3k.emulator.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.vita3k.emulator.overlay.InputOverlay
import org.vita3k.emulator.overlay.OverlayConfig
import org.vita3k.emulator.overlay.OverlayLayout
import org.vita3k.emulator.overlay.OverlayStore
import org.vita3k.emulator.ui.common.ImmersiveOrientationEffect
import org.vita3k.emulator.ui.components.OverlayEditorPalette
import java.lang.reflect.Method

@Composable
internal fun OverlayLayoutEditorDialog(
    overlayConfig: OverlayConfig,
    overlayLayout: OverlayLayout,
    screenOrientation: Int = 0,
    onDismiss: (OverlayLayout) -> Unit
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    var overlayView by remember { mutableStateOf<InputOverlay?>(null) }
    var editMode by remember { mutableStateOf(InputOverlay.EditMode.MOVE) }

    // Use reflection to call InputOverlay methods (Java compilation happens after Kotlin)
    val setLayoutMethod = InputOverlay::class.java.getMethod("setLayout", OverlayLayout::class.java)
    val captureLayoutMethod = InputOverlay::class.java.getMethod("captureLayout")
    val setAllowVirtualControllerMethod = InputOverlay::class.java.getMethod("setAllowVirtualController", Boolean::class.java)
    val setAutoHideEnabledMethod = InputOverlay::class.java.getMethod("setAutoHideEnabled", Boolean::class.java)
    val setIsInEditModeMethod = InputOverlay::class.java.getMethod("setIsInEditMode", Boolean::class.java)
    val setScaleMethod = InputOverlay::class.java.getMethod("setScale", Float::class.java)
    val setOpacityMethod = InputOverlay::class.java.getMethod("setOpacity", Int::class.java)
    val setStateMethod = InputOverlay::class.java.getMethod("setState", Int::class.java)
    val setEditModeMethod = InputOverlay::class.java.getMethod("setEditMode", InputOverlay.EditMode::class.java)
    val setScreenOrientationMethod = InputOverlay::class.java.getMethod("setScreenOrientation", Int::class.java)
    val setDeviceNativeRotationMethod = InputOverlay::class.java.getMethod("setDeviceNativeRotation", Int::class.java)
    val setOverlayConfigMethod = InputOverlay::class.java.getMethod("setOverlayConfig", OverlayConfig::class.java)

    // Mirror the display rotation so the editor edits the same orientation map
    // the in-game overlay uses (Surface rotation constants 0..3).
    fun applyOrientation(target: InputOverlay?) {
        if (target == null) return
        try {
            setScreenOrientationMethod.invoke(target, screenOrientation)
            val rotation = activity?.windowManager?.defaultDisplay?.rotation ?: 0
            setDeviceNativeRotationMethod.invoke(target, rotation)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun finishEditor() {
        val layout = try {
            val result = captureLayoutMethod.invoke(overlayView)
            android.util.Log.d("TataconDebug", "finishEditor captured=" + (result != null) + " type=" + result?.javaClass?.simpleName)
            result
        } catch (e: Exception) {
            android.util.Log.e("TataconDebug", "finishEditor captureLayout FAILED", e)
            overlayLayout
        } as? OverlayLayout ?: overlayLayout
        android.util.Log.d("TataconDebug", "finishEditor onDismiss layout.portrait=" + layout.portraitPositions.size + " landscape=" + layout.landscapePositions.size)
        onDismiss(layout)
    }

    ImmersiveOrientationEffect(activity = activity, orientation = screenOrientation)
    BackHandler(onBack = ::finishEditor)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { viewContext ->
                InputOverlay(viewContext).apply {
                    overlayView = this
                    try {
                        // Apply the scope's config (per-app for Custom Config) so button
                        // visibility toggles match what this settings screen shows,
                        // instead of the global config loaded by the constructor.
                        setOverlayConfigMethod.invoke(this, overlayConfig)
                        applyOrientation(this)
                        setLayoutMethod.invoke(this, overlayLayout)
                        setAllowVirtualControllerMethod.invoke(this, false)
                        setAutoHideEnabledMethod.invoke(this, false)
                        setIsInEditModeMethod.invoke(this, true)
                        setScaleMethod.invoke(this, overlayConfig.overlayScale / 100f)
                        setOpacityMethod.invoke(this, overlayConfig.overlayOpacity)
                        setStateMethod.invoke(this, overlayConfig.activeMask())
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            },
            update = { view ->
                overlayView = view
                try {
                    setOverlayConfigMethod.invoke(view, overlayConfig)
                    applyOrientation(view)
                    setAllowVirtualControllerMethod.invoke(view, false)
                    setAutoHideEnabledMethod.invoke(view, false)
                    setIsInEditModeMethod.invoke(view, true)
                    setScaleMethod.invoke(view, overlayConfig.overlayScale / 100f)
                    setOpacityMethod.invoke(view, overlayConfig.overlayOpacity)
                    setStateMethod.invoke(view, overlayConfig.activeMask())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        OverlayEditorPalette(
            onDone = ::finishEditor,
            onReset = {
                try {
                    setLayoutMethod.invoke(overlayView, OverlayStore.defaultLayout(context))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            },
            editMode = editMode,
            onEditModeChange = { mode ->
                editMode = mode
                try {
                    setEditModeMethod.invoke(overlayView, mode)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}