@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ChannelViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiMonitorScreen(
    viewModel: ChannelViewModel,
    onNavigateBack: () -> Unit,
) {
    val config by viewModel.eventChannelConfig.collectAsStateWithLifecycle()
    var newSsid by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi Monitor") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            items(config.wifi.ssids.toList()) { ssid ->
                ListItem(
                    headlineContent = { Text(ssid) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.removeWifiSsid(ssid) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove")
                        }
                    },
                )
            }
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newSsid,
                        onValueChange = { newSsid = it },
                        label = { Text("SSID") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    IconButton(
                        onClick = {
                            if (newSsid.isNotBlank()) {
                                viewModel.addWifiSsid(newSsid.trim())
                                newSsid = ""
                            }
                        },
                    ) {
                        Icon(Icons.Default.Add, "Add")
                    }
                }
            }
            item {
                ListItem(
                    headlineContent = { Text("Notify on discovered") },
                    supportingContent = { Text("Scan-based, may be delayed") },
                    trailingContent = {
                        Switch(
                            checked = config.wifi.notifyOnDiscovered,
                            onCheckedChange = { viewModel.updateWifiNotifyOnDiscovered(it) },
                        )
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Notify on lost") },
                    supportingContent = { Text("Scan-based, may be delayed") },
                    trailingContent = {
                        Switch(
                            checked = config.wifi.notifyOnLost,
                            onCheckedChange = { viewModel.updateWifiNotifyOnLost(it) },
                        )
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Notify on connected") },
                    supportingContent = { Text("Real-time") },
                    trailingContent = {
                        Switch(
                            checked = config.wifi.notifyOnConnected,
                            onCheckedChange = { viewModel.updateWifiNotifyOnConnected(it) },
                        )
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Notify on disconnected") },
                    supportingContent = { Text("Real-time") },
                    trailingContent = {
                        Switch(
                            checked = config.wifi.notifyOnDisconnected,
                            onCheckedChange = { viewModel.updateWifiNotifyOnDisconnected(it) },
                        )
                    },
                )
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text(
                        "WiFi scan events (discovered/lost) may be delayed up to 30 minutes in " +
                            "background due to Android scan throttling. Connection events are real-time.",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}
