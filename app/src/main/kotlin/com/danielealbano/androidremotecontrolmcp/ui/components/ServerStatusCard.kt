@file:Suppress("FunctionNaming", "MagicNumber", "UnusedPrivateMember", "LongMethod", "LongParameterList")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.services.control.ControlSessionState
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme

private const val STATUS_DOT_SIZE_DP = 12
private const val ANIMATION_DURATION_MS = 300

@Composable
fun ServerStatusCard(
    serverStatus: ServerStatus,
    channelStatus: ChannelConnectionStatus,
    channelEnabled: Boolean,
    onMcpStartClick: () -> Unit,
    onMcpStopClick: () -> Unit,
    onChannelStartClick: () -> Unit,
    onChannelStopClick: () -> Unit,
    startEnabled: Boolean,
    modifier: Modifier = Modifier,
    controlState: ControlSessionState = ControlSessionState(),
    onControlStartClick: () -> Unit = {},
    onControlExitClick: () -> Unit = {},
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.orulink_service_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = serverStatusToText(serverStatus),
                style = MaterialTheme.typography.headlineSmall,
                color = serverStatusToColor(serverStatus),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = serviceDescription(serverStatus),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = if (serverStatus is ServerStatus.Running) onMcpStopClick else onMcpStartClick,
                enabled = mcpStartStopButtonEnabled(serverStatus, startEnabled),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        when (serverStatus) {
                            is ServerStatus.Running -> R.string.orulink_stop_service
                            is ServerStatus.Error -> R.string.orulink_retry_service
                            else -> R.string.orulink_start_service
                        },
                    ),
                )
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))
            ControlModeControls(
                serverStatus = serverStatus,
                state = controlState,
                onStart = onControlStartClick,
                onExit = onControlExitClick,
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))
            ServiceRow(
                label = stringResource(R.string.orulink_channel_title),
                statusText = channelStatusToText(channelStatus, channelEnabled),
                statusColor = channelStatusToColor(channelStatus, channelEnabled),
                buttonText = stringResource(if (channelEnabled) R.string.orulink_stop else R.string.orulink_start),
                buttonEnabled = channelStartStopButtonEnabled(channelEnabled, startEnabled),
                onButtonClick = if (channelEnabled) onChannelStopClick else onChannelStartClick,
            )
        }
    }
}

@Composable
private fun serviceDescription(status: ServerStatus): String {
    if (status is ServerStatus.Error) return status.message
    return stringResource(
        when (status) {
            is ServerStatus.Running -> R.string.orulink_service_running
            is ServerStatus.Starting -> R.string.orulink_service_starting
            is ServerStatus.Stopping -> R.string.orulink_service_stopping
            else -> R.string.orulink_service_stopped
        },
    )
}

/**
 * Whether the MCP Server start/stop button is enabled. Stop (Running) is always enabled;
 * Start or retry requires [startEnabled] (accessibility granted); transient states are disabled.
 */
internal fun mcpStartStopButtonEnabled(
    status: ServerStatus,
    startEnabled: Boolean,
): Boolean =
    when (status) {
        is ServerStatus.Running -> true
        is ServerStatus.Stopped, is ServerStatus.Error -> startEnabled
        else -> false
    }

/**
 * Whether the Event Channel start/stop button is enabled. Stop (enabled) is always allowed;
 * Start requires [startEnabled] (accessibility granted).
 */
internal fun channelStartStopButtonEnabled(
    channelEnabled: Boolean,
    startEnabled: Boolean,
): Boolean = if (channelEnabled) true else startEnabled

@Composable
private fun ServiceRow(
    label: String,
    statusText: String,
    statusColor: Color,
    buttonText: String,
    buttonEnabled: Boolean,
    onButtonClick: () -> Unit,
) {
    val animatedColor by animateColorAsState(
        targetValue = statusColor,
        animationSpec = tween(durationMillis = ANIMATION_DURATION_MS),
        label = "statusColor",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Canvas(
                modifier =
                    Modifier
                        .size(STATUS_DOT_SIZE_DP.dp)
                        .semantics {
                            contentDescription = "$label status: $statusText"
                        },
            ) {
                drawCircle(color = animatedColor)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        FilledTonalButton(
            onClick = onButtonClick,
            enabled = buttonEnabled,
        ) {
            Text(text = buttonText)
        }
    }
}

@Composable
private fun serverStatusToText(status: ServerStatus): String =
    when (status) {
        is ServerStatus.Running -> stringResource(R.string.server_status_running)
        is ServerStatus.Stopped -> stringResource(R.string.server_status_stopped)
        is ServerStatus.Starting -> stringResource(R.string.server_status_starting)
        is ServerStatus.Stopping -> stringResource(R.string.server_status_stopping)
        is ServerStatus.Error -> stringResource(R.string.orulink_service_error_title)
    }

@Composable
private fun serverStatusToColor(status: ServerStatus): Color =
    when (status) {
        is ServerStatus.Running -> MaterialTheme.colorScheme.primary
        is ServerStatus.Stopped -> MaterialTheme.colorScheme.onSurfaceVariant
        is ServerStatus.Starting, is ServerStatus.Stopping -> MaterialTheme.colorScheme.tertiary
        is ServerStatus.Error -> MaterialTheme.colorScheme.error
    }

@Composable
private fun channelStatusToText(
    status: ChannelConnectionStatus,
    enabled: Boolean,
): String =
    if (!enabled) {
        stringResource(R.string.server_status_stopped)
    } else {
        when (status) {
            is ChannelConnectionStatus.Idle -> stringResource(R.string.orulink_channel_idle)
            is ChannelConnectionStatus.Active -> stringResource(R.string.orulink_channel_active)
            is ChannelConnectionStatus.Error -> status.message
        }
    }

@Composable
private fun channelStatusToColor(
    status: ChannelConnectionStatus,
    enabled: Boolean,
): Color =
    when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        status is ChannelConnectionStatus.Active -> MaterialTheme.colorScheme.primary
        status is ChannelConnectionStatus.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Preview(showBackground = true)
@Composable
private fun ServerStatusCardStoppedPreview() {
    AndroidRemoteControlMcpTheme {
        ServerStatusCard(
            serverStatus = ServerStatus.Stopped,
            channelStatus = ChannelConnectionStatus.Idle,
            channelEnabled = false,
            onMcpStartClick = {},
            onMcpStopClick = {},
            onChannelStartClick = {},
            onChannelStopClick = {},
            startEnabled = true,
        )
    }
}
