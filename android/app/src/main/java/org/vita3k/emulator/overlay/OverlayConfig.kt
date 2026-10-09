package org.vita3k.emulator.overlay

import android.content.Context

val DEFAULT_OVERLAY_MASK: Int =
    InputOverlay.OVERLAY_MASK_BASIC or InputOverlay.OVERLAY_MASK_TOUCH_SCREEN_SWITCH

data class OverlayConfig(
    @JvmField
    val overlayMask: Int = DEFAULT_OVERLAY_MASK,

    @JvmField
    val visibleButtons: Long = OverlayButtonRegistry.defaultMask(),

    @JvmField
    val overlayScale: Int = 100,

    @JvmField
    val overlayOpacity: Int = 100,

    @JvmField
    val hideOverlayWhenControllerConnected: Boolean = true,

    @JvmField
    val hapticAmplitude: Int = 255,

    @JvmField
    val screenOrientation: Int = 0, // 0=Auto, 1=Portrait, 2=Landscape

    @JvmField
    val portraitYOffset: Float = 0.0f,

    // Seconds of inactivity before the overlay auto-hides. 0 = Never.
    @JvmField
    val hideOverlayTimeSeconds: Int = 10
) {
    val controlsVisible: Boolean
        get() = overlayMask != 0

    val l2r2Visible: Boolean
        get() = overlayMask and InputOverlay.OVERLAY_MASK_L2R2 != 0

    val l3r3Visible: Boolean
        get() = overlayMask and InputOverlay.OVERLAY_MASK_L3R3 != 0

    val touchSwitchVisible: Boolean
        get() = overlayMask and InputOverlay.OVERLAY_MASK_TOUCH_SCREEN_SWITCH != 0

    val hideToggleVisible: Boolean
        get() = overlayMask and InputOverlay.OVERLAY_MASK_HIDE_TOGGLE != 0

    fun activeMask(): Int = if (overlayMask == 0) DEFAULT_OVERLAY_MASK else overlayMask

    fun withControlsVisible(visible: Boolean): OverlayConfig {
        return copy(overlayMask = if (visible) activeMask() else 0).normalized()
    }

    fun withL2R2Visible(visible: Boolean): OverlayConfig {
        val baseMask = activeMask()
        val updatedMask = if (visible) {
            baseMask or InputOverlay.OVERLAY_MASK_L2R2
        } else {
            baseMask and InputOverlay.OVERLAY_MASK_L2R2.inv()
        }
        // 同步 visibleButtons: 开启组时自动开启 L2/R2 单独bit，关闭组时关闭它们
        var updatedButtons = visibleButtons
        val l2Bit = OverlayButtonRegistry.bitFor(ButtonType.TRIGGER_L2)
        val r2Bit = OverlayButtonRegistry.bitFor(ButtonType.TRIGGER_R2)
        if (visible) {
            updatedButtons = updatedButtons or l2Bit or r2Bit
        } else {
            updatedButtons = updatedButtons and l2Bit.inv() and r2Bit.inv()
        }
        return copy(overlayMask = updatedMask, visibleButtons = updatedButtons).normalized()
    }

    fun withL3R3Visible(visible: Boolean): OverlayConfig {
        val baseMask = activeMask()
        val updatedMask = if (visible) {
            baseMask or InputOverlay.OVERLAY_MASK_L3R3
        } else {
            baseMask and InputOverlay.OVERLAY_MASK_L3R3.inv()
        }
        // 同步 visibleButtons: 开启组时自动开启 L3/R3 单独bit，关闭组时关闭它们
        var updatedButtons = visibleButtons
        val l3Bit = OverlayButtonRegistry.bitFor(ButtonType.TRIGGER_L3)
        val r3Bit = OverlayButtonRegistry.bitFor(ButtonType.TRIGGER_R3)
        if (visible) {
            updatedButtons = updatedButtons or l3Bit or r3Bit
        } else {
            updatedButtons = updatedButtons and l3Bit.inv() and r3Bit.inv()
        }
        return copy(overlayMask = updatedMask, visibleButtons = updatedButtons).normalized()
    }

    fun withTouchSwitchVisible(visible: Boolean): OverlayConfig {
        val baseMask = activeMask()
        val updatedMask = if (visible) {
            baseMask or InputOverlay.OVERLAY_MASK_TOUCH_SCREEN_SWITCH
        } else {
            baseMask and InputOverlay.OVERLAY_MASK_TOUCH_SCREEN_SWITCH.inv()
        }
        return copy(overlayMask = updatedMask).normalized()
    }

    fun withHideToggleVisible(visible: Boolean): OverlayConfig {
        val baseMask = activeMask()
        val updatedMask = if (visible) {
            baseMask or InputOverlay.OVERLAY_MASK_HIDE_TOGGLE
        } else {
            baseMask and InputOverlay.OVERLAY_MASK_HIDE_TOGGLE.inv()
        }
        return copy(overlayMask = updatedMask).normalized()
    }

    // ---- 新增:每个按钮独立开关(跟上面的组开关并存,取交集判断) ----

    fun isButtonVisible(legacyId: Int): Boolean =
        visibleButtons and OverlayButtonRegistry.bitFor(legacyId) != 0L

    fun withButtonVisible(legacyId: Int, visible: Boolean): OverlayConfig {
        val bit = OverlayButtonRegistry.bitFor(legacyId)
        val updated = if (visible) visibleButtons or bit else visibleButtons and bit.inv()
        return copy(visibleButtons = updated)
    }

    fun normalized(): OverlayConfig {
        val knownMask = InputOverlay.OVERLAY_MASK_BASIC or
            InputOverlay.OVERLAY_MASK_L2R2 or
            InputOverlay.OVERLAY_MASK_L3R3 or
            InputOverlay.OVERLAY_MASK_TOUCH_SCREEN_SWITCH or
            InputOverlay.OVERLAY_MASK_HIDE_TOGGLE
        val sanitizedMask = overlayMask.coerceAtLeast(0) and knownMask

        // The touch switch and hide toggle have dedicated visibility toggles (group mask
        // bits). Keep their per-button bits in sync with the group so a single toggle
        // fully controls the utility button (no second, redundant switch needed).
        val touchSwitchBit = OverlayButtonRegistry.bitFor(ButtonType.BUTTON_TOUCH_SWITCH)
        val hideToggleBit = OverlayButtonRegistry.bitFor(ButtonType.BUTTON_TOUCH_HIDE)
        var buttons = visibleButtons
        buttons = if (sanitizedMask and InputOverlay.OVERLAY_MASK_TOUCH_SCREEN_SWITCH != 0)
            buttons or touchSwitchBit
        else
            buttons and touchSwitchBit.inv()
        buttons = if (sanitizedMask and InputOverlay.OVERLAY_MASK_HIDE_TOGGLE != 0)
            buttons or hideToggleBit
        else
            buttons and hideToggleBit.inv()

        return copy(
            overlayMask = sanitizedMask,
            visibleButtons = buttons,
            overlayScale = overlayScale.coerceIn(50, 150),
            overlayOpacity = overlayOpacity.coerceIn(20, 100),
            hideOverlayTimeSeconds = hideOverlayTimeSeconds.coerceIn(0, 300)
        )
    }
}

object OverlayConfigStore {
    fun load(
        context: Context,
        scopeId: String? = null,
        allowGameOverride: Boolean = false
    ): OverlayConfig = OverlayStore.loadConfig(context, scopeId, allowGameOverride)

    fun save(
        context: Context,
        config: OverlayConfig,
        scopeId: String? = null,
        useGameOverride: Boolean = false
    ) {
        OverlayStore.saveConfig(context, config, scopeId, useGameOverride)
    }
}