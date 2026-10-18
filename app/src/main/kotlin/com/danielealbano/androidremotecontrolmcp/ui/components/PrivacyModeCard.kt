@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.danielealbano.androidremotecontrolmcp.R

@Composable
fun PrivacyModeCard(
    onSetupClick: () -> Unit,
    onDismissClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CalloutCard(
        icon = Icons.Default.Lightbulb,
        title = stringResource(R.string.privacy_card_title),
        modifier = modifier,
    ) {
        TextButton(onClick = onSetupClick) {
            Text(text = stringResource(R.string.privacy_card_action))
        }
        TextButton(onClick = onDismissClick) {
            Text(text = stringResource(R.string.privacy_card_dismiss))
        }
    }
}
