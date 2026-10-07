package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.build.ApachiyProductSettings
import com.nuvio.app.features.network.TorboxSpeedTestHarness
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_torbox_pin_action
import nuvio.composeapp.generated.resources.settings_playback_torbox_pin_clear
import nuvio.composeapp.generated.resources.settings_playback_torbox_pin_dialog_subtitle
import nuvio.composeapp.generated.resources.settings_playback_torbox_pin_dialog_title
import nuvio.composeapp.generated.resources.settings_playback_torbox_pin_invalid
import nuvio.composeapp.generated.resources.settings_playback_torbox_pin_save
import nuvio.composeapp.generated.resources.settings_playback_torbox_pin_subtitle
import nuvio.composeapp.generated.resources.settings_playback_torbox_pin_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TorboxSpeedPinSettingsRow(
    isTablet: Boolean,
    refreshTick: Int,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!ApachiyProductSettings.operatorSettingsVisible) return
    var showDialog by remember { mutableStateOf(false) }
    val pinned = remember(refreshTick) { TorboxSpeedTestHarness.readPinnedMbps() }
    SettingsGroup(isTablet = isTablet, modifier = modifier) {
        SettingsNavigationRow(
            title = stringResource(Res.string.settings_playback_torbox_pin_title),
            description = stringResource(Res.string.settings_playback_torbox_pin_subtitle) +
                if (pinned != null) " · ${pinned} Mbps" else "",
            isTablet = isTablet,
            trailingContent = {
                Text(
                    text = stringResource(Res.string.settings_playback_torbox_pin_action),
                    style = MaterialTheme.typography.labelLarge,
                )
            },
            onClick = { showDialog = true },
        )
    }
    if (showDialog) {
        TorboxPinMbpsDialog(
            initialMbps = pinned,
            onDismiss = { showDialog = false },
            onSaved = {
                showDialog = false
                onRefresh()
            },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TorboxPinMbpsDialog(
    initialMbps: Double?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var text by remember {
        mutableStateOf(
            initialMbps?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty(),
        )
    }
    var invalid by remember { mutableStateOf(false) }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_torbox_pin_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.settings_playback_torbox_pin_dialog_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingsSecretTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        invalid = false
                    },
                    label = "Mbps",
                    modifier = Modifier.fillMaxWidth(),
                    isError = invalid,
                )
                if (invalid) {
                    Text(
                        text = stringResource(Res.string.settings_playback_torbox_pin_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = {
                        TorboxSpeedTestHarness.setPinnedMbps(null)
                        onSaved()
                    }) {
                        Text(stringResource(Res.string.settings_playback_torbox_pin_clear))
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    TextButton(onClick = {
                        val mbps = text.trim().replace(',', '.').toDoubleOrNull()
                        if (mbps == null || mbps <= 0.0) {
                            invalid = true
                            return@TextButton
                        }
                        TorboxSpeedTestHarness.setPinnedMbps(mbps)
                        onSaved()
                    }) {
                        Text(stringResource(Res.string.settings_playback_torbox_pin_save))
                    }
                }
            }
        }
    }
}
