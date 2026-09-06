package com.example.gpsarrivalalarm

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

class GeofenceManager(private val context: Context) {
    private val client = LocationServices.getGeofencingClient(context)

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(
            context,
            1001,
            intent,
            // GeofencingClient は Android 12 以降、通知用 PendingIntent を
            // mutable として受け取る必要がある。
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    fun start(destination: Destination, onResult: (Result<Unit>) -> Unit) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            onResult(Result.failure(SecurityException("位置情報の許可がありません")))
            return
        }

        val request = try {
            val geofence = Geofence.Builder()
                .setRequestId(destination.id.toString())
                .setCircularRegion(
                    destination.latitude,
                    destination.longitude,
                    destination.radiusMeters
                )
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()

            GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofence(geofence)
                .build()
        } catch (t: Throwable) {
            onResult(Result.failure(t))
            return
        }

        try {
            client.removeGeofences(pendingIntent).addOnCompleteListener {
                try {
                    client.addGeofences(request, pendingIntent)
                        .addOnSuccessListener {
                            DestinationStore(context).setActiveDestination(destination)
                            onResult(Result.success(Unit))
                        }
                        .addOnFailureListener { onResult(Result.failure(it)) }
                } catch (t: Throwable) {
                    onResult(Result.failure(t))
                }
            }
        } catch (t: Throwable) {
            onResult(Result.failure(t))
        }
    }

    fun stop(onComplete: (() -> Unit)? = null) {
        try {
            client.removeGeofences(pendingIntent).addOnCompleteListener {
                DestinationStore(context).setActiveDestination(null)
                onComplete?.invoke()
            }
        } catch (_: Throwable) {
            DestinationStore(context).setActiveDestination(null)
            onComplete?.invoke()
        }
    }
}
