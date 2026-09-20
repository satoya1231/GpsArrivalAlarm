package com.example.gpsarrivalalarm

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay

@Composable
fun MapPickerDialog(
    initialLatitude: Double?,
    initialLongitude: Double?,
    isWaypoint: Boolean = false,
    onDismiss: () -> Unit,
    onSelected: (Double, Double, String?) -> Unit
) {
    val context = LocalContext.current
    val azimuth = rememberDeviceAzimuth()
    val placeSearcher = remember { PlaceSearcher(context) }
    val locationType = if (isWaypoint) "経由駅" else "目的地"

    val fallback = GeoPoint(35.681236, 139.767125) // 東京駅付近
    val initial = remember(initialLatitude, initialLongitude) {
        if (
            initialLatitude != null && initialLongitude != null &&
            initialLatitude in -90.0..90.0 && initialLongitude in -180.0..180.0
        ) GeoPoint(initialLatitude, initialLongitude) else fallback
    }

    var selected by remember(initialLatitude, initialLongitude) { mutableStateOf(initial) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var lastSearchedQuery by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceSearchResult>>(emptyList()) }
    var selectedResult by remember { mutableStateOf<PlaceSearchResult?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var markerRef by remember { mutableStateOf<Marker?>(null) }

    fun moveMarker(point: GeoPoint, title: String? = null, animate: Boolean = true) {
        selected = point
        selectedName = title
        markerRef?.apply {
            position = point
            this.title = title ?: locationType
        }
        mapViewRef?.apply {
            invalidate()
            if (animate) controller.animateTo(point)
        }
    }

    fun runSearch() {
        if (query.isBlank() || searching) return
        searching = true
        searchError = null
        placeSearcher.search(query) { result ->
            searching = false
            result.onSuccess {
                lastSearchedQuery = query.trim()
                results = it
                selectedResult = null
                if (it.isEmpty()) searchError = "該当する場所が見つかりませんでした"
            }.onFailure {
                results = emptyList()
                selectedResult = null
                searchError = it.message ?: "検索に失敗しました"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mapViewRef?.onPause()
            mapViewRef?.onDetach()
            mapViewRef = null
            markerRef = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        createOsmMap(ctx, initial, locationType) { point ->
                            moveMarker(point, null, animate = false)
                            results = emptyList()
                            selectedResult = null
                        }.also { (map, marker) ->
                            map.onResume()
                            mapViewRef = map
                            markerRef = marker
                        }.first
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { map ->
                        mapViewRef = map
                        azimuth?.let { map.setMapOrientation(-it) }
                        markerRef?.position = selected
                        map.invalidate()
                    }
                )

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                    tonalElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "閉じる")
                            }
                            Column(Modifier.weight(1f)) {
                                Text("${locationType}を検索・地図から選択")
                                Text(
                                    "OpenStreetMap（APIキー不要）",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("住所・駅名・施設名") },
                                placeholder = { Text("例：名古屋駅 / 東京駅") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.Black,
                                    unfocusedTextColor = Color.Black,
                                    cursorColor = Color.Black,
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White,
                                    focusedLabelColor = Color.Black,
                                    unfocusedLabelColor = Color.DarkGray,
                                    focusedPlaceholderColor = Color.DarkGray,
                                    unfocusedPlaceholderColor = Color.DarkGray,
                                    focusedBorderColor = Color(0xFF1565C0),
                                    unfocusedBorderColor = Color.DarkGray
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                                singleLine = true
                            )
                            Spacer(Modifier.width(6.dp))
                            FilledIconButton(
                                onClick = { runSearch() },
                                enabled = query.isNotBlank() && !searching
                            ) {
                                if (searching) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Search, contentDescription = "検索")
                                }
                            }
                        }

                        if (searchError != null) {
                            Text(
                                text = searchError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }

                        if (results.isNotEmpty()) {
                            Text(
                                text = if (results.size > 1) {
                                    "「$lastSearchedQuery」の候補（${results.size}件）"
                                } else {
                                    "検索候補（1件）"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                            )
                            Text(
                                text = "候補をタップして地図上の位置を確認し、選択してください。",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                            )
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 250.dp)
                                    .padding(top = 6.dp)
                            ) {
                                LazyColumn {
                                    items(results) { result ->
                                        val isSelected = selectedResult == result
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    val point = GeoPoint(result.latitude, result.longitude)
                                                    selectedResult = result
                                                    moveMarker(point, result.title)
                                                    mapViewRef?.controller?.setZoom(16.0)
                                                }
                                                .then(
                                                    if (isSelected) {
                                                        Modifier.padding(2.dp)
                                                    } else {
                                                        Modifier
                                                    }
                                                )
                                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(
                                                    selected = isSelected,
                                                    onClick = null
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(result.title, style = MaterialTheme.typography.titleSmall)
                                                    if (result.subtitle.isNotBlank() && result.subtitle != result.title) {
                                                        Text(result.subtitle, style = MaterialTheme.typography.bodySmall)
                                                    }
                                                }
                                            }
                                            if (isSelected) {
                                                Text(
                                                    "選択中：下のボタンでこの場所を確定できます",
                                                    color = MaterialTheme.colorScheme.primary,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(start = 52.dp, top = 2.dp)
                                                )
                                            }
                                        }
                                        HorizontalDivider()
                                    }
                                }
                            }
                            selectedResult?.let {
                                OutlinedButton(
                                    onClick = {
                                        results = emptyList()
                                        selectedResult = null
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp)
                                ) {
                                    Text("候補の選択を確定")
                                }
                            }
                        }
                    }
                }

                Text(
                    text = "© OpenStreetMap contributors",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 76.dp)
                )

                MapOrientationIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp),
                    azimuth = azimuth
                )

                Button(
                    onClick = {
                        onSelected(selected.latitude, selected.longitude, selectedName)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Icon(Icons.Default.Done, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("この場所を${locationType}にする")
                }
            }
        }
    }
}

private fun createOsmMap(
    context: Context,
    initial: GeoPoint,
    markerTitle: String,
    onMapTap: (GeoPoint) -> Unit
): Pair<MapView, Marker> {
    // OSM のタイルサーバーは、osmdroid の既定 User-Agent や
    // パッケージ名だけの識別しにくい値を 403 にすることがある。
    // アプリ名とバージョンを明示して、すべてのタイル要求に同じ値を使う。
    Configuration.getInstance().userAgentValue =
        "GpsArrivalAlarm/1.2 (Android; ${context.packageName})"

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
        controller.setCenter(initial)
    }

    map.overlays.add(0, RotationGestureOverlay(map).apply { setEnabled(true) })

    val marker = Marker(map).apply {
        position = initial
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        title = markerTitle
    }
    map.overlays.add(marker)

    val receiver = object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
            marker.position = p
            map.invalidate()
            onMapTap(p)
            return true
        }

        override fun longPressHelper(p: GeoPoint): Boolean = false
    }
    map.overlays.add(0, MapEventsOverlay(receiver))

    return map to marker
}
