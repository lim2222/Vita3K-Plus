package org.vita3k.emulator.ui.common

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
internal fun ImmersiveOrientationEffect(
    activity: Activity?,
    orientation: Int = 0,  // 0=Auto, 1=Portrait, 2=Landscape
    enabled: Boolean = true
) {
    DisposableEffect(activity, enabled, orientation) {
        if (activity == null || !enabled) {
            return@DisposableEffect onDispose { }
        }

        val previousOrientation = activity.requestedOrientation
        val window = activity.window
        val decorView = window.decorView
        val insetsController = WindowCompat.getInsetsController(window, decorView)
        val previousCutoutMode = window.attributes.layoutInDisplayCutoutMode

        // Apply orientation: 0=Auto(Sensor), 1=Portrait, 2=Landscape
        val targetOrientation = when (orientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
        activity.requestedOrientation = targetOrientation

        WindowCompat.setDecorFitsSystemWindows(window, false)
        insetsController.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            previousCutoutMode != android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        ) {
            val attributes = window.attributes
            attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attributes
        }

        decorView.post {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(window, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val attributes = window.attributes
                attributes.layoutInDisplayCutoutMode = previousCutoutMode
                window.attributes = attributes
            }
            activity.requestedOrientation = previousOrientation
        }
    }
}

// Deprecated alias for backward compatibility
@Composable
@Deprecated("Use ImmersiveOrientationEffect instead", ReplaceWith("ImmersiveOrientationEffect(activity, orientation = 2, enabled)"))
internal fun ImmersiveLandscapeEffect(
    activity: Activity?,
    enabled: Boolean = true
) {
    ImmersiveOrientationEffect(activity, orientation = 2, enabled = enabled)
}
