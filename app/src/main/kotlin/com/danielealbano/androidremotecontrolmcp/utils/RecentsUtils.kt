package com.danielealbano.androidremotecontrolmcp.utils

import android.app.ActivityManager
import android.content.Context

/**
 * Utility functions for managing application tasks in the Android Recent Tasks list.
 */
object RecentsUtils {
    /**
     * Updates all application tasks to exclude or include them in the recent tasks list.
     *
     * @param context Application or activity context.
     * @param exclude `true` to exclude app tasks from recents, `false` to include them.
     */
    fun setExcludeFromRecents(
        context: Context,
        exclude: Boolean,
    ) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        activityManager?.appTasks?.forEach { task ->
            task.setExcludeFromRecents(exclude)
        }
    }
}
