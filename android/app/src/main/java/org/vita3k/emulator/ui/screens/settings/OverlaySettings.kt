package org.vita3k.emulator.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.vita3k.emulator.ConnectedGamepad
import org.vita3k.emulator.R
import org.vita3k.emulator.data.EmulatorConfig
import org.vita3k.emulator.overlay.OverlayConfig
import org.vita3k.emulator.overlay.InputOverlay
import org.vita3k.emulator.overlay.OverlayButtonRegistry
import org.vita3k.emulator.overlay.ButtonType

private fun officialButtonName(legacyId: Int): String = when (legacyId) {
    ButtonType.BUTTON_CROSS -> "Cross"
    ButtonType.BUTTON_CIRCLE -> "Circle"
    ButtonType.BUTTON_SQUARE -> "Square"
    ButtonType.BUTTON_TRIANGLE -> "Triangle"
    ButtonType.BUTTON_SELECT -> "Select"
    ButtonType.BUTTON_START -> "Start"
    ButtonType.BUTTON_PS -> "PS Button"
    ButtonType.DPAD_UP -> "D-pad"
    ButtonType.STICK_LEFT -> "Left Stick"
    ButtonType.STICK_RIGHT -> "Right Stick"
    ButtonType.TRIGGER_L -> "L1"
    ButtonType.TRIGGER_R -> "R1"
    ButtonType.TRIGGER_L2 -> "L2"
    ButtonType.TRIGGER_R2 -> "R2"
    ButtonType.TRIGGER_L3 -> "L3"
    ButtonType.TRIGGER_R3 -> "R3"
    ButtonType.BUTTON_TOUCH_SWITCH -> "Touch Switch"
    ButtonType.BUTTON_TOUCH_HIDE -> "Hide Toggle"
	ButtonType.TATACON -> "Taiko Drum"
    else -> "Button $legacyId"
}

@Composable
internal fun OverlaySettingsSection(
    cfg: EmulatorConfig,
    overlayConfig: OverlayConfig,
    isPerApp: Boolean,
    connectedGamepads: List<ConnectedGamepad>,
    onUpdate: (EmulatorConfig.() -> Unit) -> Unit,
    onOverlayConfigChange: (OverlayConfig) -> Unit,
    onShowHelp: (SettingsHelpEntry) -> Unit,
    controllerConnected: Boolean = false,
    onStartControlsEditor: (() -> Unit)? = null,
    onResetControlsLayout: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSectionCard(
            title = stringResource(R.string.settings_tab_controls),
            summary = null,
            help = null,
            onShowHelp = onShowHelp
        ) {
            OverlaySettingsRows(
                overlayConfig = overlayConfig,
                onOverlayConfigChange = onOverlayConfigChange,
                onShowHelp = onShowHelp,
                controllerConnected = controllerConnected,
                onStartControlsEditor = onStartControlsEditor,
                onResetControlsLayout = onResetControlsLayout
            )
        }

        if (!isPerApp) {
            ControllerMappingSections(
                cfg = cfg,
                connectedGamepads = connectedGamepads,
                onUpdate = onUpdate,
                onShowHelp = onShowHelp
            )
        }
    }
}

@Composable
internal fun OverlaySettingsRows(
    overlayConfig: OverlayConfig,
    onOverlayConfigChange: (OverlayConfig) -> Unit,
    onShowHelp: (SettingsHelpEntry) -> Unit,
    controllerConnected: Boolean = false,
    onStartControlsEditor: (() -> Unit)? = null,
    onResetControlsLayout: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onStartControlsEditor != null || onResetControlsLayout != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (onStartControlsEditor != null) {
                    FilledTonalButton(
                        onClick = onStartControlsEditor,
                        enabled = overlayConfig.controlsVisible,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.TouchApp, contentDescription = null)
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        androidx.compose.material3.Text(stringResource(R.string.emulation_controls_edit))
                    }
                }
                if (onResetControlsLayout != null) {
                    OutlinedButton(
                        onClick = onResetControlsLayout,
                        enabled = overlayConfig.controlsVisible,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null)
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        androidx.compose.material3.Text(stringResource(R.string.action_reset))
                    }
                }
            }
        }

        SettingsToggleRow(
            title = stringResource(R.string.settings_controls_show),
            checked = overlayConfig.controlsVisible,
            onCheckedChange = { onOverlayConfigChange(overlayConfig.withControlsVisible(it)) },
            help = SettingsHelpEntry(
                title = stringResource(R.string.settings_controls_show),
                body = stringResource(R.string.settings_controls_show_desc),
                scope = SettingsScope.Global
            ),
            onShowHelp = onShowHelp
        )

        OverlayButtonRegistry.ids.filter { id ->
            id != ButtonType.TRIGGER_L2 &&
            id != ButtonType.TRIGGER_R2 &&
            id != ButtonType.TRIGGER_L3 &&
            id != ButtonType.TRIGGER_R3 &&
            // Utility buttons (Touch Switch / Hide Toggle) have their own dedicated
            // toggle rows below; showing them here too would require enabling two
            // switches for a single button.
            id != ButtonType.BUTTON_TOUCH_SWITCH &&
            id != ButtonType.BUTTON_TOUCH_HIDE
        }.forEach { id ->
            SettingsToggleRow(
                title = officialButtonName(id),
                checked = overlayConfig.isButtonVisible(id),
                onCheckedChange = { onOverlayConfigChange(overlayConfig.withButtonVisible(id, it)) },
                enabled = overlayConfig.controlsVisible,
                onShowHelp = onShowHelp
            )
        }

        SettingsToggleRow(
            title = stringResource(R.string.settings_controls_hide_on_controller),
            checked = overlayConfig.hideOverlayWhenControllerConnected,
            onCheckedChange = {
                onOverlayConfigChange(overlayConfig.copy(hideOverlayWhenControllerConnected = it))
            },
            summary = if (controllerConnected) {
                stringResource(R.string.settings_controls_controller_connected)
            } else {
                stringResource(R.string.settings_controls_controller_disconnected)
            },
            help = SettingsHelpEntry(
                title = stringResource(R.string.settings_controls_hide_on_controller),
                body = stringResource(R.string.settings_controls_hide_on_controller_desc),
                scope = SettingsScope.Global
            ),
            onShowHelp = onShowHelp
        )

        val hideOverlayTimeOptions = listOf(0, 10, 15, 20, 30, 45, 60, 120, 180, 300)
        val hideOverlayTimeLabels = hideOverlayTimeOptions.map { seconds ->
            when {
                seconds == 0 -> stringResource(R.string.settings_controls_hide_overlay_time_never)
                seconds < 60 -> stringResource(R.string.settings_controls_hide_overlay_time_seconds, seconds)
                else -> stringResource(R.string.settings_controls_hide_overlay_time_minutes, seconds / 60)
            }
        }
        val hideTimeIndex = hideOverlayTimeOptions.indices
            .minByOrNull { kotlin.math.abs(hideOverlayTimeOptions[it] - overlayConfig.hideOverlayTimeSeconds) }
            ?: 0
        SettingsScrollableChoiceSelector(
            title = stringResource(R.string.settings_controls_hide_overlay_time),
            options = hideOverlayTimeLabels,
            selectedIndex = hideTimeIndex,
            onSelected = { index ->
                onOverlayConfigChange(
                    overlayConfig.copy(hideOverlayTimeSeconds = hideOverlayTimeOptions[index])
                )
            },
            help = SettingsHelpEntry(
                title = stringResource(R.string.settings_controls_hide_overlay_time),
                body = stringResource(R.string.settings_controls_hide_overlay_time_desc),
                scope = SettingsScope.Global
            ),
            onShowHelp = onShowHelp
        )

        SettingsToggleRow(
            title = stringResource(R.string.settings_controls_l2r2),
            checked = overlayConfig.l2r2Visible,
            onCheckedChange = { onOverlayConfigChange(overlayConfig.withL2R2Visible(it)) },
            enabled = overlayConfig.controlsVisible,
            help = SettingsHelpEntry(
                title = stringResource(R.string.settings_controls_l2r2),
                body = stringResource(R.string.settings_controls_l2r2_desc),
                scope = SettingsScope.Global
            ),
            onShowHelp = onShowHelp
        )

        SettingsSliderRow(
            title = stringResource(R.string.settings_controls_haptic_strength),
            valueLabel = overlayConfig.hapticAmplitude.toString(),
            value = overlayConfig.hapticAmplitude.toFloat(),
            onValueChange = {
                onOverlayConfigChange(overlayConfig.copy(hapticAmplitude = it.toInt()))
            },
            valueRange = 0f..255f,
            steps = 254,
            help = SettingsHelpEntry(
                title = stringResource(R.string.settings_controls_haptic_strength),
                body = stringResource(R.string.settings_controls_haptic_strength_desc),
                scope = SettingsScope.Global
            ),
            onShowHelp = onShowHelp
        )
        SettingsToggleRow(
            title = stringResource(R.string.settings_controls_l3r3),
            checked = overlayConfig.l3r3Visible,
            onCheckedChange = { onOverlayConfigChange(overlayConfig.withL3R3Visible(it)) },
            enabled = overlayConfig.controlsVisible,
            help = SettingsHelpEntry(
                title = stringResource(R.string.settings_controls_l3r3),
                body = stringResource(R.string.settings_controls_l3r3_desc),
                scope = SettingsScope.Global
            ),
            onShowHelp = onShowHelp
        )
        SettingsToggleRow(
            title = stringResource(R.string.settings_controls_touch_switch),
            checked = overlayConfig.touchSwitchVisible,
            onCheckedChange = { onOverlayConfigChange(overlayConfig.withTouchSwitchVisible(it)) },
            enabled = overlayConfig.controlsVisible,
            help = SettingsHelpEntry(
                title = stringResource(R.string.settings_controls_touch_switch),
                body = stringResource(R.string.settings_controls_touch_switch_desc),
                scope = SettingsScope.Global
            ),
            onShowHelp = onShowHelp
        )
        SettingsToggleRow(
            title = stringResource(R.string.settings_controls_hide_toggle),
            checked = overlayConfig.hideToggleVisible,
            onCheckedChange = { onOverlayConfigChange(overlayConfig.withHideToggleVisible(it)) },
            enabled = overlayConfig.controlsVisible,
            help = SettingsHelpEntry(
                title = stringResource(R.string.settings_controls_hide_toggle),
                body = stringResource(R.string.settings_controls_hide_toggle_desc),
                scope = SettingsScope.Global
            ),
            onShowHelp = onShowHelp
        )
        SettingsSliderRow(
            title = stringResource(R.string.settings_controls_scale),
            valueLabel = stringResource(R.string.settings_controls_percent_value, overlayConfig.overlayScale),
            value = overlayConfig.overlayScale.toFloat(),
            onValueChange = {
                onOverlayConfigChange(overlayConfig.copy(overlayScale = it.toInt()))
            },
            valueRange = 50f..150f,
            steps = 99,
            enabled = overlayConfig.controlsVisible,
            help = SettingsHelpEntry(
                title = stringResource(R.string.settings_controls_scale),
                body = stringResource(R.string.settings_controls_scale_desc),
                scope = SettingsScope.Global
            ),
            onShowHelp = onShowHelp
        )
        SettingsSliderRow(
            title = stringResource(R.string.settings_controls_opacity),
            valueLabel = stringResource(R.string.settings_controls_percent_value, overlayConfig.overlayOpacity),
            value = overlayConfig.overlayOpacity.toFloat(),
            onValueChange = {
                onOverlayConfigChange(overlayConfig.copy(overlayOpacity = it.toInt()))
            },
            valueRange = 20f..100f,
            steps = 79,
            enabled = overlayConfig.controlsVisible,
            help = SettingsHelpEntry(
                title = stringResource(R.string.settings_controls_opacity),
                body = stringResource(R.string.settings_controls_opacity_desc),
                scope = SettingsScope.Global
            ),
            onShowHelp = onShowHelp
        )
    }
}