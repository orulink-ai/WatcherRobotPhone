package com.danielealbano.androidremotecontrolmcp.services.power

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** foss flavor: opens the battery-optimization settings list (no special permission). */
class FossBatteryOptimizationManagerImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : BatteryOptimizationManager {
        override fun isIgnoringBatteryOptimizations(): Boolean = context.isIgnoringBatteryOptimizations()

        override fun requestExemption() {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
