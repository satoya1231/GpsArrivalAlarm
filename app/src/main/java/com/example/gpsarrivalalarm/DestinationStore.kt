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
            }
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

    fun savePendingArrival(destination: Destination): Boolean {
        if (prefs.contains(KEY_PENDING_NAME)) return false
        prefs.edit()
            .putLong(KEY_PENDING_ID, destination.id)
            .putString(KEY_PENDING_NAME, destination.name)
            .putString(KEY_PENDING_METHOD, destination.arrivalAlertMethod.name)
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

        return ArrivalEvent(name, method)
    }

    fun clearPendingArrival() {
        prefs.edit()
            .remove(KEY_PENDING_ID)
            .remove(KEY_PENDING_NAME)
            .remove(KEY_PENDING_METHOD)
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
    }
}
