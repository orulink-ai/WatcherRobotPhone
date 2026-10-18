package com.danielealbano.androidremotecontrolmcp.services.power

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** gms flavor: one-tap system exemption dialog. */
class GmsBatteryOptimizationManagerImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : BatteryOptimizationManager {
        override fun isIgnoringBatteryOptimizations(): Boolean = context.isIgnoringBatteryOptimizations()

        override fun requestExemption() {
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog unavailable; opening settings list", e)
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        private companion object {
            const val TAG = "MCP:BatteryOpt"
        }
    }
