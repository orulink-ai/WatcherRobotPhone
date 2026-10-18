package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.danielealbano.androidremotecontrolmcp.R

/**
 * gms flavor: the geofence-only "Background Location" permission row. Recomputes the grant state on
 * lifecycle resume (returning from app settings) and opens app settings on action, since
 * ACCESS_BACKGROUND_LOCATION cannot be requested via a runtime dialog.
 */
@Composable
fun BackgroundLocationPermissionRow() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(isGranted()) }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    granted = isGranted()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Spacer(modifier = Modifier.height(8.dp))
    PermissionRow(
        label = stringResource(R.string.permission_background_location),
        rationale = stringResource(R.string.permission_background_location_rationale),
        isEnabled = granted,
        buttonText =
            if (granted) {
                stringResource(R.string.permission_granted)
            } else {
                stringResource(R.string.permission_background_location_open_settings)
            },
        onAction = {
            val intent =
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
            context.startActivity(intent)
        },
        actionEnabled = !granted,
    )
}
