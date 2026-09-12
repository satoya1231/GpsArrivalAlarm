package com.example.gpsarrivalalarm

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Returns the phone's magnetic heading in degrees (0° = north, clockwise).
 * The value is null while the device has no usable orientation sensor.
 */
@Composable
internal fun rememberDeviceAzimuth(): Float? {
    val context = LocalContext.current
    var azimuth by remember { mutableFloatStateOf(Float.NaN) }

    DisposableEffect(context) {
        val listener = DeviceCompassListener(context) { value ->
            azimuth = value
        }
        listener.start()

        onDispose {
            listener.stop()
        }
    }

    return azimuth.takeUnless { it.isNaN() }
}

private class DeviceCompassListener(
    context: Context,
    private val onAzimuthChanged: (Float) -> Unit
) : SensorEventListener {
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometerSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magneticFieldSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val displayRotation = getDisplayRotation(context)

    private val rotationMatrix = FloatArray(9)
    private val remappedRotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var gravity: FloatArray? = null
    private var magneticField: FloatArray? = null
    private var smoothedAzimuth = Float.NaN

    fun start() {
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME)
        } else {
            accelerometerSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            magneticFieldSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val matrixReady = when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                true
            }

            Sensor.TYPE_ACCELEROMETER -> {
                gravity = event.values.copyOf()
                val gravityValues = gravity
                val magneticValues = magneticField
                gravityValues != null && magneticValues != null &&
                    SensorManager.getRotationMatrix(
                        rotationMatrix,
                        null,
                        gravityValues,
                        magneticValues
                    )
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                magneticField = event.values.copyOf()
                val gravityValues = gravity
                val magneticValues = magneticField
                gravityValues != null && magneticValues != null &&
                    SensorManager.getRotationMatrix(
                        rotationMatrix,
                        null,
                        gravityValues,
                        magneticValues
                    )
            }

            else -> false
        }

        if (!matrixReady) return

        val matrix = if (displayRotation == Surface.ROTATION_0) {
            rotationMatrix
        } else {
            val (xAxis, yAxis) = when (displayRotation) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                xAxis,
                yAxis,
                remappedRotationMatrix
            )
            remappedRotationMatrix
        }

        SensorManager.getOrientation(matrix, orientation)
        val rawAzimuth = normalizeDegrees(Math.toDegrees(orientation[0].toDouble()).toFloat())
        val nextAzimuth = if (smoothedAzimuth.isNaN()) {
            rawAzimuth
        } else {
            val delta = ((rawAzimuth - smoothedAzimuth + 540f) % 360f) - 180f
            normalizeDegrees(smoothedAzimuth + delta * 0.2f)
        }
        smoothedAzimuth = nextAzimuth
        onAzimuthChanged(nextAzimuth)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun getDisplayRotation(context: Context): Int {
        @Suppress("DEPRECATION")
        return (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay
            .rotation
    }
}

private fun normalizeDegrees(degrees: Float): Float {
    return ((degrees % 360f) + 360f) % 360f
}
