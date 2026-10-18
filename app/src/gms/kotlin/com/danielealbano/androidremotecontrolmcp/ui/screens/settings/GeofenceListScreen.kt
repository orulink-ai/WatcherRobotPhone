@file:Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import android.location.Geocoder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.data.model.GeofenceZone
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.GeofenceSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceListScreen(
    viewModel: GeofenceSettingsViewModel,
    onNavigateToMap: (zoneId: String?) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val config by viewModel.geofenceConfig.collectAsStateWithLifecycle()
    var zoneToDelete by remember { mutableStateOf<GeofenceZone?>(null) }
    val streetNames = remember { mutableStateMapOf<String, String>() }
    val context = LocalContext.current

    // Reverse geocode all zones
    LaunchedEffect(config.zones) {
        withContext(Dispatchers.IO) {
            for (zone in config.zones) {
                if (zone.id in streetNames) continue
                try {
                    @Suppress("DEPRECATION")
                    val results = Geocoder(context).getFromLocation(zone.latitude, zone.longitude, 1)
                    val address = results?.firstOrNull()
                    val street =
                        if (address != null) {
                            buildString {
                                address.thoroughfare?.let { append(it) }
                                address.subThoroughfare?.let {
                                    if (isNotEmpty()) append(" ")
                                    append(it)
                                }
                                val cityParts =
                                    listOfNotNull(
                                        address.postalCode,
                                        address.locality ?: address.subAdminArea,
                                    )
                                if (cityParts.isNotEmpty()) {
                                    if (isNotEmpty()) append(", ")
                                    append(cityParts.joinToString(" "))
                                }
                                if (isEmpty()) address.getAddressLine(0)?.let { append(it) }
                            }
                        } else {
                            ""
                        }
                    streetNames[zone.id] = street
                } catch (_: IOException) {
                    streetNames[zone.id] = ""
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Geofence Zones") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onNavigateToMap(null) }) {
                Icon(Icons.Default.Add, "Add zone")
            }
        },
    ) { padding ->
        if (config.zones.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No geofence zones configured",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(config.zones) { zone ->
                    val street = streetNames[zone.id] ?: ""
                    ListItem(
                        headlineContent = { Text(zone.name) },
                        supportingContent = {
                            Text(
                                buildString {
                                    if (street.isNotBlank()) append("$street\n")
                                    append(
                                        "${"%.4f".format(zone.latitude)}, " +
                                            "${"%.4f".format(zone.longitude)} — " +
                                            "${zone.radiusMeters.toInt()}m",
                                    )
                                },
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { zoneToDelete = zone }) {
                                    Icon(Icons.Default.Delete, "Delete")
                                }
                            }
                        },
                        modifier = Modifier.clickable { onNavigateToMap(zone.id) },
                    )
                }
            }
        }

        zoneToDelete?.let { zone ->
            AlertDialog(
                onDismissRequest = { zoneToDelete = null },
                title = { Text("Delete zone?") },
                text = { Text("Remove \"${zone.name}\"?") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.removeGeofenceZone(zone.id)
                        zoneToDelete = null
                    }) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { zoneToDelete = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}
