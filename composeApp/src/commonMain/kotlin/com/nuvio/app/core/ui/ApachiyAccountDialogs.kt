package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_ok
import nuvio.composeapp.generated.resources.device_limit_manage_devices
import nuvio.composeapp.generated.resources.device_limit_message
import nuvio.composeapp.generated.resources.device_limit_title
import nuvio.composeapp.generated.resources.subscription_inactive_manage
import nuvio.composeapp.generated.resources.subscription_inactive_message
import nuvio.composeapp.generated.resources.subscription_inactive_title
import org.jetbrains.compose.resources.stringResource

private const val DEVICES_DASHBOARD_URL = "https://apachiy.org/dashboard/devices"
private const val ACCOUNT_DASHBOARD_URL = "https://apachiy.org/dashboard"

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DeviceLimitDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    ApachiyMessageDialog(
        title = stringResource(Res.string.device_limit_title),
        message = stringResource(Res.string.device_limit_message),
        primaryLabel = stringResource(Res.string.device_limit_manage_devices),
        onPrimary = {
            runCatching { uriHandler.openUri(DEVICES_DASHBOARD_URL) }
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun InactiveSubscriptionDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    ApachiyMessageDialog(
        title = stringResource(Res.string.subscription_inactive_title),
        message = stringResource(Res.string.subscription_inactive_message),
        primaryLabel = stringResource(Res.string.subscription_inactive_manage),
        onPrimary = {
            runCatching { uriHandler.openUri(ACCOUNT_DASHBOARD_URL) }
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ApachiyMessageDialog(
    title: String,
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = onPrimary,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(primaryLabel)
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.action_ok))
                }
            }
        }
    }
}
