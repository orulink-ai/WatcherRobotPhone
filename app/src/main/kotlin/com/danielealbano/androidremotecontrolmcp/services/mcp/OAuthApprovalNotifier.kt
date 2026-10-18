package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.ApprovalActivity

/**
 * Posts/cancels the single, collapsed heads-up notification for pending OAuth approvals. Extracted from
 * [McpServerService] so the service stays within detekt's function-count threshold.
 */
object OAuthApprovalNotifier {
    const val NOTIFICATION_ID = 1002

    /** Posts (or updates) the pending-approvals notification. No-op if POST_NOTIFICATIONS is not granted. */
    fun post(
        context: Context,
        count: Int,
    ) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                1,
                Intent(context, ApprovalActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, McpApplication.OAUTH_APPROVAL_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notification_oauth_approval_title))
                .setContentText(context.getString(R.string.notification_oauth_approval_body, count))
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Cancels the pending-approvals notification, if any. */
    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
