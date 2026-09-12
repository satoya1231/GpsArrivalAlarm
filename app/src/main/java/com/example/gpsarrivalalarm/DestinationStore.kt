package com.example.gpsarrivalalarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
            arrivalAlertMethod = item.optString(
                "arrivalAlertMethod",
                ArrivalAlertMethod.VIBRATION.name
            ).let { method ->
                runCatching { ArrivalAlertMethod.valueOf(method) }
                    .getOrDefault(ArrivalAlertMethod.VIBRATION)
            },
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
                                radiusMeters = point.optDouble("radiusMeters", 500.0).toFloat(),
                                arrivalAlertMethod = point.optString(
                                    "arrivalAlertMethod", ArrivalAlertMethod.VIBRATION.name
                                ).let { runCatching { ArrivalAlertMethod.valueOf(it) }.getOrDefault(ArrivalAlertMethod.VIBRATION) }
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
                    put("arrivalAlertMethod", destination.arrivalAlertMethod.name)
                    put("waypoints", JSONArray().apply {
                        destination.waypoints.forEach { point ->
                            put(JSONObject().apply {
                                put("id", point.id)
                                put("name", point.name)
                                put("latitude", point.latitude)
                                put("longitude", point.longitude)
                                put("radiusMeters", point.radiusMeters.toDouble())
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
        return savePendingArrival(destination.id, destination.name, destination.arrivalAlertMethod, true)
    }

    fun savePendingArrival(waypoint: Waypoint): Boolean {
        return savePendingArrival(waypoint.id, waypoint.name, waypoint.arrivalAlertMethod, false)
    }

    private fun savePendingArrival(id: Long, name: String, method: ArrivalAlertMethod, isFinal: Boolean): Boolean {
        if (prefs.contains(KEY_PENDING_NAME)) return false
        prefs.edit()
            .putLong(KEY_PENDING_ID, id)
            .putString(KEY_PENDING_NAME, name)
            .putString(KEY_PENDING_METHOD, method.name)
            .putBoolean(KEY_PENDING_IS_FINAL, isFinal)
            .apply()
        return true
    }

    fun getPendingArrival(): ArrivalEvent? {
        val name = prefs.getString(KEY_PENDING_NAME, null) ?: return null
        val method = prefs.getString(
            KEY_PENDING_METHOD,
            ArrivalAlertMethod.VIBRATION.name
        )?.let {
            runCatching { ArrivalAlertMethod.valueOf(it) }
                .getOrDefault(ArrivalAlertMethod.VIBRATION)
        } ?: ArrivalAlertMethod.VIBRATION

        return ArrivalEvent(name, method, prefs.getBoolean(KEY_PENDING_IS_FINAL, true))
    }

    fun clearPendingArrival() {
        prefs.edit()
            .remove(KEY_PENDING_ID)
            .remove(KEY_PENDING_NAME)
            .remove(KEY_PENDING_METHOD)
            .remove(KEY_PENDING_IS_FINAL)
            .apply()
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
        private const val KEY_WAYPOINT_ROUTE_ID = "waypoint_route_id"
        private const val KEY_ANNOUNCED_WAYPOINTS = "announced_waypoints"
    }
}
