package com.example.gpsarrivalalarm

enum class ArrivalAlertMethod {
    NOTIFICATION,
    SOUND,
    VIBRATION
}

const val NO_DESTINATION_FOLDER = ""
const val DEFAULT_ARRIVAL_RADIUS_METERS = 1000f

fun ArrivalAlertMethod.label(): String = when (this) {
    ArrivalAlertMethod.NOTIFICATION -> "通知"
    ArrivalAlertMethod.SOUND -> "音"
    ArrivalAlertMethod.VIBRATION -> "バイブ"
}

fun Set<ArrivalAlertMethod>.label(): String =
    ArrivalAlertMethod.entries
        .filter { it in this }
        .joinToString("・") { it.label() }
        .ifBlank { "なし" }

data class Destination(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val folder: String = NO_DESTINATION_FOLDER,
    val alertMethods: Set<ArrivalAlertMethod> = setOf(ArrivalAlertMethod.VIBRATION),
    val waypoints: List<Waypoint> = emptyList()
) {
    val arrivalAlertMethod: ArrivalAlertMethod
        get() = alertMethods.firstOrNull() ?: ArrivalAlertMethod.VIBRATION
}

data class Waypoint(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = DEFAULT_ARRIVAL_RADIUS_METERS,
    val alertMethods: Set<ArrivalAlertMethod> = setOf(ArrivalAlertMethod.VIBRATION)
) {
    val arrivalAlertMethod: ArrivalAlertMethod
        get() = alertMethods.firstOrNull() ?: ArrivalAlertMethod.VIBRATION
}

data class ArrivalEvent(
    val destinationName: String,
    val alertMethods: Set<ArrivalAlertMethod>,
    val isFinalDestination: Boolean = true,
    val id: Long = 0L
) {
    val alertMethod: ArrivalAlertMethod
        get() = alertMethods.firstOrNull() ?: ArrivalAlertMethod.VIBRATION
}
