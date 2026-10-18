@file:Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "TooManyFunctions", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.location.Geocoder
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.data.model.GeofenceZone
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.GeofenceSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

private fun createCircleMarkerIcon(context: android.content.Context): BitmapDrawable {
    val size = 40
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val outerPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 66, 133, 244)
            style = Paint.Style.FILL
        }
    val innerPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
    val r = size / 2f
    canvas.drawCircle(r, r, r, outerPaint)
    canvas.drawCircle(r, r, r * 0.5f, innerPaint)
    return BitmapDrawable(context.resources, bitmap)
}

private fun formatRadius(meters: Float): String {
    val measureSystem =
        android.icu.util.LocaleData.getMeasurementSystem(
            android.icu.util.ULocale
                .forLocale(Locale.getDefault()),
        )
    val useImperial = measureSystem != android.icu.util.LocaleData.MeasurementSystem.SI
    return if (useImperial) {
        val feet = meters * METERS_TO_FEET
        if (feet >= FEET_PER_MILE) {
            "${"%.1f".format(feet / FEET_PER_MILE)} mi"
        } else {
            "${feet.roundToInt()} ft"
        }
    } else {
        if (meters >= METERS_PER_KM) {
            "${"%.1f".format(meters / METERS_PER_KM)} km"
        } else {
            "${meters.roundToInt()} m"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceMapScreen(
    viewModel: GeofenceSettingsViewModel,
    zoneId: String?,
    onNavigateBack: () -> Unit,
) {
    val config by viewModel.geofenceConfig.collectAsStateWithLifecycle()
    val existingZone = zoneId?.let { id -> config.zones.find { it.id == id } }

    var name by rememberSaveable { mutableStateOf(existingZone?.name ?: "") }
    var latitude by rememberSaveable { mutableDoubleStateOf(existingZone?.latitude ?: 0.0) }
    var longitude by rememberSaveable { mutableDoubleStateOf(existingZone?.longitude ?: 0.0) }
    var hasPin by rememberSaveable { mutableStateOf(existingZone != null) }
    var radiusMeters by rememberSaveable {
        mutableFloatStateOf(existingZone?.radiusMeters ?: DEFAULT_RADIUS_METERS)
    }
    var notifyOnEnter by rememberSaveable { mutableStateOf(existingZone?.notifyOnEnter ?: true) }
    var notifyOnExit by rememberSaveable { mutableStateOf(existingZone?.notifyOnExit ?: true) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var streetName by rememberSaveable { mutableStateOf("") }

    val canSave = name.isNotBlank() && hasPin
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val markerIcon = remember { createCircleMarkerIcon(context) }

    // Stable reference — created once, updated via `update` lambda
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }

    fun reverseGeocode(
        lat: Double,
        lon: Double,
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val results = Geocoder(context).getFromLocation(lat, lon, 1)
                val address = results?.firstOrNull()
                val resolved =
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
                withContext(Dispatchers.Main) { streetName = resolved }
            } catch (_: IOException) {
                withContext(Dispatchers.Main) { streetName = "" }
            }
        }
    }

    fun updateMapOverlays(map: MapView) {
        if (!hasPin) return
        val point = GeoPoint(latitude, longitude)

        map.overlays.removeAll { it is Polygon || it is Marker }

        val circle = Polygon(map)
        circle.points = Polygon.pointsAsCircle(point, radiusMeters.toDouble())
        circle.fillPaint.color = Color.argb(38, 66, 133, 244)
        circle.outlinePaint.color = Color.argb(200, 66, 133, 244)
        circle.outlinePaint.strokeWidth = 4f
        map.overlays.add(circle)

        val marker = Marker(map)
        marker.position = point
        marker.icon = markerIcon
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        marker.title = formatRadius(radiusMeters)
        marker.snippet =
            buildString {
                if (streetName.isNotBlank()) append("$streetName\n")
                append("${"%.6f".format(latitude)}, ${"%.6f".format(longitude)}")
            }
        marker.showInfoWindow()
        map.overlays.add(marker)
        map.invalidate()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (zoneId != null) "Edit Zone" else "Add Zone") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val zone =
                                GeofenceZone(
                                    id =
                                        zoneId ?: java.util.UUID
                                            .randomUUID()
                                            .toString(),
                                    name = name,
                                    latitude = latitude,
                                    longitude = longitude,
                                    radiusMeters = radiusMeters,
                                    notifyOnEnter = notifyOnEnter,
                                    notifyOnExit = notifyOnExit,
                                )
                            if (zoneId != null) {
                                viewModel.updateGeofenceZone(zone)
                            } else {
                                viewModel.addGeofenceZone(zone)
                            }
                            onNavigateBack()
                        },
                        enabled = canSave,
                    ) {
                        Text("Save")
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
    ) { scaffoldPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(scaffoldPadding)) {
            // Zone name field
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Zone name") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
            )

            Spacer(Modifier.height(12.dp))

            // Map — takes remaining space, clipToBounds prevents tile flicker
            Box(modifier = Modifier.weight(1f).clipToBounds()) {
                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            isTilesScaledToDpi = true
                            zoomController.setVisibility(
                                org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT,
                            )
                            controller.setZoom(DEFAULT_ZOOM)

                            if (existingZone != null) {
                                controller.setCenter(GeoPoint(existingZone.latitude, existingZone.longitude))
                            } else {
                                coroutineScope.launch {
                                    viewModel.currentLocation().onSuccess { loc ->
                                        controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
                                    }
                                }
                            }

                            val tapOverlay =
                                object : Overlay() {
                                    override fun onSingleTapConfirmed(
                                        e: MotionEvent?,
                                        mapView: MapView?,
                                    ): Boolean {
                                        if (e == null || mapView == null) return false
                                        val gp =
                                            mapView.projection.fromPixels(
                                                e.x.toInt(),
                                                e.y.toInt(),
                                            ) as GeoPoint
                                        latitude = gp.latitude
                                        longitude = gp.longitude
                                        hasPin = true
                                        reverseGeocode(gp.latitude, gp.longitude)
                                        updateMapOverlays(mapView)
                                        return true
                                    }
                                }
                            overlays.add(0, tapOverlay)

                            mapViewRef.value = this

                            if (existingZone != null) {
                                reverseGeocode(existingZone.latitude, existingZone.longitude)
                                updateMapOverlays(this)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // Notification toggles — top right of map
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 8.dp)
                            .width(120.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                                RoundedCornerShape(12.dp),
                            ).padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        "Notifications",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Enter", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Switch(checked = notifyOnEnter, onCheckedChange = { notifyOnEnter = it })
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Exit", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Switch(checked = notifyOnExit, onCheckedChange = { notifyOnExit = it })
                    }
                }

                // My Location button
                SmallFloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            viewModel.currentLocation().onSuccess { loc ->
                                mapViewRef.value?.controller?.animateTo(
                                    GeoPoint(loc.latitude, loc.longitude),
                                )
                            }
                        }
                    },
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = 12.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                ) {
                    Icon(Icons.Default.MyLocation, "My location")
                }
            }

            // Bottom controls — radius slider + search
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                // Radius slider
                Text(
                    "Radius: ${formatRadius(radiusMeters)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = radiusMeters,
                    onValueChange = {
                        radiusMeters = it
                        mapViewRef.value?.let { map -> updateMapOverlays(map) }
                    },
                    valueRange = MIN_RADIUS_METERS..MAX_RADIUS_METERS,
                    steps = ((MAX_RADIUS_METERS - MIN_RADIUS_METERS) / RADIUS_STEP_METERS).toInt() - 1,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Search address
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search address") },
                    trailingIcon = {
                        IconButton(onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    @Suppress("DEPRECATION")
                                    val results = Geocoder(context).getFromLocationName(searchQuery, 1)
                                    val address = results?.firstOrNull()
                                    if (address != null) {
                                        withContext(Dispatchers.Main) {
                                            latitude = address.latitude
                                            longitude = address.longitude
                                            hasPin = true
                                            reverseGeocode(address.latitude, address.longitude)
                                            val point = GeoPoint(address.latitude, address.longitude)
                                            mapViewRef.value?.controller?.animateTo(point)
                                            mapViewRef.value?.let { updateMapOverlays(it) }
                                        }
                                    }
                                } catch (_: IOException) {
                                    // Geocoder failed
                                }
                            }
                        }) {
                            Icon(Icons.Default.Search, "Search")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mapViewRef.value?.onDetach()
        }
    }
}

private const val MIN_RADIUS_METERS = 100f
private const val MAX_RADIUS_METERS = 5000f
private const val DEFAULT_RADIUS_METERS = 1000f
private const val RADIUS_STEP_METERS = 100f
private const val DEFAULT_ZOOM = 15.0
private const val METERS_TO_FEET = 3.28084f
private const val FEET_PER_MILE = 5280f
private const val METERS_PER_KM = 1000f
