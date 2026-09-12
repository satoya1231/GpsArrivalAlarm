package com.example.gpsarrivalalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** スマホ再起動またはアプリ更新後に、保存済みの到着監視を復旧する。 */
class MonitoringRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val destination = DestinationStore(context).getActiveDestinationId()
            ?.let { DestinationStore(context).findById(it) }
            ?: return

        // Foreground Serviceを先に再開し、Geofenceの再登録は非同期で完了させる。
        runCatching { LocationMonitoringService.start(context, destination) }
        val pendingResult = goAsync()
        GeofenceManager(context).start(destination) {
            pendingResult.finish()
        }
    }
}
