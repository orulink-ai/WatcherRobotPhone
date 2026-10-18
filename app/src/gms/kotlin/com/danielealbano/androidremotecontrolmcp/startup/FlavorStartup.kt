package com.danielealbano.androidremotecontrolmcp.startup

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.data.repository.GeofenceConfigRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface GeofenceMigrationEntryPoint {
    fun geofenceConfigRepository(): GeofenceConfigRepository
}

/**
 * gms flavor: runs the one-time geofence config migration eagerly at app startup on a background IO
 * coroutine (mirrors the app's existing auth-model migration in [McpApplication.onCreate]). It must
 * NOT block `Application.onCreate` — a blocking migration there stalls app initialization (no
 * components/server start). The dedicated geofence key already isolates geofence data from the shared
 * `EventChannelConfig` blob, and `migrateIfNeeded()` is idempotent and re-invoked defensively on every
 * geofence-repo read/write, so the data-safety guarantee (P53-001) is preserved without blocking.
 */
fun runFlavorStartupMigrations(context: Context) {
    CoroutineScope(Dispatchers.IO).launch {
        val repository =
            EntryPointAccessors
                .fromApplication(context, GeofenceMigrationEntryPoint::class.java)
                .geofenceConfigRepository()
        repository.migrateIfNeeded()
    }
}
