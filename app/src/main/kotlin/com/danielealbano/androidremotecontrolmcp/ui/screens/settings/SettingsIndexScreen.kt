@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.components.OrulinkSectionHeader
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SettingsRoute

private const val PAGE_PADDING = 16
private const val SECTION_SPACING = 24
private const val HEADING_SPACING = 12

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsIndexScreen(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_settings)) },
            windowInsets = WindowInsets(0),
        )
        SettingsEntriesColumn(
            onNavigate = onNavigate,
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(PAGE_PADDING.dp),
        )
    }
}

@Composable
private fun SettingsEntriesColumn(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SECTION_SPACING.dp)) {
        ConnectionSettingsGroup(onNavigate)
        ExperienceSettingsGroup(onNavigate)
        CapabilitySettingsGroup(onNavigate)
        DeviceSettingsGroup(onNavigate)
    }
}

@Composable
private fun ConnectionSettingsGroup(onNavigate: (String) -> Unit) {
    SettingsGroup(stringResource(R.string.orulink_settings_connections)) {
        SettingsEntry(
            icon = Icons.Default.Key,
            title = R.string.orulink_settings_access_title,
            subtitle = R.string.orulink_settings_access_hint,
        ) {
            onNavigate(SettingsRoute.Access.route)
        }
        SettingsEntry(
            icon = Icons.Default.Cloud,
            title = R.string.orulink_settings_tunnel_title,
            subtitle = R.string.orulink_settings_tunnel_hint,
        ) {
            onNavigate(SettingsRoute.Tunnel.route)
        }
        SettingsEntry(
            icon = Icons.Default.Lock,
            title = R.string.orulink_settings_security_title,
            subtitle = R.string.orulink_settings_security_hint,
        ) {
            onNavigate(SettingsRoute.Security.route)
        }
    }
}

@Composable
private fun ExperienceSettingsGroup(onNavigate: (String) -> Unit) {
    SettingsGroup(stringResource(R.string.orulink_settings_experience)) {
        SettingsEntry(
            icon = Icons.Default.AutoAwesome,
            title = R.string.control_effects_settings_title,
            subtitle = R.string.control_effects_settings_hint,
        ) {
            onNavigate(SettingsRoute.ControlEffects.route)
        }
        SettingsEntry(
            icon = Icons.Default.Shield,
            title = R.string.orulink_settings_privacy_title,
            subtitle = R.string.orulink_settings_privacy_hint,
        ) {
            onNavigate(SettingsRoute.Privacy.route)
        }
        SettingsEntry(
            icon = Icons.Default.CellTower,
            title = R.string.orulink_settings_channel_title,
            subtitle = R.string.orulink_settings_channel_hint,
        ) {
            onNavigate(SettingsRoute.ChannelSettings.route)
        }
    }
}

@Composable
private fun CapabilitySettingsGroup(onNavigate: (String) -> Unit) {
    SettingsGroup(stringResource(R.string.orulink_settings_capabilities)) {
        SettingsEntry(
            icon = Icons.Default.Build,
            title = R.string.orulink_settings_tools_title,
            subtitle = R.string.orulink_settings_tools_hint,
        ) {
            onNavigate(SettingsRoute.McpTools.route)
        }
        SettingsEntry(
            Icons.Default.AdminPanelSettings,
            R.string.orulink_settings_permissions_title,
            R.string.orulink_settings_permissions_hint,
        ) {
            onNavigate(SettingsRoute.Permissions.route)
        }
    }
}

@Composable
private fun DeviceSettingsGroup(onNavigate: (String) -> Unit) {
    SettingsGroup(stringResource(R.string.orulink_settings_device)) {
        SettingsEntry(
            icon = Icons.Default.Tune,
            title = R.string.orulink_settings_general_title,
            subtitle = R.string.orulink_settings_general_hint,
        ) {
            onNavigate(SettingsRoute.General.route)
        }
        SettingsEntry(
            icon = Icons.Default.Folder,
            title = R.string.orulink_settings_storage_title,
            subtitle = R.string.orulink_settings_storage_hint,
        ) {
            onNavigate(SettingsRoute.Storage.route)
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column {
        OrulinkSectionHeader(title = title)
        Spacer(Modifier.height(HEADING_SPACING.dp))
        Card { Column { content() } }
    }
}

@Composable
private fun SettingsEntry(
    icon: ImageVector,
    title: Int,
    subtitle: Int,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(subtitle)) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
