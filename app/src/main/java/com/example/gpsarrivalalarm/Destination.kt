package com.example.gpsarrivalalarm

enum class ArrivalAlertMethod {
    NOTIFICATION,
    SOUND,
    VIBRATION
}

const val NO_DESTINATION_FOLDER = ""

fun ArrivalAlertMethod.label(): String = when (this) {
    ArrivalAlertMethod.NOTIFICATION -> "通知"
    ArrivalAlertMethod.SOUND -> "音"
    ArrivalAlertMethod.VIBRATION -> "バイブ"
}

data class Destination(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val folder: String = NO_DESTINATION_FOLDER,
    val arrivalAlertMethod: ArrivalAlertMethod = ArrivalAlertMethod.VIBRATION,
    val waypoints: List<Waypoint> = emptyList()
)

data class Waypoint(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 500f,
    val arrivalAlertMethod: ArrivalAlertMethod = ArrivalAlertMethod.VIBRATION
)

data class ArrivalEvent(
    val destinationName: String,
    val alertMethod: ArrivalAlertMethod,
    val isFinalDestination: Boolean = true
)
