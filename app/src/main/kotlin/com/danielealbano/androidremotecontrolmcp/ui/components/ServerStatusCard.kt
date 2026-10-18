@file:Suppress("FunctionNaming", "MagicNumber", "UnusedPrivateMember", "LongMethod", "LongParameterList")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
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
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = "Services Status",
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(Modifier.height(12.dp))

            // MCP Server row
            ServiceRow(
                label = "MCP Server",
                statusText = serverStatusToText(serverStatus),
                statusColor = serverStatusToColor(serverStatus, isSystemInDarkTheme()),
                buttonText = if (serverStatus is ServerStatus.Running) "Stop" else "Start",
                buttonEnabled = mcpStartStopButtonEnabled(serverStatus, startEnabled),
                onButtonClick = if (serverStatus is ServerStatus.Running) onMcpStopClick else onMcpStartClick,
            )

            Spacer(Modifier.height(8.dp))

            // Event Channel row
            ServiceRow(
                label = "Event Channel",
                statusText = channelStatusToText(channelStatus, channelEnabled),
                statusColor = channelStatusToColor(channelStatus, channelEnabled, isSystemInDarkTheme()),
                buttonText = if (channelEnabled) "Stop" else "Start",
                buttonEnabled = channelStartStopButtonEnabled(channelEnabled, startEnabled),
                onButtonClick = if (channelEnabled) onChannelStopClick else onChannelStartClick,
            )
        }
    }
}

/**
 * Whether the MCP Server start/stop button is enabled. Stop (Running) is always enabled;
 * Start (Stopped) requires [startEnabled] (accessibility granted); transient states are disabled.
 */
internal fun mcpStartStopButtonEnabled(
    status: ServerStatus,
    startEnabled: Boolean,
): Boolean =
    when (status) {
        is ServerStatus.Running -> true
        is ServerStatus.Stopped -> startEnabled
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
        is ServerStatus.Error -> stringResource(R.string.server_status_error, status.message)
    }

private fun serverStatusToColor(
    status: ServerStatus,
    isDarkTheme: Boolean,
): Color =
    if (isDarkTheme) {
        when (status) {
            is ServerStatus.Running -> Color(0xFF81C784)
            is ServerStatus.Stopped -> Color(0xFFEF5350)
            is ServerStatus.Starting -> Color(0xFFFFD54F)
            is ServerStatus.Stopping -> Color(0xFFFFD54F)
            is ServerStatus.Error -> Color(0xFFFFB74D)
        }
    } else {
        when (status) {
            is ServerStatus.Running -> Color(0xFF4CAF50)
            is ServerStatus.Stopped -> Color(0xFFF44336)
            is ServerStatus.Starting -> Color(0xFFFFC107)
            is ServerStatus.Stopping -> Color(0xFFFFC107)
            is ServerStatus.Error -> Color(0xFFFF9800)
        }
    }

@Composable
private fun channelStatusToText(
    status: ChannelConnectionStatus,
    enabled: Boolean,
): String =
    if (!enabled) {
        "Stopped"
    } else {
        when (status) {
            is ChannelConnectionStatus.Idle -> "Idle"
            is ChannelConnectionStatus.Active -> "Active"
            is ChannelConnectionStatus.Error -> status.message
        }
    }

private fun channelStatusToColor(
    status: ChannelConnectionStatus,
    enabled: Boolean,
    isDarkTheme: Boolean,
): Color =
    if (!enabled) {
        if (isDarkTheme) Color(0xFFEF5350) else Color(0xFFF44336)
    } else {
        when (status) {
            is ChannelConnectionStatus.Idle -> {
                Color.Gray
            }

            is ChannelConnectionStatus.Active -> {
                if (isDarkTheme) Color(0xFF81C784) else Color(0xFF4CAF50)
            }

            is ChannelConnectionStatus.Error -> {
                if (isDarkTheme) Color(0xFFFFB74D) else Color(0xFFFF9800)
            }
        }
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
