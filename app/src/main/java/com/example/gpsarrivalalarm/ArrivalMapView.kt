package com.example.gpsarrivalalarm

import android.content.Context
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ArrivalMapView(
    destination: Destination,
    currentLocation: Location?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var destinationMarkerRef by remember { mutableStateOf<Marker?>(null) }
    var currentMarkerRef by remember { mutableStateOf<Marker?>(null) }
    var routeLineRef by remember { mutableStateOf<Polyline?>(null) }
    var lastCenteredLocation by remember { mutableStateOf<Location?>(null) }
    var hasInitialLocationFrame by remember { mutableStateOf(false) }
    var hasInitialDestinationPosition by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            mapViewRef?.onPause()
            mapViewRef?.onDetach()
            mapViewRef = null
            destinationMarkerRef = null
            currentMarkerRef = null
            routeLineRef = null
        }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                createArrivalMap(ctx, destination).also { mapObjects ->
                    mapViewRef = mapObjects.map
                    destinationMarkerRef = mapObjects.destinationMarker
                    currentMarkerRef = mapObjects.currentMarker
                    routeLineRef = mapObjects.routeLine
                    mapObjects.map.onResume()
                }.map
            },
            modifier = Modifier.fillMaxSize(),
            update = { map ->
                mapViewRef = map
                val destinationPoint = GeoPoint(destination.latitude, destination.longitude)
                destinationMarkerRef?.position = destinationPoint

                val location = currentLocation
                val currentPoint = location?.let { GeoPoint(it.latitude, it.longitude) }
                currentMarkerRef?.apply {
                    position = currentPoint ?: position
                    isEnabled = currentPoint != null
                }
                routeLineRef?.setPoints(
                    if (currentPoint == null) emptyList()
                    else listOf(currentPoint, destinationPoint)
                )

                // 初回だけ、目的地が画面上側に来るように地図を配置する。
                // 現在地の取得が遅れた場合は、取得できた時点で現在地も含めて初期配置する。
                if (map.width > 0 && map.height > 0 && !hasInitialLocationFrame &&
                    (location != null || !hasInitialDestinationPosition)
                ) {
                    val initialCenter = if (location == null) {
                        destinationPoint
                    } else {
                        // 監視開始時は、現在地を基準に地図を表示する。
                        GeoPoint(location.latitude, location.longitude)
                    }

                    if (location != null) {
                        val destinationLocation = Location("destination").apply {
                            latitude = destination.latitude
                            longitude = destination.longitude
                        }
                        // 現在地から目的地への方向が画面の真上を向くように初期方位を合わせる。
                        map.setMapOrientation(-location.bearingTo(destinationLocation))
                        setInitialZoomToFitMarkers(map, currentPoint, destinationPoint)
                    }
                    map.controller.setCenter(initialCenter)
                    val targetY = (map.height * INITIAL_DESTINATION_Y_FRACTION).roundToInt()
                    val destinationPixel = map.projection.toPixels(destinationPoint, null)
                    val targetPixel = map.projection.unrotateAndScalePoint(
                        map.width / 2,
                        targetY,
                        null
                    )
                    map.setExpectedCenter(
                        initialCenter,
                        (targetPixel.x - destinationPixel.x).toLong(),
                        (targetPixel.y - destinationPixel.y).toLong()
                    )
                    keepInitialMarkersVisible(
                        map,
                        listOfNotNull(currentPoint, destinationPoint)
                    )
                    hasInitialDestinationPosition = true
                    if (location != null) {
                        hasInitialLocationFrame = true
                        lastCenteredLocation = location
                    }
                } else if (location != null &&
                    hasInitialLocationFrame &&
                    (lastCenteredLocation == null || lastCenteredLocation!!.distanceTo(location) >= 20f)
                ) {
                    // 更新時は中心だけを追従し、ユーザーが設定した縮尺を維持する。
                    val center = GeoPoint(
                        (location.latitude + destination.latitude) / 2.0,
                        (location.longitude + destination.longitude) / 2.0
                    )
                    map.controller.setCenter(center)
                    lastCenteredLocation = location
                }
                map.invalidate()
            }
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            tonalElevation = 6.dp,
            shadowElevation = 4.dp,
            shape = MaterialTheme.shapes.large
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "一覧に戻る")
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(destination.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (currentLocation == null) "現在地を取得中…"
                        else "到着まで地図を表示中",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                FilledIconButton(
                    onClick = {
                        currentLocation?.let { location ->
                            mapViewRef?.controller?.animateTo(GeoPoint(location.latitude, location.longitude))
                        }
                    },
                    enabled = currentLocation != null
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "現在地を表示")
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            tonalElevation = 6.dp,
            shadowElevation = 4.dp,
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    currentLocation?.let { location ->
                        val distance = FloatArray(1)
                        Location.distanceBetween(
                            location.latitude,
                            location.longitude,
                            destination.latitude,
                            destination.longitude,
                            distance
                        )
                        formatMapDistance(distance[0])
                    } ?: "残り距離を取得中…",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        MapOrientationIndicator(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 96.dp, end = 12.dp),
            mapView = mapViewRef
        )

        Text(
            text = "© OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 112.dp)
        )
    }
}

private data class ArrivalMapObjects(
    val map: MapView,
    val destinationMarker: Marker,
    val currentMarker: Marker,
    val routeLine: Polyline
)

private fun createArrivalMap(context: android.content.Context, destination: Destination): ArrivalMapObjects {
    Configuration.getInstance().userAgentValue =
        "GpsArrivalAlarm/1.2 (Android; ${context.packageName})"

    val destinationPoint = GeoPoint(destination.latitude, destination.longitude)
    val map = MapView(context).apply {
        setTileSource(
            XYTileSource(
                "OSM Standard",
                0,
                19,
                256,
                ".png",
                arrayOf("https://tile.openstreetmap.org/")
            )
        )
        setMultiTouchControls(true)
        minZoomLevel = 3.0
        maxZoomLevel = 19.0
        controller.setZoom(15.0)
        controller.setCenter(destinationPoint)
    }

    map.overlays.add(0, RotationGestureOverlay(map).apply { setEnabled(true) })

    val destinationMarker = Marker(map).apply {
        position = destinationPoint
        icon = createDestinationIcon(map.context)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        title = "目的地"
    }
    val currentMarker = Marker(map).apply {
        position = destinationPoint
        icon = createCurrentLocationIcon(map.context)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        title = "現在地"
        isEnabled = false
    }
    val routeLine = Polyline(map).apply {
        outlinePaint.color = 0xff1565c0.toInt()
        outlinePaint.strokeWidth = 6f
    }
    map.overlays.add(routeLine)
    map.overlays.add(destinationMarker)
    map.overlays.add(currentMarker)
    return ArrivalMapObjects(map, destinationMarker, currentMarker, routeLine)
}

private fun createCurrentLocationIcon(context: Context): Drawable {
    return GoogleCurrentLocationDrawable(context.resources.displayMetrics.density)
}

private class GoogleCurrentLocationDrawable(
    private val density: Float
) : Drawable() {
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff1a73e8.toInt()
        style = Paint.Style.FILL
    }

    override fun draw(canvas: AndroidCanvas) {
        val centerX = bounds.exactCenterX()
        val top = bounds.top.toFloat() + 3f * density
        val bottom = bounds.bottom.toFloat() - 3f * density
        val halfWidth = bounds.width() * 0.34f

        val outline = AndroidPath().apply {
            moveTo(centerX, top)
            lineTo(centerX + halfWidth, bottom)
            lineTo(centerX, bottom - halfWidth * 0.38f)
            lineTo(centerX - halfWidth, bottom)
            close()
        }
        val inset = 2f * density
        val inner = AndroidPath().apply {
            moveTo(centerX, top + inset)
            lineTo(centerX + halfWidth - inset, bottom - inset)
            lineTo(centerX, bottom - halfWidth * 0.38f - inset)
            lineTo(centerX - halfWidth + inset, bottom - inset)
            close()
        }
        canvas.drawPath(outline, outlinePaint)
        canvas.drawPath(inner, dotPaint)
    }

    override fun getIntrinsicWidth(): Int = (32f * density).toInt()

    override fun getIntrinsicHeight(): Int = (32f * density).toInt()

    override fun setAlpha(alpha: Int) {
        outlinePaint.alpha = alpha
        dotPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        outlinePaint.colorFilter = colorFilter
        dotPaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private fun createDestinationIcon(context: Context): Drawable {
    return GoogleDestinationDrawable(context.resources.displayMetrics.density)
}

private class GoogleDestinationDrawable(
    private val density: Float
) : Drawable() {
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffea4335.toInt()
        style = Paint.Style.FILL
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    override fun draw(canvas: AndroidCanvas) {
        val centerX = bounds.exactCenterX()
        val top = bounds.top.toFloat() + 2f * density
        val pinRadius = bounds.width() * 0.28f
        val circleCenterY = top + pinRadius
        val bottom = bounds.bottom.toFloat() - 1f * density

        canvas.drawCircle(centerX, circleCenterY, pinRadius, pinPaint)
        val tip = AndroidPath().apply {
            moveTo(centerX - pinRadius * 0.78f, circleCenterY)
            lineTo(centerX + pinRadius * 0.78f, circleCenterY)
            lineTo(centerX, bottom)
            close()
        }
        canvas.drawPath(tip, pinPaint)
        canvas.drawCircle(centerX, circleCenterY, pinRadius * 0.38f, centerPaint)
    }

    override fun getIntrinsicWidth(): Int = (32f * density).toInt()

    override fun getIntrinsicHeight(): Int = (40f * density).toInt()

    override fun setAlpha(alpha: Int) {
        pinPaint.alpha = alpha
        centerPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        pinPaint.colorFilter = colorFilter
        centerPaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private fun setInitialZoomToFitMarkers(
    map: MapView,
    currentPoint: GeoPoint?,
    destinationPoint: GeoPoint
) {
    if (currentPoint == null) return

    // 実際の投影上の距離を使い、現在地と目的地が画面高の約45%離れる倍率を探す。
    val targetPixels = (map.height * 0.45).coerceIn(240.0, 900.0)
    var lowerZoom = map.minZoomLevel
    var upperZoom = map.maxZoomLevel

    repeat(10) {
        val candidateZoom = (lowerZoom + upperZoom) / 2.0
        map.controller.setZoom(candidateZoom)
        map.controller.setCenter(currentPoint)
        val currentPixel = screenPixel(map, currentPoint)
        val destinationPixel = screenPixel(map, destinationPoint)
        val separation = kotlin.math.hypot(
            (destinationPixel.x - currentPixel.x).toDouble(),
            (destinationPixel.y - currentPixel.y).toDouble()
        )
        if (separation > targetPixels) upperZoom = candidateZoom else lowerZoom = candidateZoom
    }
    map.controller.setZoom(lowerZoom)
}

private fun keepInitialMarkersVisible(map: MapView, points: List<GeoPoint>) {
    if (points.isEmpty() || map.width <= 0 || map.height <= 0) return

    val pixels = points.map { screenPixel(map, it) }
    val minX = pixels.minOf { it.x }.toFloat()
    val maxX = pixels.maxOf { it.x }.toFloat()
    val minY = pixels.minOf { it.y }.toFloat()
    val maxY = pixels.maxOf { it.y }.toFloat()
    val density = map.resources.displayMetrics.density
    val horizontalMargin = minOf(48f * density, map.width / 4f)
    val verticalMargin = minOf(112f * density, map.height / 3f)

    val shiftX = when {
        minX < horizontalMargin -> horizontalMargin - minX
        maxX > map.width - horizontalMargin -> map.width - horizontalMargin - maxX
        else -> 0f
    }
    val shiftY = when {
        minY < verticalMargin -> verticalMargin - minY
        maxY > map.height - verticalMargin -> map.height - verticalMargin - maxY
        else -> 0f
    }

    if (shiftX != 0f || shiftY != 0f) {
        // osmdroid の scrollBy は地図上の点を反対方向へ動かすため、符号を反転する。
        map.controller.scrollBy(-shiftX.roundToInt(), -shiftY.roundToInt())
    }
}

private fun screenPixel(map: MapView, point: GeoPoint) : android.graphics.Point {
    val unrotated = map.projection.toPixels(point, null)
    return map.projection.rotateAndScalePoint(unrotated.x, unrotated.y, null)
}

private const val INITIAL_DESTINATION_Y_FRACTION = 0.25f

private fun formatMapDistance(distanceMeters: Float): String {
    return if (distanceMeters < 1_000f) {
        "目的地まで %.0f m".format(distanceMeters)
    } else {
        "目的地まで %.1f km".format(distanceMeters / 1_000f)
    }
}

@Composable
internal fun MapOrientationIndicator(
    modifier: Modifier = Modifier,
    mapView: MapView?
) {
    var mapOrientation by remember(mapView) {
        mutableStateOf(mapView?.getMapOrientation() ?: 0f)
    }

    LaunchedEffect(mapView) {
        val map = mapView ?: return@LaunchedEffect
        while (true) {
            mapOrientation = map.getMapOrientation()
            delay(100L)
        }
    }

    Surface(
        modifier = modifier,
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CompassIcon(mapOrientation)
            Text(
                "北: ${northOffsetDescription(mapOrientation)}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun CompassIcon(mapOrientation: Float) {
    Canvas(
        modifier = Modifier
            .size(32.dp)
            .rotate(mapOrientation)
            .semantics { contentDescription = "北と南を示す方位磁針" }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = minOf(size.width, size.height) * 0.42f
        val needleWidth = radius * 0.24f

        val northNeedle = Path().apply {
            moveTo(center.x, center.y - radius)
            lineTo(center.x + needleWidth, center.y)
            lineTo(center.x - needleWidth, center.y)
            close()
        }
        val southNeedle = Path().apply {
            moveTo(center.x, center.y + radius)
            lineTo(center.x + needleWidth, center.y)
            lineTo(center.x - needleWidth, center.y)
            close()
        }

        drawPath(northNeedle, color = ComposeColor(0xffd32f2f))
        drawPath(southNeedle, color = ComposeColor(0xff1976d2))
        drawCircle(
            color = ComposeColor(0xff5f6368),
            radius = radius,
            center = center,
            style = Stroke(width = 2f)
        )
        drawCircle(color = ComposeColor.White, radius = 2.5f, center = center)
    }
}

private fun normalizeDegrees(degrees: Float): Float {
    return ((degrees % 360f) + 360f) % 360f
}

private fun northOffsetDescription(mapOrientation: Float): String {
    val normalized = normalizeDegrees(mapOrientation)
    val signedOffset = if (normalized > 180f) normalized - 360f else normalized
    val degrees = abs(signedOffset).roundToInt()
    return when {
        degrees == 0 -> "真上 (0°)"
        signedOffset > 0f -> "右に${degrees}°"
        else -> "左に${degrees}°"
    }
}
