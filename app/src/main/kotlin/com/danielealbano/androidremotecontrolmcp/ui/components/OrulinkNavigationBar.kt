package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.navigation.TopLevelRoute

/** Shared app navigation; Material items retain selection semantics and large touch targets. */
object OrulinkNavigationBar {
    @Composable
    operator fun invoke(
        selectedRoute: String,
        onSelectRoute: (String) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val colors = MaterialTheme.colorScheme
        Column(modifier = modifier) {
            HorizontalDivider(color = colors.outlineVariant)
            NavigationBar(containerColor = colors.surfaceContainerLow, tonalElevation = 0.dp) {
                listOf(
                    Triple(TopLevelRoute.Server, Icons.Default.PhoneAndroid, stringResource(R.string.tab_server)),
                    Triple(TopLevelRoute.Settings, Icons.Default.Settings, stringResource(R.string.tab_settings)),
                    Triple(TopLevelRoute.About, Icons.Default.Info, stringResource(R.string.tab_about)),
                ).forEach { (route, icon, label) ->
                    NavigationBarItem(
                        selected = selectedRoute == route.route,
                        onClick = { onSelectRoute(route.route) },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        colors =
                            NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.onPrimaryContainer,
                                selectedTextColor = colors.primary,
                                indicatorColor = colors.primaryContainer,
                                unselectedIconColor = colors.onSurfaceVariant,
                                unselectedTextColor = colors.onSurfaceVariant,
                            ),
                    )
                }
            }
        }
    }
}
