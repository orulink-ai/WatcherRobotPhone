@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.components.OrulinkBrandMark
import com.danielealbano.androidremotecontrolmcp.ui.components.OrulinkSectionHeader
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.UpdateCheckUiState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.UpdateViewModel

private const val GITHUB_URL = "https://github.com/orulink-ai/android-remote-control-mcp"
private const val ISSUES_URL = "$GITHUB_URL/issues"
private const val UPSTREAM_URL = "https://github.com/danielealbano/android-remote-control-mcp"
private const val LICENSE_URL = "$UPSTREAM_URL/blob/main/LICENSE.md"
private const val LINKEDIN_URL = "https://linkedin.com/in/danielesalvatorealbano"
private const val X_URL = "https://x.com/daniele_dll"
private const val DBIP_URL = "https://db-ip.com"
private const val PRIVACY_MODEL_URL =
    "https://huggingface.co/ai4privacy/llama-ai4privacy-multilingual-categorical-anonymiser-openpii"
private const val PAGE_PADDING = 16
private const val SECTION_SPACING = 24
private const val CONTENT_SPACING = 12
private const val PROGRESS_SIZE = 18

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    modifier: Modifier = Modifier,
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val openUrl: (String) -> Unit = { url ->
        val action = if (url.startsWith("mailto:")) Intent.ACTION_SENDTO else Intent.ACTION_VIEW
        runCatching { context.startActivity(Intent(action, Uri.parse(url))) }
    }
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_about)) },
            windowInsets = WindowInsets(0),
        )
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(PAGE_PADDING.dp),
            verticalArrangement = Arrangement.spacedBy(SECTION_SPACING.dp),
        ) {
            OrulinkAboutHeader()
            AboutProjectLinks(onOpenUrl = openUrl)
            UpstreamAttribution(onOpenUrl = openUrl)
            UpdateSettingsSection(viewModel = updateViewModel, onOpenUrl = openUrl)
            ThirdPartyAttributions(onOpenUrl = openUrl)
            Text(
                text = stringResource(R.string.about_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OrulinkAboutHeader() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(SECTION_SPACING.dp),
            verticalArrangement = Arrangement.spacedBy(CONTENT_SPACING.dp),
        ) {
            OrulinkBrandMark()
            Text(stringResource(R.string.orulink_home_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.orulink_about_tagline), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.orulink_about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AboutProjectLinks(onOpenUrl: (String) -> Unit) {
    Column {
        OrulinkSectionHeader(title = stringResource(R.string.orulink_about_project))
        Spacer(Modifier.height(CONTENT_SPACING.dp))
        Card {
            AboutLink(
                title = stringResource(R.string.orulink_about_repository),
                url = GITHUB_URL,
                icon = Icons.Default.Code,
                onOpenUrl = onOpenUrl,
            )
            AboutLink(
                title = stringResource(R.string.about_link_issues),
                url = ISSUES_URL,
                icon = Icons.Default.ReportProblem,
                onOpenUrl = onOpenUrl,
            )
        }
    }
}

@Composable
private fun UpstreamAttribution(onOpenUrl: (String) -> Unit) {
    Column {
        OrulinkSectionHeader(
            title = stringResource(R.string.orulink_about_open_source),
            subtitle = stringResource(R.string.orulink_about_upstream_credit),
        )
        Spacer(Modifier.height(CONTENT_SPACING.dp))
        Card {
            AboutLink(
                title = stringResource(R.string.orulink_about_upstream_repository),
                url = UPSTREAM_URL,
                icon = Icons.Default.Code,
                onOpenUrl = onOpenUrl,
            )
            AboutLink(
                title = stringResource(R.string.about_link_license),
                url = LICENSE_URL,
                icon = Icons.Default.Description,
                onOpenUrl = onOpenUrl,
            )
            AboutLink(
                title = stringResource(R.string.about_author_email),
                url = "mailto:d.albano@gmail.com",
                icon = Icons.Default.Link,
                onOpenUrl = onOpenUrl,
            )
            AboutLink(
                title = stringResource(R.string.about_author_linkedin),
                url = LINKEDIN_URL,
                icon = Icons.Default.Link,
                onOpenUrl = onOpenUrl,
            )
            AboutLink(
                title = stringResource(R.string.about_author_x),
                url = X_URL,
                icon = Icons.Default.Link,
                onOpenUrl = onOpenUrl,
            )
        }
    }
}

@Composable
private fun ThirdPartyAttributions(onOpenUrl: (String) -> Unit) {
    Column {
        OrulinkSectionHeader(title = stringResource(R.string.about_attributions_section))
        Spacer(Modifier.height(CONTENT_SPACING.dp))
        Card {
            AboutLink(
                title = stringResource(R.string.about_attribution_dbip),
                url = DBIP_URL,
                icon = Icons.Default.Link,
                onOpenUrl = onOpenUrl,
            )
            AboutLink(
                title = stringResource(R.string.about_attribution_privacy_model),
                url = PRIVACY_MODEL_URL,
                icon = Icons.Default.Link,
                onOpenUrl = onOpenUrl,
            )
        }
    }
}

@Composable
private fun AboutLink(
    title: String,
    url: String,
    icon: ImageVector,
    onOpenUrl: (String) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.clickable { onOpenUrl(url) },
    )
}

@Composable
private fun UpdateSettingsSection(
    viewModel: UpdateViewModel,
    onOpenUrl: (String) -> Unit,
) {
    val autoCheckEnabled by viewModel.autoCheckEnabled.collectAsStateWithLifecycle()
    val checkState by viewModel.checkState.collectAsStateWithLifecycle()
    Column {
        OrulinkSectionHeader(
            title = stringResource(R.string.orulink_about_upstream_updates),
            subtitle = stringResource(R.string.orulink_about_upstream_updates_hint),
        )
        Spacer(Modifier.height(CONTENT_SPACING.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.orulink_about_auto_check_upstream),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = autoCheckEnabled, onCheckedChange = viewModel::setAutoCheckEnabled)
        }
        OutlinedButton(
            onClick = viewModel::checkNow,
            enabled = checkState !is UpdateCheckUiState.Checking,
        ) {
            if (checkState is UpdateCheckUiState.Checking) {
                CircularProgressIndicator(modifier = Modifier.size(PROGRESS_SIZE.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.orulink_about_check_upstream))
            }
        }
        UpdateCheckStatusText(state = checkState, onOpenUrl = onOpenUrl)
    }
}

@Composable
private fun UpdateCheckStatusText(
    state: UpdateCheckUiState,
    onOpenUrl: (String) -> Unit,
) {
    if (state is UpdateCheckUiState.UpdateFound) {
        AboutLink(
            title = stringResource(R.string.orulink_about_upstream_update_found, state.update.versionName),
            url = state.update.releaseUrl,
            icon = Icons.Default.Link,
            onOpenUrl = onOpenUrl,
        )
        return
    }
    val message =
        when (state) {
            UpdateCheckUiState.UpToDate -> stringResource(R.string.orulink_about_upstream_current)
            UpdateCheckUiState.Failed -> stringResource(R.string.about_update_check_failed)
            UpdateCheckUiState.DevBuild -> stringResource(R.string.orulink_about_dev_build)
            else -> null
        }
    if (message != null) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
