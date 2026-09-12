package com.example.gpsarrivalalarm

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * 画面が消灯・ロックされても、現在地と目的地の距離を監視するサービス。
 * ジオフェンスは補助として残し、このサービスを到着判定の主経路にする。
 */
class LocationMonitoringService : Service() {
    private lateinit var locationClient: FusedLocationProviderClient
    private var destination: Destination? = null
    private var arrivalHandled = false
    private val announcedWaypointIds = mutableSetOf<Long>()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val currentDestination = destination ?: return
            val location = result.lastLocation ?: return
            // 登録順を守り、まだ通過していない最初の経由駅だけを判定する。
            // 後の駅の判定範囲が重なっていても、先に鳴らないようにする。
            currentDestination.waypoints
                .firstOrNull { it.id !in announcedWaypointIds }
                ?.let { waypoint ->
                    val waypointDistance = FloatArray(1)
                    Location.distanceBetween(
                        location.latitude, location.longitude,
                        waypoint.latitude, waypoint.longitude,
                        waypointDistance
                    )
                    if (waypointDistance[0] <= waypoint.radiusMeters) {
                        announcedWaypointIds += waypoint.id
                        DestinationStore(this@LocationMonitoringService)
                            .markWaypointAnnounced(currentDestination.id, waypoint.id)
                        ArrivalAlertCoordinator.announceWaypoint(
                            this@LocationMonitoringService,
                            waypoint
                        )
                    }
                }
            val distance = FloatArray(1)
            Location.distanceBetween(
                location.latitude,
                location.longitude,
                currentDestination.latitude,
                currentDestination.longitude,
                distance
            )
            if (distance[0] <= currentDestination.radiusMeters) {
                announceArrival(currentDestination)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            stopSelf()
            return START_NOT_STICKY
        }

        val store = DestinationStore(this)
        val destinationId = intent?.getLongExtra(EXTRA_DESTINATION_ID, INVALID_ID)
            ?.takeIf { it != INVALID_ID }
            ?: store.getActiveDestinationId()
        val monitoredDestination = destinationId?.let(store::findById)
        if (monitoredDestination == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        destination = monitoredDestination
        arrivalHandled = false
        announcedWaypointIds.clear()
        announcedWaypointIds += store.getAnnouncedWaypointIds(monitoredDestination.id)
        startForeground(NOTIFICATION_ID, createNotification(monitoredDestination.name))
        requestLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun requestLocationUpdates() {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFineLocation && !hasCoarseLocation) {
            stopSelf()
            return
        }

        stopMonitoring()
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL_MILLIS
        )
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MILLIS)
            .setWaitForAccurateLocation(false)
            .build()
        locationClient.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper()
        ).addOnFailureListener {
            stopSelf()
        }
    }

    private fun announceArrival(currentDestination: Destination) {
        if (arrivalHandled) return
        arrivalHandled = true
        stopMonitoring()
        GeofenceManager(this).stop()
        ArrivalAlertCoordinator.announce(this, currentDestination)
        stopSelf()
    }

    private fun stopMonitoring() {
        if (::locationClient.isInitialized) {
            locationClient.removeLocationUpdates(locationCallback)
        }
    }

    private fun createNotification(destinationName: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "到着監視中",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "画面ロック中も目的地までの距離を監視します"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("到着監視中")
            .setContentText(destinationName)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.example.gpsarrivalalarm.STOP_LOCATION_MONITORING"
        private const val EXTRA_DESTINATION_ID = "destination_id"
        private const val CHANNEL_ID = "location_monitoring"
        private const val NOTIFICATION_ID = 3003
        private const val INVALID_ID = Long.MIN_VALUE
        private const val UPDATE_INTERVAL_MILLIS = 5_000L
        private const val MIN_UPDATE_INTERVAL_MILLIS = 2_000L

        fun start(context: Context, destination: Destination) {
            val intent = Intent(context, LocationMonitoringService::class.java).apply {
                putExtra(EXTRA_DESTINATION_ID, destination.id)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationMonitoringService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }
}
