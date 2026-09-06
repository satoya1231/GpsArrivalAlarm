package com.example.gpsarrivalalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return

        val requestId = event.triggeringGeofences?.firstOrNull()?.requestId ?: return
        val destinationId = requestId.toLongOrNull() ?: return
        // 削除済み目的地などの古いジオフェンス通知で、誤って到着扱いにしない。
        val arrivalDestination = DestinationStore(context).findById(destinationId) ?: return
        val pendingResult = goAsync()
        GeofenceManager(context).stop {
            ArrivalAlertCoordinator.announce(context, arrivalDestination)
            pendingResult.finish()
        }
    }

    companion object {
        const val ACTION_ARRIVAL = "com.example.gpsarrivalalarm.ACTION_ARRIVAL"
    }
}
