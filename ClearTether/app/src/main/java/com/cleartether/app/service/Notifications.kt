package com.cleartether.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cleartether.app.MainActivity
import com.cleartether.app.R

object Notifications {
    const val CHANNEL_STATUS = "tether_status"
    const val CHANNEL_ALERTS = "tether_alerts"
    const val ID_HOST = 1001
    const val ID_CLIENT = 1002
    const val ID_ALERT = 1003

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_status_description)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_alerts_description)
            },
        )
    }

    private fun openApp(context: Context, showPaywall: Boolean = false): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_SHOW_PAYWALL, showPaywall)
        return PendingIntent.getActivity(
            context, if (showPaywall) 2 else 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun host(
        context: Context,
        title: String,
        text: String,
        progressPercent: Int? = null,
        upgradeAction: Boolean = false,
    ): Notification {
        val stop = PendingIntent.getService(
            context, 10,
            Intent(context, TetherForegroundService::class.java).setAction(TetherForegroundService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_tether)
            .setColor(ContextCompat.getColor(context, R.color.brand_primary))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (progressPercent != null) setProgress(100, progressPercent.coerceIn(0, 100), false)
                if (upgradeAction) addAction(0, context.getString(R.string.action_upgrade), openApp(context, showPaywall = true))
            }
            .addAction(0, context.getString(R.string.action_stop), stop)
            .build()
    }

    fun client(context: Context, title: String, text: String): Notification {
        val disconnect = PendingIntent.getService(
            context, 11,
            Intent(context, TransparentTetherVpnService::class.java).setAction(TransparentTetherVpnService.ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_tether)
            .setColor(ContextCompat.getColor(context, R.color.brand_primary))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.action_disconnect), disconnect)
            .build()
    }

    fun limitReached(context: Context, text: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_tether)
            .setColor(ContextCompat.getColor(context, R.color.brand_primary))
            .setContentTitle(context.getString(R.string.notif_limit_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context, showPaywall = true))
            .addAction(0, context.getString(R.string.action_upgrade), openApp(context, showPaywall = true))
            .setAutoCancel(true)
            .build()
        post(context, ID_ALERT, n)
    }

    fun cancelAlert(context: Context) = NotificationManagerCompat.from(context).cancel(ID_ALERT)

    fun post(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
