package com.danielealbano.androidremotecontrolmcp.services.update

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Posts the "update available" notification whose tap opens the release page in the default browser. */
interface UpdateNotifier {
    fun notifyUpdateAvailable(
        versionName: String,
        releaseUrl: String,
    )
}

@Singleton
class UpdateNotifierImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : UpdateNotifier {
        override fun notifyUpdateAvailable(
            versionName: String,
            releaseUrl: String,
        ) {
            // Silent no-op if the runtime notification permission is not granted (Android 13+).
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            // Opens the release page in whatever browser handles it — NOT an in-app WebView.
            val viewIntent =
                Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            val pendingIntent =
                PendingIntent.getActivity(
                    context,
                    REQUEST_CODE,
                    viewIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )

            val notification =
                NotificationCompat
                    .Builder(context, McpApplication.UPDATE_CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.notification_update_available_title))
                    .setContentText(context.getString(R.string.notification_update_available_body, versionName))
                    .setSmallIcon(R.drawable.ic_notification)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }

        companion object {
            const val NOTIFICATION_ID = 1003
            private const val REQUEST_CODE = 3
        }
    }
