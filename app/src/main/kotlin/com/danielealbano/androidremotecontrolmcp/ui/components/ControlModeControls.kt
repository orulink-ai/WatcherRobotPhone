@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.services.control.ControlSessionState

private const val CONTENT_SPACING_DP = 8

/** App-level session controls stay separate from the device service and floating task controls. */
@Composable
internal fun ControlModeControls(
    serverStatus: ServerStatus,
    state: ControlSessionState,
    onStart: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(CONTENT_SPACING_DP.dp)) {
        Text(
            stringResource(R.string.control_mode_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(controlModeStatusResource(state)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(controlModeHintResource(serverStatus, state)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = if (state.isEngaged) onExit else onStart,
            enabled = controlModeButtonEnabled(serverStatus, state),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(controlModeButtonResource(state)))
        }
    }
}

internal fun controlModeButtonEnabled(
    status: ServerStatus,
    state: ControlSessionState,
): Boolean = state.isEngaged || status is ServerStatus.Running

internal fun controlModeButtonResource(state: ControlSessionState): Int =
    if (state.isEngaged) R.string.control_mode_exit else R.string.control_mode_start

internal fun controlModeStatusResource(state: ControlSessionState): Int =
    when {
        !state.isEngaged -> R.string.control_mode_inactive
        state.isTakenOver -> R.string.control_mode_taken_over
        state.isPaused -> R.string.control_mode_paused
        state.isActive -> R.string.control_mode_active
        else -> R.string.control_mode_waiting
    }

private fun controlModeHintResource(
    status: ServerStatus,
    state: ControlSessionState,
): Int =
    when {
        state.isEngaged -> R.string.control_mode_engaged_hint
        status !is ServerStatus.Running -> R.string.control_mode_requires_service
        else -> R.string.control_mode_start_hint
    }
