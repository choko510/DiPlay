// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shilapi.xcertplay.host.R

internal object BootAutoStart {
    const val EXTRA_AUTO_START_ON_BOOT = "com.shilapi.xcertplay.extra.AUTO_START_ON_BOOT"
    private const val TAG = "diplay-auto-connect"

    enum class Result {
        LAUNCHED,
        DISABLED,
        BLOCKED_NOTIFICATION_SHOWN,
        BLOCKED_NO_NOTIFICATION,
    }

    fun launch(
        context: Context,
        autoStartEnabled: Boolean = AirPlayPersistence.loadAutoStartOnBoot(context),
        startActivity: (Intent) -> Unit = context::startActivity,
        showNotification: (Context) -> Boolean = BootLaunchNotification::show,
    ): Result {
        if (!autoStartEnabled) {
            AutoConnectLog.event("event=BOOT_LAUNCH result=DISABLED reason=AUTO_START_OFF")
            return Result.DISABLED
        }
        val notificationShown = showNotification(context)
        val intent = Intent(context, DiPlayActivity::class.java).apply {
            putExtra(EXTRA_AUTO_START_ON_BOOT, true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        return try {
            startActivity(intent)
            AutoConnectLog.event("event=BOOT_LAUNCH result=LAUNCHED notification=$notificationShown")
            Result.LAUNCHED
        } catch (error: RuntimeException) {
            Log.w(
                TAG,
                "AUTO_CONNECT event=BOOT_LAUNCH result=BLOCKED " +
                    "error=${error.javaClass.simpleName} notification=$notificationShown",
            )
            if (notificationShown) Result.BLOCKED_NOTIFICATION_SHOWN else Result.BLOCKED_NO_NOTIFICATION
        }
    }
}

/**
 * A BOOT_COMPLETED receiver can be denied a background Activity start by the OS or an OEM power
 * policy without throwing. The notification is posted before the attempt and the Activity cancels
 * it after launch, so a silently denied launch still leaves an explicit user action.
 */
internal object BootLaunchNotification {
    private const val CHANNEL_ID = "diplay_boot_start"
    private const val NOTIFICATION_ID = 1453

    fun show(context: Context): Boolean {
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.ui_boot_auto_start_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val openApp = Intent(context, DiPlayActivity::class.java)
            .putExtra(BootAutoStart.EXTRA_AUTO_START_ON_BOOT, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return try {
            notifications.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_carplay)
                    .setContentTitle(context.getString(R.string.ui_boot_auto_start_notification_title))
                    .setContentText(context.getString(R.string.ui_boot_auto_start_notification_body))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build(),
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
