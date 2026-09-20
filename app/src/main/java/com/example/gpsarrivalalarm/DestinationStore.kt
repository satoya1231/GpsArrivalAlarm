package com.example.gpsarrivalalarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private fun JSONArray.toArrivalAlertMethods(): Set<ArrivalAlertMethod> =
    buildSet {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let { value ->
                runCatching { ArrivalAlertMethod.valueOf(value) }
                    .getOrNull()
                    ?.let(::add)
            }
        }
    }.ifEmpty { setOf(ArrivalAlertMethod.VIBRATION) }

private fun String.toArrivalAlertMethods(): Set<ArrivalAlertMethod> =
    runCatching { setOf(ArrivalAlertMethod.valueOf(this)) }
        .getOrDefault(setOf(ArrivalAlertMethod.VIBRATION))

class DestinationStore(context: Context) {
    private val prefs = context.getSharedPreferences("destinations", Context.MODE_PRIVATE)

    fun load(): List<Destination> {
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    runCatching { parseDestination(array.getJSONObject(i)) }
                        .getOrNull()
                        ?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseDestination(item: JSONObject): Destination {
        val destination = Destination(
            id = item.getLong("id"),
            name = item.getString("name"),
            folder = item.optString("folder", NO_DESTINATION_FOLDER)
                .trim()
                .let { if (it == "未分類") NO_DESTINATION_FOLDER else it },
            latitude = item.getDouble("latitude"),
            longitude = item.getDouble("longitude"),
            radiusMeters = item.getDouble("radiusMeters").toFloat(),
            alertMethods = item.optJSONArray("alertMethods")?.toArrivalAlertMethods()
                ?: item.optString(
                "arrivalAlertMethod",
                ArrivalAlertMethod.VIBRATION.name
            ).toArrivalAlertMethods(),
            waypoints = item.optJSONArray("waypoints")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        val point = array.optJSONObject(index) ?: continue
                        runCatching {
                            Waypoint(
                                id = point.getLong("id"),
                                name = point.getString("name"),
                                latitude = point.getDouble("latitude"),
                                longitude = point.getDouble("longitude"),
                                radiusMeters = point.optDouble(
                                    "radiusMeters",
                                    DEFAULT_ARRIVAL_RADIUS_METERS.toDouble()
                                ).toFloat(),
                                alertMethods = point.optJSONArray("alertMethods")?.toArrivalAlertMethods()
                                    ?: point.optString(
                                    "arrivalAlertMethod", ArrivalAlertMethod.VIBRATION.name
                                ).toArrivalAlertMethods()
                            )
                        }.getOrNull()?.let(::add)
                    }
                }
            } ?: emptyList()
        )
        require(destination.id > 0L)
        require(destination.name.isNotBlank())
        require(destination.latitude.isFinite() && destination.latitude in -90.0..90.0)
        require(destination.longitude.isFinite() && destination.longitude in -180.0..180.0)
        require(destination.radiusMeters.isFinite() && destination.radiusMeters >= 1f)
        return destination
    }

    fun save(items: List<Destination>) {
        val array = JSONArray()
        items.forEach { destination ->
            array.put(
                JSONObject().apply {
                    put("id", destination.id)
                    put("name", destination.name)
                    put("folder", destination.folder)
                    put("latitude", destination.latitude)
                    put("longitude", destination.longitude)
                    put("radiusMeters", destination.radiusMeters.toDouble())
                    put("alertMethods", JSONArray(destination.alertMethods.map { it.name }))
                    put("arrivalAlertMethod", destination.arrivalAlertMethod.name)
                    put("waypoints", JSONArray().apply {
                        destination.waypoints.forEach { point ->
                            put(JSONObject().apply {
                                put("id", point.id)
                                put("name", point.name)
                                put("latitude", point.latitude)
                                put("longitude", point.longitude)
                                put("radiusMeters", point.radiusMeters.toDouble())
                            put("alertMethods", JSONArray(point.alertMethods.map { it.name }))
                            put("arrivalAlertMethod", point.arrivalAlertMethod.name)
                            })
                        }
                    })
                }
            )
        }
        prefs.edit()
            .putString(KEY_LIST, array.toString())
            .putString(KEY_FOLDERS, folderArray((loadFolders() + items.map { it.folder }).distinct()).toString())
            .apply()
    }

    fun loadFolders(): List<String> {
        val stored = prefs.getString(KEY_FOLDERS, "[]") ?: "[]"
        val folders = runCatching {
            val array = JSONArray(stored)
            buildList {
                for (i in 0 until array.length()) {
                    array.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
        return (folders + load().map { it.folder })
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "未分類" }
            .distinct()
    }

    fun saveFolders(folders: List<String>) {
        prefs.edit()
            .putString(KEY_FOLDERS, folderArray(folders).toString())
            .apply()
    }

    fun setSelectedFolder(folder: String?) {
        prefs.edit().apply {
            if (folder.isNullOrBlank()) remove(KEY_SELECTED_FOLDER)
            else putString(KEY_SELECTED_FOLDER, folder)
        }.apply()
    }

    fun getSelectedFolder(): String? = prefs.getString(KEY_SELECTED_FOLDER, null)
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun folderArray(folders: List<String>): JSONArray {
        return JSONArray().apply {
            folders.map { it.trim() }
                .filter { it.isNotBlank() && it != "未分類" }
                .distinct()
                .forEach(::put)
        }
    }

    fun setActiveDestination(destination: Destination?) {
        if (destination == null) {
            prefs.edit().remove(KEY_ACTIVE).apply()
        } else {
            prefs.edit().putString(KEY_ACTIVE, destination.id.toString()).apply()
        }
    }

    fun getActiveDestinationId(): Long? = prefs.getString(KEY_ACTIVE, null)?.toLongOrNull()

    fun findById(id: Long): Destination? = load().firstOrNull { it.id == id }

    fun getAnnouncedWaypointIds(destinationId: Long): Set<Long> {
        if (prefs.getLong(KEY_WAYPOINT_ROUTE_ID, -1L) != destinationId) return emptySet()
        return prefs.getStringSet(KEY_ANNOUNCED_WAYPOINTS, emptySet()).orEmpty()
            .mapNotNull(String::toLongOrNull).toSet()
    }

    fun markWaypointAnnounced(destinationId: Long, waypointId: Long) {
        val updated = getAnnouncedWaypointIds(destinationId) + waypointId
        prefs.edit()
            .putLong(KEY_WAYPOINT_ROUTE_ID, destinationId)
            .putStringSet(KEY_ANNOUNCED_WAYPOINTS, updated.map(Long::toString).toSet())
            .apply()
    }

    fun resetWaypointProgress(destinationId: Long) {
        prefs.edit()
            .putLong(KEY_WAYPOINT_ROUTE_ID, destinationId)
            .remove(KEY_ANNOUNCED_WAYPOINTS)
            .apply()
    }

    fun savePendingArrival(destination: Destination): Boolean {
        return enqueuePendingArrival(
            ArrivalEvent(
                destination.name,
                destination.alertMethods,
                isFinalDestination = true,
                id = destination.id
            )
        )
    }

    fun savePendingArrival(waypoint: Waypoint): Boolean {
        return enqueuePendingArrival(
            ArrivalEvent(
                waypoint.name,
                waypoint.alertMethods,
                isFinalDestination = false,
                id = waypoint.id
            )
        )
    }

    /**
     * 到着イベントを待機列へ追加する。
     * 戻り値は、このイベントをすぐ鳴らす必要がある場合だけ true。
     * すでに同じイベントがある場合や、先行イベントの確認待ちなら false。
     */
    private fun enqueuePendingArrival(event: ArrivalEvent): Boolean = synchronized(PENDING_LOCK) {
        val queue = loadPendingArrivals()
        if (queue.any { it.id == event.id && it.isFinalDestination == event.isFinalDestination }) {
            return@synchronized false
        }
        val shouldAlertNow = queue.isEmpty()
        savePendingArrivals(queue + event)
        shouldAlertNow
    }

    fun getPendingArrival(): ArrivalEvent? = synchronized(PENDING_LOCK) {
        loadPendingArrivals().firstOrNull()
    }

    /** 先頭の到着イベントを確認済みにして、次のイベントを返す。 */
    fun clearPendingArrival(): ArrivalEvent? = synchronized(PENDING_LOCK) {
        val remaining = loadPendingArrivals().drop(1)
        savePendingArrivals(remaining)
        remaining.firstOrNull()
    }

    private fun loadPendingArrivals(): List<ArrivalEvent> {
        val storedQueue = prefs.getString(KEY_PENDING_QUEUE, null)
        if (storedQueue != null) {
            return runCatching {
                val array = JSONArray(storedQueue)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val name = item.optString("name").trim()
                        if (name.isBlank()) continue
                        val methods = item.optJSONArray("methods")?.toArrivalAlertMethods()
                            ?: item.optString(
                            "method",
                            ArrivalAlertMethod.VIBRATION.name
                        ).toArrivalAlertMethods()
                        add(
                            ArrivalEvent(
                                destinationName = name,
                                alertMethods = methods,
                                isFinalDestination = item.optBoolean("isFinal", true),
                                id = item.optLong("id", 0L)
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

        // 旧版で確認待ちだった1件を、新しい待機列へ引き継ぐ。
        val legacyName = prefs.getString(KEY_PENDING_NAME, null)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val legacyMethods = prefs.getString(
            KEY_PENDING_METHOD,
            ArrivalAlertMethod.VIBRATION.name
        )?.toArrivalAlertMethods() ?: setOf(ArrivalAlertMethod.VIBRATION)
        return listOf(
            ArrivalEvent(
                destinationName = legacyName,
                alertMethods = legacyMethods,
                isFinalDestination = prefs.getBoolean(KEY_PENDING_IS_FINAL, true),
                id = prefs.getLong(KEY_PENDING_ID, 0L)
            )
        )
    }

    private fun savePendingArrivals(events: List<ArrivalEvent>) {
        val array = JSONArray().apply {
            events.forEach { event ->
                put(JSONObject().apply {
                    put("id", event.id)
                    put("name", event.destinationName)
                    put("methods", JSONArray(event.alertMethods.map { it.name }))
                    put("method", event.alertMethod.name)
                    put("isFinal", event.isFinalDestination)
                })
            }
        }
        prefs.edit()
            .putString(KEY_PENDING_QUEUE, array.toString())
            .remove(KEY_PENDING_ID)
            .remove(KEY_PENDING_NAME)
            .remove(KEY_PENDING_METHOD)
            .remove(KEY_PENDING_IS_FINAL)
            .commit()
    }

    companion object {
        private const val KEY_LIST = "destination_list"
        private const val KEY_FOLDERS = "destination_folders"
        private const val KEY_SELECTED_FOLDER = "selected_destination_folder"
        private const val KEY_ACTIVE = "active_destination"
        private const val KEY_PENDING_ID = "pending_arrival_id"
        private const val KEY_PENDING_NAME = "pending_arrival_name"
        private const val KEY_PENDING_METHOD = "pending_arrival_method"
        private const val KEY_PENDING_IS_FINAL = "pending_arrival_is_final"
        private const val KEY_PENDING_QUEUE = "pending_arrival_queue"
        private const val KEY_WAYPOINT_ROUTE_ID = "waypoint_route_id"
        private const val KEY_ANNOUNCED_WAYPOINTS = "announced_waypoints"
        private val PENDING_LOCK = Any()
    }
}
