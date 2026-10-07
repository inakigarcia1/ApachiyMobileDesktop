package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.nuvio.app.features.network.PlaybackActiveGuard
import com.nuvio.app.features.network.TorboxSpeedTestCoordinator
import com.nuvio.app.features.network.formatTorboxMbps
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_torbox_measure_now
import nuvio.composeapp.generated.resources.settings_playback_torbox_speed_last
import nuvio.composeapp.generated.resources.settings_playback_torbox_speed_none
import nuvio.composeapp.generated.resources.settings_playback_torbox_speed_title
import org.jetbrains.compose.resources.stringResource
import java.text.DateFormat
import java.util.Date

@Composable
internal fun TorboxSpeedSettingsGroup(
    isTablet: Boolean,
    modifier: Modifier = Modifier,
) {
    var refreshTick by remember { mutableStateOf(0) }
    val sample = remember(refreshTick) { TorboxSpeedTestCoordinator.lastSample() }
    val subtitle = when {
        sample == null -> stringResource(Res.string.settings_playback_torbox_speed_none)
        else -> {
            val whenText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(sample.measuredAtEpochMs))
            stringResource(
                Res.string.settings_playback_torbox_speed_last,
                formatTorboxMbps(sample.speedMbps),
                whenText,
            )
        }
    }
    TorboxSpeedPinSettingsRow(
        isTablet = isTablet,
        refreshTick = refreshTick,
        onRefresh = { refreshTick++ },
        modifier = modifier,
    )
    SettingsGroup(isTablet = isTablet, modifier = modifier) {
        SettingsNavigationRow(
            title = stringResource(Res.string.settings_playback_torbox_speed_title),
            description = subtitle,
            isTablet = isTablet,
            trailingContent = {
                Text(
                    text = stringResource(Res.string.settings_playback_torbox_measure_now),
                    style = MaterialTheme.typography.labelLarge,
                )
            },
            onClick = {
                if (PlaybackActiveGuard.isPlaybackActive) return@SettingsNavigationRow
                TorboxSpeedTestCoordinator.runManualMeasure {
                    refreshTick++
                }
            },
        )
    }
}
