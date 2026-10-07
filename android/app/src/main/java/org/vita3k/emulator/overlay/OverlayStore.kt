package org.vita3k.emulator.overlay

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.DisplayMetrics
import org.vita3k.emulator.R

data class OverlayPosition @JvmOverloads constructor(
    val normalizedX: Float,
    val normalizedY: Float,
    val scale: Float = 1.0f
)

object OverlayStore {
    private const val PREFS_NAME = "overlay_settings"
    private const val LEGACY_PREFS_SUFFIX = "_preferences"
    private const val GLOBAL_SCOPE = "global"
    private const val GAME_SCOPE_PREFIX = "game:"
    private const val KEY_OVERRIDE_ENABLED = "override_enabled"
    private const val KEY_OVERLAY_MASK = "overlay_mask"
	private const val KEY_VISIBLE_BUTTONS = "visible_buttons"
    private const val KEY_OVERLAY_SCALE = "overlay_scale"
    private const val KEY_OVERLAY_OPACITY = "overlay_opacity"
    private const val KEY_HIDE_ON_CONTROLLER = "hide_overlay_on_controller"
    private const val KEY_HAPTIC_AMPLITUDE = "haptic_amplitude"
    private const val KEY_CONFIG_PREFIX = "config"
    private const val KEY_LAYOUT_PREFIX = "layout"
    private const val KEY_POSITION_X_SUFFIX = ":x"
    private const val KEY_POSITION_Y_SUFFIX = ":y"
	private const val KEY_POSITION_SCALE_SUFFIX = ":scale"
    private const val KEY_ORIENTATION_SUFFIX = ":orientation"

    // Listeners for overlay config changes (e.g., to notify running game and settings screen)
    private val onGameOverlayChangedListeners = mutableSetOf<(String) -> Unit>()

    @JvmStatic
    fun addOnGameOverlayChangedListener(listener: (String) -> Unit) {
        onGameOverlayChangedListeners.add(listener)
    }

    @JvmStatic
    fun removeOnGameOverlayChangedListener(listener: (String) -> Unit) {
        onGameOverlayChangedListeners.remove(listener)
    }

    private fun notifyGameOverlayChanged(scopeId: String) {
        onGameOverlayChangedListeners.forEach { it(scopeId) }
    }

    private data class OverlayPositionDefaults(
        val buttonType: Int,
        val xResId: Int,
        val yResId: Int
    )

    private val defaultEntries = listOf(
        OverlayPositionDefaults(ButtonType.BUTTON_CROSS, R.integer.BUTTON_CROSS_X, R.integer.BUTTON_CROSS_Y),
        OverlayPositionDefaults(ButtonType.BUTTON_CIRCLE, R.integer.BUTTON_CIRCLE_X, R.integer.BUTTON_CIRCLE_Y),
        OverlayPositionDefaults(ButtonType.BUTTON_SQUARE, R.integer.BUTTON_SQUARE_X, R.integer.BUTTON_SQUARE_Y),
        OverlayPositionDefaults(ButtonType.BUTTON_TRIANGLE, R.integer.BUTTON_TRIANGLE_X, R.integer.BUTTON_TRIANGLE_Y),
        OverlayPositionDefaults(ButtonType.BUTTON_SELECT, R.integer.BUTTON_SELECT_X, R.integer.BUTTON_SELECT_Y),
        OverlayPositionDefaults(ButtonType.BUTTON_START, R.integer.BUTTON_START_X, R.integer.BUTTON_START_Y),
        OverlayPositionDefaults(ButtonType.BUTTON_PS, R.integer.BUTTON_PS_X, R.integer.BUTTON_PS_Y),
        OverlayPositionDefaults(ButtonType.DPAD_UP, R.integer.DPAD_UP_X, R.integer.DPAD_UP_Y),
        OverlayPositionDefaults(ButtonType.STICK_LEFT, R.integer.STICK_LEFT_X, R.integer.STICK_LEFT_Y),
        OverlayPositionDefaults(ButtonType.STICK_RIGHT, R.integer.STICK_RIGHT_X, R.integer.STICK_RIGHT_Y),
        OverlayPositionDefaults(ButtonType.TRIGGER_L, R.integer.TRIGGER_L_X, R.integer.TRIGGER_L_Y),
        OverlayPositionDefaults(ButtonType.TRIGGER_R, R.integer.TRIGGER_R_X, R.integer.TRIGGER_R_Y),
        OverlayPositionDefaults(ButtonType.TRIGGER_L2, R.integer.TRIGGER_L2_X, R.integer.TRIGGER_L2_Y),
        OverlayPositionDefaults(ButtonType.TRIGGER_R2, R.integer.TRIGGER_R2_X, R.integer.TRIGGER_R2_Y),
        OverlayPositionDefaults(ButtonType.TRIGGER_L3, R.integer.TRIGGER_L3_X, R.integer.TRIGGER_L3_Y),
        OverlayPositionDefaults(ButtonType.TRIGGER_R3, R.integer.TRIGGER_R3_X, R.integer.TRIGGER_R3_Y),
        OverlayPositionDefaults(ButtonType.BUTTON_TOUCH_HIDE, R.integer.BUTTON_TOUCH_HIDE_X, R.integer.BUTTON_TOUCH_HIDE_Y),
        OverlayPositionDefaults(ButtonType.BUTTON_TOUCH_SWITCH, R.integer.BUTTON_TOUCH_SWITCH_X, R.integer.BUTTON_TOUCH_SWITCH_Y),
		OverlayPositionDefaults(ButtonType.TATACON, R.integer.BUTTON_TATACON_X, R.integer.BUTTON_TATACON_Y)
    )

    @JvmStatic
    fun normalizeScopeId(scopeId: String?): String = scopeId.orEmpty().trim()

    @JvmStatic
    fun hasGameOverride(context: Context, scopeId: String?): Boolean {
        val normalizedScopeId = normalizeScopeId(scopeId)
        if (normalizedScopeId.isEmpty()) {
            return false
        }
        return preferences(context).getBoolean(overrideEnabledKey(normalizedScopeId), false)
    }

    @JvmStatic
    fun loadGlobalState(context: Context): OverlayState {
        val prefs = preferences(context)
        val fallback = legacyGlobalState(context)
        return loadScopeState(prefs, scopeId = null, fallback = fallback)
    }

    @JvmStatic
    fun loadGameOverride(context: Context, scopeId: String?): OverlayState? {
        val normalizedScopeId = normalizeScopeId(scopeId)
        if (normalizedScopeId.isEmpty() || !hasGameOverride(context, normalizedScopeId)) {
            return null
        }
        return loadScopeState(preferences(context), normalizedScopeId, loadGlobalState(context))
    }

    @JvmStatic
    fun resolveState(context: Context, scopeId: String?, allowGameOverride: Boolean): OverlayState {
        val normalizedScopeId = normalizeScopeId(scopeId)
        val globalState = loadGlobalState(context)
        if (!allowGameOverride || normalizedScopeId.isEmpty() || !hasGameOverride(context, normalizedScopeId)) {
            return globalState
        }
        return loadScopeState(preferences(context), normalizedScopeId, globalState)
    }

    @JvmStatic
    fun saveGlobalState(context: Context, state: OverlayState) {
        saveScopeState(context, scopeId = null, state = state, markOverride = false)
    }

    @JvmStatic
    fun saveGameOverride(context: Context, scopeId: String?, state: OverlayState) {
        val normalizedScopeId = normalizeScopeId(scopeId)
        if (normalizedScopeId.isEmpty()) {
            saveGlobalState(context, state)
            return
        }
        saveScopeState(context, normalizedScopeId, state, markOverride = true)
        notifyGameOverlayChanged(normalizedScopeId)
    }

    @JvmStatic
    fun deleteGameOverride(context: Context, scopeId: String?) {
        val normalizedScopeId = normalizeScopeId(scopeId)
        if (normalizedScopeId.isEmpty()) {
            return
        }

        preferences(context).edit().apply {
            remove(overrideEnabledKey(normalizedScopeId))
            removeScopePayload(this, normalizedScopeId)
            apply()
        }
        notifyGameOverlayChanged(normalizedScopeId)
    }

    @JvmStatic
    fun clearAllGameOverrides(context: Context) {
        val prefs = preferences(context)
        prefs.edit().apply {
            prefs.all.keys
                .mapNotNull { key ->
                    if (!key.startsWith("$GAME_SCOPE_PREFIX")) {
                        null
                    } else {
                        key.removePrefix(GAME_SCOPE_PREFIX).substringBefore(':').takeIf { it.isNotBlank() }
                    }
                }
                .toSet()
                .forEach { scopeId ->
                    remove(overrideEnabledKey(scopeId))
                    removeScopePayload(this, scopeId)
                }
            apply()
        }
    }

    @JvmStatic
    fun defaultLayout(context: Context): OverlayLayout {
        val resources = context.resources
        val positions = defaultEntries.associate { entry ->
            entry.buttonType to OverlayPosition(
                normalizedX = (resources.getInteger(entry.xResId) / 1000f).coerceNormalizedCoordinate(),
                normalizedY = (resources.getInteger(entry.yResId) / 1000f).coerceNormalizedCoordinate()
            )
        }
        // Use same default positions for both portrait and landscape
        return OverlayLayout(positions, positions).normalized()
    }

    @JvmStatic
    fun defaultState(context: Context): OverlayState =
        OverlayState(
            config = OverlayConfig().normalized(),
            layout = defaultLayout(context)
        )

    @JvmStatic
    fun loadConfig(context: Context, scopeId: String? = null, allowGameOverride: Boolean = false): OverlayConfig =
        resolveState(context, scopeId, allowGameOverride).config

    @JvmStatic
    fun saveConfig(context: Context, config: OverlayConfig, scopeId: String? = null, useGameOverride: Boolean = false) {
        val existing = resolveState(context, scopeId, useGameOverride)
        val updated = existing.copy(config = config.normalized())
        if (useGameOverride && !normalizeScopeId(scopeId).isEmpty()) {
            saveGameOverride(context, scopeId, updated)
        } else {
            saveGlobalState(context, updated)
        }
    }

    @JvmStatic
    fun loadLayout(context: Context, scopeId: String? = null, allowGameOverride: Boolean = false): OverlayLayout =
        resolveState(context, scopeId, allowGameOverride).layout

    private fun loadScopeState(
        prefs: SharedPreferences,
        scopeId: String?,
        fallback: OverlayState
    ): OverlayState {
        val normalizedScopeId = normalizeScopeId(scopeId)
        val layout = loadStoredLayout(prefs, normalizedScopeId, fallback.layout)
        return OverlayState(
            config = loadStoredConfig(prefs, normalizedScopeId, fallback.config),
            layout = layout
        )
    }

    private fun saveScopeState(
        context: Context,
        scopeId: String?,
        state: OverlayState,
        markOverride: Boolean
    ) {
        val normalizedScopeId = normalizeScopeId(scopeId)
        val normalizedState = OverlayState(
            config = state.config.normalized(),
            layout = state.layout.normalized()
        )

        preferences(context).edit().apply {
            putInt(
                configKey(normalizedScopeId, KEY_OVERLAY_MASK),
                normalizedState.config.overlayMask
            )
            putLong(
                configKey(normalizedScopeId, KEY_VISIBLE_BUTTONS),
                normalizedState.config.visibleButtons
            )
            putInt(
                configKey(normalizedScopeId, KEY_OVERLAY_SCALE),
                normalizedState.config.overlayScale
            )
            putInt(
                configKey(normalizedScopeId, KEY_OVERLAY_OPACITY),
                normalizedState.config.overlayOpacity
            )
            putBoolean(
                configKey(normalizedScopeId, KEY_HIDE_ON_CONTROLLER),
                normalizedState.config.hideOverlayWhenControllerConnected
            )
            putInt(
                configKey(normalizedScopeId, KEY_HAPTIC_AMPLITUDE),
                normalizedState.config.hapticAmplitude
            )
            saveLayoutEntries(this, normalizedScopeId, normalizedState.layout)
            if (markOverride && normalizedScopeId.isNotEmpty()) {
                putBoolean(overrideEnabledKey(normalizedScopeId), true)
            }
            apply()
        }
    }

    private fun loadStoredConfig(
        prefs: SharedPreferences,
        scopeId: String,
        fallback: OverlayConfig
    ): OverlayConfig {
        val maskKey = configKey(scopeId, KEY_OVERLAY_MASK)
        val visibleButtonsKey = configKey(scopeId, KEY_VISIBLE_BUTTONS)
        val scaleKey = configKey(scopeId, KEY_OVERLAY_SCALE)
        val opacityKey = configKey(scopeId, KEY_OVERLAY_OPACITY)
        val hideOnControllerKey = configKey(scopeId, KEY_HIDE_ON_CONTROLLER)
        val hapticAmplitudeKey = configKey(scopeId, KEY_HAPTIC_AMPLITUDE)

        if (!prefs.contains(maskKey) && !prefs.contains(visibleButtonsKey)) {
            // 什么都没存过，用默认值
            return fallback
        }

        val overlayMask = prefs.getInt(maskKey, fallback.overlayMask)
        val visibleButtons = if (prefs.contains(visibleButtonsKey)) {
            prefs.getLong(visibleButtonsKey, fallback.visibleButtons)
        } else {
            // 新字段还没存过（老存档），从旧的分组掩码迁移一次
            migrateLegacyMask(overlayMask)
        }

        return OverlayConfig(
            overlayMask = overlayMask,
            visibleButtons = visibleButtons,
            overlayScale = prefs.getInt(scaleKey, fallback.overlayScale),
            overlayOpacity = prefs.getInt(opacityKey, fallback.overlayOpacity),
            hideOverlayWhenControllerConnected = prefs.getBoolean(
                hideOnControllerKey,
                fallback.hideOverlayWhenControllerConnected
            ),
            hapticAmplitude = prefs.getInt(hapticAmplitudeKey, fallback.hapticAmplitude)
        ).normalized()
    }

    // 把旧的"组"掩码展开成新的"每个按钮独立"掩码
    private fun migrateLegacyMask(legacyMask: Int): Long {
        if (legacyMask == 0) return 0L

        var result = 0L
        val basicOn = legacyMask and InputOverlay.OVERLAY_MASK_BASIC != 0
        val l2r2On = legacyMask and InputOverlay.OVERLAY_MASK_L2R2 != 0
        val l3r3On = legacyMask and InputOverlay.OVERLAY_MASK_L3R3 != 0
        val touchSwitchOn = legacyMask and InputOverlay.OVERLAY_MASK_TOUCH_SCREEN_SWITCH != 0
        val hideToggleOn = legacyMask and InputOverlay.OVERLAY_MASK_HIDE_TOGGLE != 0

        if (basicOn) {
            // BASIC 组里包含的所有按钮
            listOf(
                ButtonType.BUTTON_CROSS,
                ButtonType.BUTTON_CIRCLE,
                ButtonType.BUTTON_SQUARE,
                ButtonType.BUTTON_TRIANGLE,
                ButtonType.BUTTON_SELECT,
                ButtonType.BUTTON_START,
                ButtonType.BUTTON_PS,
                ButtonType.DPAD_UP,
                ButtonType.STICK_LEFT,
                ButtonType.STICK_RIGHT,
                ButtonType.TRIGGER_L,
                ButtonType.TRIGGER_R,
                ButtonType.TATACON
            ).forEach { result = result or OverlayButtonRegistry.bitFor(it) }
        }
        if (l2r2On) {
            result = result or OverlayButtonRegistry.bitFor(ButtonType.TRIGGER_L2)
            result = result or OverlayButtonRegistry.bitFor(ButtonType.TRIGGER_R2)
        }
        if (l3r3On) {
            result = result or OverlayButtonRegistry.bitFor(ButtonType.TRIGGER_L3)
            result = result or OverlayButtonRegistry.bitFor(ButtonType.TRIGGER_R3)
        }
        if (touchSwitchOn) {
            result =
                result or OverlayButtonRegistry.bitFor(ButtonType.BUTTON_TOUCH_SWITCH)
        }
        if (hideToggleOn) {
            result =
                result or OverlayButtonRegistry.bitFor(ButtonType.BUTTON_TOUCH_HIDE)
        }
        return result
    }

    private fun loadStoredLayout(
        prefs: SharedPreferences,
        scopeId: String,
        fallback: OverlayLayout
    ): OverlayLayout {
        // Check if any portrait or landscape entry is present
        val anyPortraitEntryPresent = defaultEntries.any { entry ->
            prefs.contains(positionKey(scopeId, entry.buttonType, KEY_POSITION_X_SUFFIX, true)) &&
                    prefs.contains(positionKey(scopeId, entry.buttonType, KEY_POSITION_Y_SUFFIX, true))
        }
        val anyLandscapeEntryPresent = defaultEntries.any { entry ->
            prefs.contains(positionKey(scopeId, entry.buttonType, KEY_POSITION_X_SUFFIX, false)) &&
                    prefs.contains(positionKey(scopeId, entry.buttonType, KEY_POSITION_Y_SUFFIX, false))
        }
        if (!anyPortraitEntryPresent && !anyLandscapeEntryPresent) {
            android.util.Log.d("TataconDebug", "loadStoredLayout: no entries found in prefs, using fallback")
            return fallback
        }

        android.util.Log.d("TataconDebug", "loadStoredLayout: portraitPresent=" + anyPortraitEntryPresent + " landscapePresent=" + anyLandscapeEntryPresent)

        val portraitPositions = defaultEntries.associate { entry ->
            val fallbackPosition = fallback.portraitPositions[entry.buttonType] ?: OverlayPosition(0f, 0f)
            entry.buttonType to OverlayPosition(
                normalizedX = prefs.getFloat(
                    positionKey(scopeId, entry.buttonType, KEY_POSITION_X_SUFFIX, true),
                    fallbackPosition.normalizedX
                ).coerceNormalizedCoordinate(),
                normalizedY = prefs.getFloat(
                    positionKey(scopeId, entry.buttonType, KEY_POSITION_Y_SUFFIX, true),
                    fallbackPosition.normalizedY
                ).coerceNormalizedCoordinate(),
				scale = prefs.getFloat(
					positionKey(scopeId, entry.buttonType, KEY_POSITION_SCALE_SUFFIX, true),
					fallbackPosition.scale
				).coerceIn(0.2f, 20.0f)
            )
        }

        val landscapePositions = defaultEntries.associate { entry ->
            val fallbackPosition = fallback.landscapePositions[entry.buttonType] ?: OverlayPosition(0f, 0f)
            entry.buttonType to OverlayPosition(
                normalizedX = prefs.getFloat(
                    positionKey(scopeId, entry.buttonType, KEY_POSITION_X_SUFFIX, false),
                    fallbackPosition.normalizedX
                ).coerceNormalizedCoordinate(),
                normalizedY = prefs.getFloat(
                    positionKey(scopeId, entry.buttonType, KEY_POSITION_Y_SUFFIX, false),
                    fallbackPosition.normalizedY
                ).coerceNormalizedCoordinate(),
				scale = prefs.getFloat(
					positionKey(scopeId, entry.buttonType, KEY_POSITION_SCALE_SUFFIX, false),
					fallbackPosition.scale
				).coerceIn(0.2f, 20.0f)
            )
        }

        val result = OverlayLayout(portraitPositions, landscapePositions).normalized()
        android.util.Log.d("TataconDebug", "loadStoredLayout result: portrait=" + result.portraitPositions.size + " landscape=" + result.landscapePositions.size)
        for ((btn, pos) in result.portraitPositions) {
            android.util.Log.d("TataconDebug", "loadStoredLayout portrait btn=" + btn + " x=" + pos.normalizedX + " y=" + pos.normalizedY)
        }
        return result
    }

    private fun legacyGlobalState(context: Context): OverlayState {
        val defaultState = defaultState(context)
        val legacyConfigPrefs = context.applicationContext.getSharedPreferences(
            "emulation_session",
            Context.MODE_PRIVATE
        )
        val config = if (legacyConfigPrefs.contains(KEY_OVERLAY_MASK)) {
            val legacyMask =
                legacyConfigPrefs.getInt(KEY_OVERLAY_MASK, defaultState.config.overlayMask)
            OverlayConfig(
                overlayMask = legacyMask,
                visibleButtons = migrateLegacyMask(legacyMask),
                overlayScale = legacyConfigPrefs.getInt(
                    KEY_OVERLAY_SCALE,
                    defaultState.config.overlayScale
                ),
                overlayOpacity = legacyConfigPrefs.getInt(
                    KEY_OVERLAY_OPACITY,
                    defaultState.config.overlayOpacity
                ),
                hideOverlayWhenControllerConnected = legacyConfigPrefs.getBoolean(
                    KEY_HIDE_ON_CONTROLLER,
                    defaultState.config.hideOverlayWhenControllerConnected
                )
            ).normalized()
        } else {
            defaultState.config
        }

        val legacyLayout = loadLegacyGlobalLayout(context, defaultState.layout)
        return OverlayState(
            config = config,
            layout = legacyLayout
        )
    }

    private fun loadLegacyGlobalLayout(context: Context, fallback: OverlayLayout): OverlayLayout {
        val legacyPrefs = context.applicationContext.getSharedPreferences("${context.packageName}$LEGACY_PREFS_SUFFIX", Context.MODE_PRIVATE)
        val anyEntryPresent = defaultEntries.any { entry ->
            legacyPrefs.contains(legacyPositionKey(entry.buttonType, "-X")) &&
                legacyPrefs.contains(legacyPositionKey(entry.buttonType, "-Y"))
        }
        if (!anyEntryPresent) {
            return fallback
        }

        val (width, height) = resolveLegacyOverlayBounds(context)
        val positions = defaultEntries.associate { entry ->
            val fallbackPosition = fallback.positionFor(entry.buttonType, false) ?: OverlayPosition(0f, 0f)
            val rawX = legacyPrefs.getFloat(legacyPositionKey(entry.buttonType, "-X"), fallbackPosition.normalizedX * width)
            val rawY = legacyPrefs.getFloat(legacyPositionKey(entry.buttonType, "-Y"), fallbackPosition.normalizedY * height)
            entry.buttonType to OverlayPosition(
                normalizedX = if (width > 0f) (rawX / width).coerceNormalizedCoordinate() else fallbackPosition.normalizedX,
                normalizedY = if (height > 0f) (rawY / height).coerceNormalizedCoordinate() else fallbackPosition.normalizedY
            )
        }
        return OverlayLayout(positions, positions).normalized()
    }

    private fun saveLayoutEntries(
        editor: SharedPreferences.Editor,
        scopeId: String,
        layout: OverlayLayout
    ) {
        defaultEntries.forEach { entry ->
            // Save portrait layout
            val portraitPosition = layout.portraitPositions[entry.buttonType] ?: OverlayPosition(0f, 0f)
            editor.putFloat(positionKey(scopeId, entry.buttonType, KEY_POSITION_X_SUFFIX, true), portraitPosition.normalizedX)
            editor.putFloat(positionKey(scopeId, entry.buttonType, KEY_POSITION_Y_SUFFIX, true), portraitPosition.normalizedY)
			editor.putFloat(positionKey(scopeId, entry.buttonType, KEY_POSITION_SCALE_SUFFIX, true), portraitPosition.scale)

            // Save landscape layout
            val landscapePosition = layout.landscapePositions[entry.buttonType] ?: OverlayPosition(0f, 0f)
            editor.putFloat(positionKey(scopeId, entry.buttonType, KEY_POSITION_X_SUFFIX, false), landscapePosition.normalizedX)
            editor.putFloat(positionKey(scopeId, entry.buttonType, KEY_POSITION_Y_SUFFIX, false), landscapePosition.normalizedY)
			editor.putFloat(positionKey(scopeId, entry.buttonType, KEY_POSITION_SCALE_SUFFIX, false), landscapePosition.scale)
        }
    }

    private fun removeScopePayload(editor: SharedPreferences.Editor, scopeId: String) {
        editor.remove(configKey(scopeId, KEY_OVERLAY_MASK))
		editor.remove(configKey(scopeId, KEY_VISIBLE_BUTTONS))
        editor.remove(configKey(scopeId, KEY_OVERLAY_SCALE))
        editor.remove(configKey(scopeId, KEY_OVERLAY_OPACITY))
        editor.remove(configKey(scopeId, KEY_HIDE_ON_CONTROLLER))
        defaultEntries.forEach { entry ->
            // Remove both portrait and landscape keys
            editor.remove(positionKey(scopeId, entry.buttonType, KEY_POSITION_X_SUFFIX, true))
            editor.remove(positionKey(scopeId, entry.buttonType, KEY_POSITION_Y_SUFFIX, true))
			editor.remove(positionKey(scopeId, entry.buttonType, KEY_POSITION_SCALE_SUFFIX, true))
            editor.remove(positionKey(scopeId, entry.buttonType, KEY_POSITION_X_SUFFIX, false))
            editor.remove(positionKey(scopeId, entry.buttonType, KEY_POSITION_Y_SUFFIX, false))
			editor.remove(positionKey(scopeId, entry.buttonType, KEY_POSITION_SCALE_SUFFIX, false))
            // Clean up old keys
            editor.remove(scopePrefix(scopeId) + ":" + KEY_LAYOUT_PREFIX + ":portrait:" + entry.buttonType + KEY_POSITION_X_SUFFIX)
            editor.remove(scopePrefix(scopeId) + ":" + KEY_LAYOUT_PREFIX + ":portrait:" + entry.buttonType + KEY_POSITION_Y_SUFFIX)
        }
    }

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun overrideEnabledKey(scopeId: String): String = "$GAME_SCOPE_PREFIX$scopeId:$KEY_OVERRIDE_ENABLED"

    private fun configKey(scopeId: String?, key: String): String =
        scopePrefix(scopeId) + ":" + KEY_CONFIG_PREFIX + ":" + key

    private fun positionKey(scopeId: String?, buttonType: Int, suffix: String, isPortrait: Boolean): String =
        scopePrefix(scopeId) + ":" + KEY_LAYOUT_PREFIX + ":" + (if (isPortrait) "portrait" else "landscape") + ":" + buttonType + suffix

    private fun scopePrefix(scopeId: String?): String {
        val normalizedScopeId = normalizeScopeId(scopeId)
        return if (normalizedScopeId.isEmpty()) {
            GLOBAL_SCOPE
        } else {
            GAME_SCOPE_PREFIX + normalizedScopeId
        }
    }

    private fun legacyPositionKey(buttonType: Int, axisSuffix: String): String = "$buttonType$axisSuffix"

    private fun resolveLegacyOverlayBounds(context: Context): Pair<Float, Float> {
        val activity = context as? Activity
        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = activity.windowManager.currentWindowMetrics.bounds
            return maxOf(bounds.width(), bounds.height()).toFloat() to minOf(bounds.width(), bounds.height()).toFloat()
        }

        val metrics = DisplayMetrics()
        if (activity != null) {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getMetrics(metrics)
        } else {
            metrics.setTo(context.resources.displayMetrics)
        }
        return maxOf(metrics.widthPixels, metrics.heightPixels).toFloat() to
            minOf(metrics.widthPixels, metrics.heightPixels).toFloat()
    }
}
