@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.UpdateCheckUiState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.UpdateViewModel

private const val GITHUB_URL = "https://github.com/danielealbano/android-remote-control-mcp"
private const val LICENSE_URL = "https://github.com/danielealbano/android-remote-control-mcp/blob/main/LICENSE"
private const val ISSUES_URL = "https://github.com/danielealbano/android-remote-control-mcp/issues"
private const val LINKEDIN_URL = "https://linkedin.com/in/danielesalvatorealbano"
private const val X_URL = "https://x.com/daniele_dll"
private const val DBIP_URL = "https://db-ip.com"
private const val PRIVACY_MODEL_URL =
    "https://huggingface.co/ai4privacy/llama-ai4privacy-multilingual-categorical-anonymiser-openpii"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    modifier: Modifier = Modifier,
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_about)) },
            windowInsets = WindowInsets(0),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            // App icon + name + version
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(colorResource(R.color.ic_launcher_background)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(72.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Updates section
            UpdateSettingsSection(
                viewModel = updateViewModel,
                onOpenUrl = { url ->
                    // No-op if the device has no browser to handle ACTION_VIEW (never crash).
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                },
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Author section
            Text(
                text = stringResource(R.string.about_author_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.about_author_name),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.about_author_email),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.clickable {
                        val intent =
                            Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:d.albano@gmail.com")
                            }
                        context.startActivity(intent)
                    },
            )
            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.about_author_linkedin),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LINKEDIN_URL)))
                    },
            )
            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.about_author_x),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(X_URL)))
                    },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Links section
            Text(
                text = stringResource(R.string.about_links_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))

            ListItem(
                headlineContent = { Text(stringResource(R.string.about_link_github)) },
                leadingContent = {
                    Icon(Icons.Default.Code, contentDescription = null)
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier =
                    Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
                    },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_link_license)) },
                leadingContent = {
                    Icon(Icons.Default.Description, contentDescription = null)
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier =
                    Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LICENSE_URL)))
                    },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_link_issues)) },
                leadingContent = {
                    Icon(Icons.Default.ReportProblem, contentDescription = null)
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier =
                    Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL)))
                    },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Attributions section
            Text(
                text = stringResource(R.string.about_attributions_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.about_attribution_dbip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DBIP_URL)))
                    },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.about_attribution_privacy_model),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_MODEL_URL)))
                    },
            )

            Spacer(Modifier.height(24.dp))

            // Footer
            Text(
                text = stringResource(R.string.about_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun UpdateSettingsSection(
    viewModel: UpdateViewModel,
    onOpenUrl: (String) -> Unit,
) {
    val autoCheckEnabled by viewModel.autoCheckEnabled.collectAsStateWithLifecycle()
    val checkState by viewModel.checkState.collectAsStateWithLifecycle()

    Text(
        text = stringResource(R.string.about_updates_section),
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.about_auto_update_label),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = autoCheckEnabled, onCheckedChange = viewModel::setAutoCheckEnabled)
    }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = viewModel::checkNow,
        enabled = checkState !is UpdateCheckUiState.Checking,
    ) {
        if (checkState is UpdateCheckUiState.Checking) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(R.string.about_check_updates_button))
        }
    }

    UpdateCheckStatusText(state = checkState, onOpenUrl = onOpenUrl)
}

@Composable
private fun UpdateCheckStatusText(
    state: UpdateCheckUiState,
    onOpenUrl: (String) -> Unit,
) {
    if (state is UpdateCheckUiState.UpdateFound) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.about_update_found, state.update.versionName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onOpenUrl(state.update.releaseUrl) },
        )
        return
    }
    val message =
        when (state) {
            UpdateCheckUiState.UpToDate -> stringResource(R.string.about_update_up_to_date)
            UpdateCheckUiState.Failed -> stringResource(R.string.about_update_check_failed)
            UpdateCheckUiState.DevBuild -> stringResource(R.string.about_update_dev_build)
            else -> null
        }
    if (message != null) {
        UpdateStatusLine(message)
    }
}

@Composable
private fun UpdateStatusLine(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
