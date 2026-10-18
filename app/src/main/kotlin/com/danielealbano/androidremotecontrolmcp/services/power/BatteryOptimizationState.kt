package com.danielealbano.androidremotecontrolmcp.services.power

import android.content.Context
import android.os.PowerManager

internal fun Context.isIgnoringBatteryOptimizations(): Boolean =
    (getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(packageName)
