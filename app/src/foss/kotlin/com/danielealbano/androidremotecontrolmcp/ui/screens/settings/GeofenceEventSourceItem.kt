package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.navigation.NavHostController

/** foss flavor: geofencing is absent, so no geofence row is added. */
fun LazyListScope.geofenceEventSourceItem(
    @Suppress("UNUSED_PARAMETER") navController: NavHostController,
) = Unit
