package com.example.gpsarrivalalarm

import android.Manifest
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

object ArrivalAlertCoordinator {
    fun announce(context: Context, destination: Destination) {
        val store = DestinationStore(context)
        val event = ArrivalEvent(
            destination.name,
            destination.arrivalAlertMethod,
            isFinalDestination = true,
            id = destination.id
        )
        val shouldAlertNow = store.savePendingArrival(destination)
        LocationMonitoringService.stop(context)
        if (shouldAlertNow) activatePending(context, event)
    }

    fun announceWaypoint(context: Context, waypoint: Waypoint) {
        val store = DestinationStore(context)
        val event = ArrivalEvent(
            waypoint.name,
            waypoint.arrivalAlertMethod,
            isFinalDestination = false,
            id = waypoint.id
        )
        if (store.savePendingArrival(waypoint)) activatePending(context, event)
    }

    fun activatePending(context: Context, event: ArrivalEvent) {
        ArrivalAlertService.start(context, event)
        notifyArrival(
            context,
            event.destinationName,
            if (event.isFinalDestination) "目的地に到着しました" else "経由駅に到着しました"
        )
        context.sendBroadcast(
            Intent(GeofenceBroadcastReceiver.ACTION_ARRIVAL).setPackage(context.packageName)
        )
    }

    private fun notifyArrival(context: Context, destinationName: String, title: String) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "到着通知",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "目的地に到着したときの通知"
                    // 音とバイブは ArrivalAlertService が継続実行する。
                    // 通知チャンネル側で二重に鳴らさず、通知表示だけを行う。
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }

        val launchIntent = Intent(context, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        val contentIntent = PendingIntent.getActivity(
            context,
            2001,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title)
            .setContentText(destinationName)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 到着音・バイブは選択された方法に応じて ArrivalAlertService が扱う。
            // 通知自身は表示だけにして、選択に関係なく音やバイブを発生させない。
            .setSilent(true)
            .setFullScreenIntent(contentIntent, true)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    // 直接アラームサービスが音・バイブを継続するため、通知は音なしで表示する。
    // v3以前のチャンネル設定を引き継がないよう新しいIDにする。
    private const val CHANNEL_ID = "arrival_notifications_v4"
    private const val NOTIFICATION_ID = 3001
}
