package com.example.gpsarrivalalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

class ArrivalAlertService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val destinationName = intent?.getStringExtra(EXTRA_DESTINATION_NAME) ?: return START_NOT_STICKY
        val alertMethod = intent.getStringExtra(EXTRA_ALERT_METHOD)
            ?.let { method -> runCatching { ArrivalAlertMethod.valueOf(method) }.getOrNull() }
            ?: ArrivalAlertMethod.VIBRATION

        startForeground(NOTIFICATION_ID, createServiceNotification(destinationName))
        stopAlert()
        when (alertMethod) {
            ArrivalAlertMethod.SOUND -> startSound()
            ArrivalAlertMethod.VIBRATION -> startVibration()
            ArrivalAlertMethod.NOTIFICATION -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopAlert()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSound() {
        val audioManager = getSystemService(AudioManager::class.java)
        if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            // マナーモードでは音を出さず、仕様どおりバイブへ切り替える。
            startVibration()
            return
        }

        // 通知音ではなくアラーム用途として再生することで、画面ロック中や
        // 通知音量が小さい端末でも、アラーム音量・ロック画面の音声経路を使う。
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return

        val player = runCatching { MediaPlayer.create(this, uri) }.getOrNull() ?: return
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.isLooping = true
            player.start()
            mediaPlayer = player
        }.onFailure {
            player.release()
        }
    }

    private fun startVibration() {
        val pattern = longArrayOf(0, 700, 300)
        val effect = VibrationEffect.createWaveform(pattern, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vibrator = getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator?.vibrate(effect)
    }

    private fun stopAlert() {
        mediaPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) player.stop()
            }
            player.release()
        }
        mediaPlayer = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun createServiceNotification(destinationName: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    SERVICE_CHANNEL_ID,
                    "到着アラーム実行中",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "確認画面を閉じるまで到着アラームを継続します"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("到着アラーム実行中")
            .setContentText(destinationName)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.example.gpsarrivalalarm.STOP_ARRIVAL_ALERT"
        private const val EXTRA_DESTINATION_NAME = "destination_name"
        private const val EXTRA_ALERT_METHOD = "alert_method"
        private const val SERVICE_CHANNEL_ID = "arrival_alert_service"
        private const val NOTIFICATION_ID = 3002

        fun start(context: Context, event: ArrivalEvent) {
            val intent = Intent(context, ArrivalAlertService::class.java).apply {
                putExtra(EXTRA_DESTINATION_NAME, event.destinationName)
                putExtra(EXTRA_ALERT_METHOD, event.alertMethod.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ArrivalAlertService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }
}
