package org.vita3k.emulator.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import org.vita3k.emulator.Emulator
import org.vita3k.emulator.NativeLib
import org.vita3k.emulator.data.NativeImeState
import org.vita3k.emulator.overlay.DEFAULT_OVERLAY_MASK
import org.vita3k.emulator.overlay.OverlayConfig
import org.vita3k.emulator.overlay.OverlayLayout
import org.vita3k.emulator.overlay.OverlayState
import org.vita3k.emulator.overlay.OverlayStore
import org.vita3k.emulator.overlay.OverlayButtonRegistry
import org.vita3k.emulator.overlay.InputOverlay

data class EmulationSessionUiState(
    val titleId: String = "",
    val gameTitle: String = "",
    val showMenu: Boolean = false,
    val isPaused: Boolean = false,
    val isEditingControls: Boolean = false,
    val wasPausedBeforeControlsEditor: Boolean = false,
    val showExitConfirmation: Boolean = false,
    val controlsVisible: Boolean = true,
    val overlayMask: Int = DEFAULT_OVERLAY_MASK,
	val visibleButtons: Long = OverlayButtonRegistry.defaultMask(),
    val l2r2Visible: Boolean = false,
    val l3r3Visible: Boolean = false,
    val touchSwitchVisible: Boolean = true,
    val hideToggleVisible: Boolean = false,
    val overlayScale: Int = 100,
    val overlayOpacity: Int = 100,
    val hideOverlayWhenControllerConnected: Boolean = true,
    val hapticAmplitude: Int = 255,
    val hideOverlayTimeSeconds: Int = 10,
    val controllerConnected: Boolean = false,
    val statusMessage: String? = null,
    val overlayLayout: OverlayLayout = OverlayLayout()
)

class EmulationSessionViewModel(application: Application) : AndroidViewModel(application) {

    private var initialized = false
    private var useGameOverlayOverride = false

    var uiState by mutableStateOf(EmulationSessionUiState())
        private set

    var imeState by mutableStateOf<NativeImeState?>(null)
        private set

    fun initialize(titleId: String?, gameTitle: String?) {
        if (initialized && uiState.titleId == titleId.orEmpty()) {
            return
        }

        val resolvedTitleId = titleId.orEmpty()
        val resolved = resolveOverlayState(resolvedTitleId)
        uiState = uiState
            .withOverlayConfig(resolved.second.config)
            .copy(
                titleId = resolvedTitleId,
                gameTitle = gameTitle.orEmpty(),
                overlayLayout = resolved.second.layout
            )
        useGameOverlayOverride = resolved.first
        initialized = true

        // Clear previous listener and register new one for overlay config changes from Settings screen
        OverlayStore.removeOnGameOverlayChangedListener(overlayChangeListener)
        if (resolvedTitleId.isNotBlank()) {
            OverlayStore.addOnGameOverlayChangedListener(overlayChangeListener)
        }
    }

    // Listener for overlay config changes from Settings screen
    private val overlayChangeListener = { scopeId: String ->
        if (scopeId == uiState.titleId) {
            reloadOverlayConfig()
        }
    }

    fun updateRunningApp(titleId: String?, gameTitle: String?) {
        val resolvedTitleId = titleId.orEmpty()
        val resolved = resolveOverlayState(resolvedTitleId)
        uiState = uiState
            .withOverlayConfig(resolved.second.config)
            .copy(
                titleId = resolvedTitleId,
                gameTitle = gameTitle.orEmpty(),
                overlayLayout = resolved.second.layout
            )
        useGameOverlayOverride = resolved.first
        initialized = true

        // Register listener for overlay config changes from Settings screen
        OverlayStore.removeOnGameOverlayChangedListener(overlayChangeListener)
        if (resolvedTitleId.isNotBlank()) {
            OverlayStore.addOnGameOverlayChangedListener(overlayChangeListener)
        }
    }

    private fun reloadOverlayConfig() {
        val resolvedTitleId = uiState.titleId
        if (resolvedTitleId.isBlank()) return

        val resolved = resolveOverlayState(resolvedTitleId)
        uiState = uiState
            .withOverlayConfig(resolved.second.config)
            .copy(overlayLayout = resolved.second.layout)
        // Apply the new config/layout to the running overlay immediately
        // Note: The emulator reference is not available here, but the next time
        // applyOverlayState is called (e.g., when menu opens/closes), it will use the new config
    }

    fun applyOverlayState(emulator: Emulator) {
        emulator.setControllerOverlayScale(uiState.overlayScale / 100f)
        emulator.setControllerOverlayOpacity(uiState.overlayOpacity)
        emulator.setControllerOverlayLayout(uiState.overlayLayout)
        val visibleMask = when {
            uiState.showMenu -> 0
            uiState.isEditingControls -> activeOverlayMask()
            uiState.hideOverlayWhenControllerConnected && uiState.controllerConnected -> 0
            else -> uiState.overlayMask
        }
        emulator.setControllerOverlayState(visibleMask, uiState.isEditingControls, false)
        emulator.setControllerOverlayConfig(currentOverlayConfig())
        if (uiState.isEditingControls) {
            emulator.setControllerOverlayEditMode(InputOverlay.EditMode.MOVE)
            emulator.setControllerOverlayAllowVirtualController(true)
        }
    }

    fun handleBackPressed(emulator: Emulator): Boolean {
        return when {
            uiState.isEditingControls -> {
                finishControlsEditor(emulator)
                true
            }
            uiState.showMenu -> {
                closeMenu(emulator)
                true
            }
            else -> {
                openMenu(emulator, pauseGame = false)
                true
            }
        }
    }

    @JvmOverloads
    fun openMenu(emulator: Emulator, pauseGame: Boolean = true) {
        if (uiState.showMenu) {
            return
        }

        emulator.prepareImeForPauseMenu()
        emulator.ensurePauseMenuOnTop()
        releaseInputs(emulator)
        val paused = if (pauseGame) {
            if (!setNativePauseReasonEnabled(NativeLib.PAUSE_REASON_MENU, true)) {
                return
            }
            true
        } else {
            isNativeAppPaused()
        }
        if (!setNativeInputIntercepted(true)) {
            if (pauseGame) {
                setNativePauseReasonEnabled(NativeLib.PAUSE_REASON_MENU, false)
            }
            return
        }
        uiState = uiState.copy(
            showMenu = true,
            isPaused = paused,
            isEditingControls = false,
            showExitConfirmation = false,
            statusMessage = null
        )
        emulator.setControllerOverlayState(0, false, false)
    }

    fun closeMenu(emulator: Emulator) {
        val wasPaused = uiState.isPaused

        releaseInputs(emulator)

        // Dismiss the overlay first: the running game must never be hidden behind
        // a menu that is still on screen, or players miss notes in that window.
        // Hiding the view directly means the dismissal does not wait for a
        // Compose frame.
        emulator.hidePauseMenuImmediately()
        uiState = uiState.copy(
            showMenu = false,
            isPaused = false,
            isEditingControls = false,
            showExitConfirmation = false,
            statusMessage = null
        )

        // Restore the controller overlay while the guest is still paused so the
        // resumed game can immediately read the overlay controls.
        applyOverlayState(emulator)

        setNativeInputIntercepted(false)
        if (wasPaused) {
            setNativePauseReasonEnabled(NativeLib.PAUSE_REASON_MENU, false)
        }
        emulator.restoreImeAfterPauseMenu()
    }

    fun togglePause(emulator: Emulator) {
        NativeLib.logDiagnostics("[PAUSEUI] togglePause")
        if (uiState.isPaused) {
            closeMenu(emulator)
        } else {
            releaseInputs(emulator)
            if (!setNativePauseReasonEnabled(NativeLib.PAUSE_REASON_MENU, true)) {
                return
            }
            uiState = uiState.copy(isPaused = true, statusMessage = null)
            emulator.setControllerOverlayState(0, false, false)
        }
    }

    fun requestExit() {
        uiState = uiState.copy(showExitConfirmation = true)
    }

    fun dismissExitConfirmation() {
        uiState = uiState.copy(showExitConfirmation = false)
    }

    fun confirmExit(emulator: Emulator) {
        releaseInputs(emulator)
        // Clear overlay change listener
        OverlayStore.removeOnGameOverlayChangedListener(overlayChangeListener)
        uiState = uiState.copy(showExitConfirmation = false, statusMessage = null)
        emulator.requestNativeQuit()
    }

    fun setControlsVisible(emulator: Emulator, visible: Boolean) {
        updateOverlayConfig(emulator) { it.withControlsVisible(visible) }
    }

    fun setL2R2Visible(emulator: Emulator, visible: Boolean) {
        updateOverlayConfig(emulator) { it.withL2R2Visible(visible) }
    }

    fun setL3R3Visible(emulator: Emulator, visible: Boolean) {
        updateOverlayConfig(emulator) { it.withL3R3Visible(visible) }
    }

    fun setTouchSwitchVisible(emulator: Emulator, visible: Boolean) {
        updateOverlayConfig(emulator) { it.withTouchSwitchVisible(visible) }
    }

    fun setHideToggleVisible(emulator: Emulator, visible: Boolean) {
        updateOverlayConfig(emulator) { it.withHideToggleVisible(visible) }
    }

    fun setOverlayScale(emulator: Emulator, value: Int) {
        updateOverlayConfig(emulator) { it.copy(overlayScale = value) }
    }

    fun setOverlayOpacity(emulator: Emulator, value: Int) {
        updateOverlayConfig(emulator) { it.copy(overlayOpacity = value) }
    }

    fun setControllerConnected(emulator: Emulator, connected: Boolean) {
        if (uiState.controllerConnected == connected) {
            return
        }

        uiState = uiState.copy(controllerConnected = connected)
        applyOverlayState(emulator)
    }

    fun startControlsEditor(emulator: Emulator) {
        val mask = activeOverlayMask()
        releaseInputs(emulator)
        uiState = uiState.copy(
            showMenu = false,
            isPaused = uiState.isPaused,
            isEditingControls = true,
            wasPausedBeforeControlsEditor = uiState.isPaused,
            controlsVisible = true,
            overlayMask = mask,
            showExitConfirmation = false,
            statusMessage = null
        )
        emulator.setControllerOverlayState(mask, true, false)
    }

    fun finishControlsEditor(emulator: Emulator) {
        releaseInputs(emulator)
        val updatedOverlayState = captureOverlayState(emulator)
        persistOverlayState(updatedOverlayState)
        // Apply the captured layout to the overlay to ensure in-memory state matches saved state
        emulator.setControllerOverlayLayout(updatedOverlayState.layout)
        uiState = uiState.withOverlayConfig(updatedOverlayState.config).copy(
            showMenu = true,
            isPaused = uiState.wasPausedBeforeControlsEditor,
            isEditingControls = false,
            wasPausedBeforeControlsEditor = false,
            statusMessage = null,
            // CRITICAL: keep the in-memory layout in sync with what was just captured &
            // persisted, otherwise the next applyOverlayState() (e.g. closeMenu after Done)
            // pushes the stale pre-edit layout back onto the overlay and the moved
            // positions appear to revert.
            overlayLayout = updatedOverlayState.layout
        )
        emulator.ensurePauseMenuOnTop()
        emulator.setControllerOverlayState(0, false, false)
    }

    fun resetControlsLayout(emulator: Emulator) {
        val defaultLayout = OverlayStore.defaultLayout(getApplication())
        emulator.setControllerOverlayLayout(defaultLayout)
        val updatedOverlayState = OverlayState(
            config = currentOverlayConfig(),
            layout = defaultLayout
        )
        persistOverlayState(updatedOverlayState)
        uiState = uiState.copy(statusMessage = null, overlayLayout = defaultLayout)
        applyOverlayState(emulator)
    }

    fun clearStatusMessage() {
        uiState = uiState.copy(statusMessage = null)
    }

    fun showStatusMessage(message: String) {
        uiState = uiState.copy(statusMessage = message)
    }

    fun updateImeState(state: NativeImeState?) {
        if (imeState == state) {
            return
        }

        imeState = state
    }

    fun currentOverlayConfig(): OverlayConfig {
        return OverlayConfig(
            overlayMask = uiState.overlayMask,
			visibleButtons = uiState.visibleButtons,
            overlayScale = uiState.overlayScale,
            overlayOpacity = uiState.overlayOpacity,
            hideOverlayWhenControllerConnected = uiState.hideOverlayWhenControllerConnected,
            hapticAmplitude = uiState.hapticAmplitude,
            hideOverlayTimeSeconds = uiState.hideOverlayTimeSeconds
        ).normalized()
    }

    fun updateOverlayConfig(emulator: Emulator, transform: (OverlayConfig) -> OverlayConfig) {
        val updatedOverlayState = captureOverlayState(
            emulator = emulator,
            config = transform(currentOverlayConfig()).normalized()
        )
        persistOverlayState(updatedOverlayState)
        uiState = uiState.withOverlayConfig(updatedOverlayState.config)
            .copy(overlayLayout = updatedOverlayState.layout)
        applyOverlayState(emulator)
    }

    fun promoteOverlayToGameOverride(emulator: Emulator) {
        val titleId = uiState.titleId
        if (titleId.isBlank()) {
            return
        }

        val captured = captureOverlayState(emulator)
        OverlayStore.saveGameOverride(getApplication(), titleId, captured)
        useGameOverlayOverride = true
        uiState = uiState.withOverlayConfig(captured.config).copy(overlayLayout = captured.layout)
    }

    fun clearGameOverlayOverride(emulator: Emulator) {
        val titleId = uiState.titleId
        if (titleId.isBlank()) {
            return
        }

        OverlayStore.deleteGameOverride(getApplication(), titleId)
        useGameOverlayOverride = false
        emulator.refreshControllerOverlayScope()
        val resolved = resolveOverlayState(titleId)
        useGameOverlayOverride = resolved.first
        uiState = uiState.withOverlayConfig(resolved.second.config)
            .copy(overlayLayout = resolved.second.layout)
        applyOverlayState(emulator)
    }

    private fun activeOverlayMask(): Int {
        return if (uiState.overlayMask == 0) DEFAULT_OVERLAY_MASK else uiState.overlayMask
    }

    private fun captureOverlayState(
        emulator: Emulator,
        config: OverlayConfig = currentOverlayConfig()
    ): OverlayState {
        return OverlayState(
            config = config.normalized(),
            layout = emulator.captureControllerOverlayLayout().normalized()
        )
    }

    private fun persistOverlayState(state: OverlayState) {
        if (useGameOverlayOverride && uiState.titleId.isNotBlank()) {
            OverlayStore.saveGameOverride(getApplication(), uiState.titleId, state)
        } else {
            OverlayStore.saveGlobalState(getApplication(), state)
        }
    }

    private fun resolveOverlayState(titleId: String): Pair<Boolean, OverlayState> {
        val useGameOverride = titleId.isNotBlank() && OverlayStore.hasGameOverride(getApplication(), titleId)
        val overlayState = OverlayStore.resolveState(
            context = getApplication(),
            scopeId = titleId,
            allowGameOverride = useGameOverride
        )
        return useGameOverride to overlayState
    }

    private fun releaseInputs(emulator: Emulator) {
        runCatching { emulator.releaseControllerOverlayInputs() }
    }

    private fun setNativePauseReasonEnabled(reasonMask: Int, enabled: Boolean): Boolean {
        return runCatching { NativeLib.setPauseReasonEnabled(reasonMask, enabled) }.getOrDefault(false)
    }

    private fun setNativeInputIntercepted(intercepted: Boolean): Boolean {
        return runCatching { NativeLib.setInputIntercepted(intercepted) }.getOrDefault(false)
    }

    private fun isNativeAppPaused(): Boolean {
        return runCatching { NativeLib.isAppPaused() }.getOrDefault(false)
    }

    private fun EmulationSessionUiState.withOverlayConfig(config: OverlayConfig): EmulationSessionUiState {
        val normalized = config.normalized()
        return copy(
            controlsVisible = normalized.controlsVisible,
            overlayMask = normalized.overlayMask,
			visibleButtons = normalized.visibleButtons,
            l2r2Visible = normalized.l2r2Visible,
            l3r3Visible = normalized.l3r3Visible,
            touchSwitchVisible = normalized.touchSwitchVisible,
            hideToggleVisible = normalized.hideToggleVisible,
            overlayScale = normalized.overlayScale,
            overlayOpacity = normalized.overlayOpacity,
            hideOverlayWhenControllerConnected = normalized.hideOverlayWhenControllerConnected,
            hapticAmplitude = normalized.hapticAmplitude,
            hideOverlayTimeSeconds = normalized.hideOverlayTimeSeconds
        )
    }
}
