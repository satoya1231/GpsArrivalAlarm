package com.example.gpsarrivalalarm

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.location.Location
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private data class WaypointDraft(
        val id: Long,
        val name: String,
        val latitude: String,
        val longitude: String,
        val radius: String,
        val alertMethod: ArrivalAlertMethod
    )
    private lateinit var store: DestinationStore
    private lateinit var geofenceManager: GeofenceManager
    private var arrivalSignal by mutableIntStateOf(0)

    private val arrivalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == GeofenceBroadcastReceiver.ACTION_ARRIVAL) {
                arrivalSignal++
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
        store = DestinationStore(this)
        geofenceManager = GeofenceManager(this)

        setContent {
            MaterialTheme {
                ArrivalAlarmApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A full-screen notification may reuse the existing activity instance.
        // Re-read the pending arrival in that case as well.
        arrivalSignal++
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            arrivalReceiver,
            IntentFilter(GeofenceBroadcastReceiver.ACTION_ARRIVAL),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // アプリ更新後や監視サービス再起動後も、保存済みの監視を再開する。
        store.getActiveDestinationId()
            ?.let(store::findById)
            ?.let { LocationMonitoringService.start(this, it) }
    }

    override fun onStop() {
        unregisterReceiver(arrivalReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        arrivalSignal++
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ArrivalAlarmApp() {
        var destinations by remember { mutableStateOf(store.load()) }
        var activeId by remember { mutableStateOf(store.getActiveDestinationId()) }
        var folders by remember { mutableStateOf(store.loadFolders()) }
        var selectedFolder by remember {
            mutableStateOf(store.getSelectedFolder()?.takeIf { it in folders })
        }
        var folderMenuExpanded by remember { mutableStateOf(false) }
        var showFolderManager by remember { mutableStateOf(false) }
        var reorderMode by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf<Destination?>(null) }
        var deleting by remember { mutableStateOf<Destination?>(null) }
        var showEditor by remember { mutableStateOf(false) }
        var pendingStart by remember { mutableStateOf<Destination?>(null) }
        var currentLocation by remember { mutableStateOf<Location?>(null) }
        var arrivalEvent by remember { mutableStateOf<ArrivalEvent?>(null) }
        var mapMode by remember { mutableStateOf(activeId != null) }
        var followPhoneOrientation by remember { mutableStateOf(true) }
        val listState = rememberLazyListState()
        var draggingId by remember { mutableStateOf<Long?>(null) }
        var draggingOffset by remember { mutableFloatStateOf(0f) }

        fun consumePendingArrival() {
            arrivalEvent = store.getPendingArrival()
            if (arrivalEvent?.isFinalDestination == true) {
                activeId = null
                mapMode = false
            }
        }

        fun dismissArrival() {
            ArrivalAlertService.stop(this@MainActivity)
            val nextArrival = store.clearPendingArrival()
            arrivalEvent = nextArrival
            if (nextArrival != null) {
                ArrivalAlertCoordinator.activatePending(this@MainActivity, nextArrival)
            }
        }

        LaunchedEffect(arrivalSignal) {
            consumePendingArrival()
        }

        LaunchedEffect(activeId) {
            if (activeId == null) mapMode = false
        }

        DisposableEffect(activeId) {
            currentLocation = null
            val locationClient = LocationServices.getFusedLocationProviderClient(this@MainActivity)
            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    currentLocation = result.lastLocation
                }
            }
            val hasLocationPermission = ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (activeId != null && hasLocationPermission) {
                locationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) currentLocation = location
                }
                val request = LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    5_000L
                )
                    .setMinUpdateIntervalMillis(2_000L)
                    .build()
                locationClient.requestLocationUpdates(
                    request,
                    locationCallback,
                    Looper.getMainLooper()
                )
            }

            onDispose {
                locationClient.removeLocationUpdates(locationCallback)
            }
        }

        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

        val backgroundSettingsLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            val destination = pendingStart
            val granted = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            pendingStart = null
            if (granted && destination != null) {
                startMonitoring(destination) {
                    activeId = destination.id
                    mapMode = true
                }
            } else if (destination != null) {
                toast("位置情報を『常に許可』にしてから、もう一度監視開始を押してください")
            }
        }

        fun openBackgroundLocationSettings() {
            toast("位置情報の権限を『常に許可』にしてください")
            backgroundSettingsLauncher.launch(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }

        val foregroundPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val destination = pendingStart
            if (fine && destination != null) {
                if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    openBackgroundLocationSettings()
                } else {
                    pendingStart = null
                    startMonitoring(destination) {
                        activeId = destination.id
                        mapMode = true
                    }
                }
            } else {
                pendingStart = null
                toast("正確な位置情報を許可してください")
            }
        }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        fun requestStart(destination: Destination) {
            pendingStart = destination
            val fineGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!fineGranted) {
                foregroundPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            } else if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                openBackgroundLocationSettings()
            } else {
                pendingStart = null
                startMonitoring(destination) {
                    activeId = destination.id
                    mapMode = true
                }
            }
        }

        fun moveDestination(id: Long, offset: Int, folder: String?) {
            val scopedDestinations = folder?.let { selectedFolderName ->
                destinations.filter { it.folder == selectedFolderName }
            } ?: destinations
            val currentIndex = scopedDestinations.indexOfFirst { it.id == id }
            val newIndex = currentIndex + offset
            if (currentIndex < 0 || newIndex !in scopedDestinations.indices) return

            val reordered = scopedDestinations.toMutableList().apply {
                add(newIndex, removeAt(currentIndex))
            }
            val updated = if (folder == null) {
                reordered
            } else {
                val reorderedItems = reordered.iterator()
                destinations.map {
                    if (it.folder == folder) reorderedItems.next() else it
                }
            }
            destinations = updated
            store.save(updated)
        }

        fun moveDraggedDestination(deltaY: Float, folder: String?) {
            val id = draggingId ?: return
            if (deltaY == 0f) return
            draggingOffset += deltaY

            val scopedDestinations = folder?.let { selectedFolderName ->
                destinations.filter { it.folder == selectedFolderName }
            } ?: destinations
            val currentIndex = scopedDestinations.indexOfFirst { it.id == id }
            val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
                ?: return
            val currentTop = itemInfo.offset + draggingOffset
            val currentBottom = currentTop + itemInfo.size
            val target = if (deltaY > 0f) {
                listState.layoutInfo.visibleItemsInfo
                    .filter { it.index > currentIndex }
                    .firstOrNull { currentBottom > it.offset + it.size / 2 }
            } else {
                listState.layoutInfo.visibleItemsInfo
                    .filter { it.index < currentIndex }
                    .lastOrNull { currentTop < it.offset + it.size / 2 }
            } ?: return

            val reordered = scopedDestinations.toMutableList().apply {
                add(target.index, removeAt(currentIndex))
            }
            val updated = if (folder == null) {
                reordered
            } else {
                val reorderedItems = reordered.iterator()
                destinations.map {
                    if (it.folder == folder) reorderedItems.next() else it
                }
            }
            destinations = updated
            draggingOffset += itemInfo.offset - target.offset
            store.save(updated)
        }

        fun endDragging() {
            draggingId = null
            draggingOffset = 0f
        }

        fun createFolder(name: String) {
            val folder = name.trim()
            when {
                folder.isBlank() -> toast("フォルダ名を入力してください")
                folders.any { it.equals(folder, ignoreCase = true) } -> toast("同じ名前のフォルダがあります")
                else -> {
                    val updated = (folders + folder).distinct()
                    store.saveFolders(updated)
                    folders = updated
                    toast("「$folder」フォルダを作成しました")
                }
            }
        }

        fun selectFolder(folder: String?) {
            selectedFolder = folder
            store.setSelectedFolder(folder)
            reorderMode = false
        }

        fun deleteFolder(folder: String) {
            if (folder.isBlank()) return
            val updatedDestinations = destinations.map {
                if (it.folder == folder) it.copy(folder = NO_DESTINATION_FOLDER) else it
            }
            destinations = updatedDestinations
            store.save(updatedDestinations)
            val updatedFolders = folders.filterNot { it == folder }
            store.saveFolders(updatedFolders)
            folders = store.loadFolders()
            if (selectedFolder == folder) selectFolder(null)
        }

        fun deleteDestination(destination: Destination) {
            if (activeId == destination.id) {
                geofenceManager.stop { }
                LocationMonitoringService.stop(this@MainActivity)
                activeId = null
                mapMode = false
            }
            val updated = destinations.filterNot { it.id == destination.id }
            destinations = updated
            store.save(updated)
            deleting = null
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (mapMode) "地図表示" else "GPS到着アラーム") },
                    actions = {
                        if (mapMode) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(start = 4.dp)
                            ) {
                                Text(
                                    if (followPhoneOrientation) "スマホ向き" else "手動回転",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Switch(
                                    checked = followPhoneOrientation,
                                    onCheckedChange = { followPhoneOrientation = it },
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                        }
                        if (!mapMode) {
                            Box {
                                TextButton(onClick = { folderMenuExpanded = true }) {
                                    Icon(Icons.Default.Folder, contentDescription = null)
                                    Spacer(Modifier.width(2.dp))
                                    Text(selectedFolder ?: "すべて")
                                }
                                DropdownMenu(
                                    expanded = folderMenuExpanded,
                                    onDismissRequest = { folderMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("すべてのフォルダ") },
                                        onClick = {
                                            selectFolder(null)
                                            folderMenuExpanded = false
                                        }
                                    )
                                    folders.forEach { folder ->
                                        DropdownMenuItem(
                                            text = { Text(folder) },
                                            onClick = {
                                                selectFolder(folder)
                                                folderMenuExpanded = false
                                            }
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("フォルダを管理") },
                                        onClick = {
                                            folderMenuExpanded = false
                                            showFolderManager = true
                                        }
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = { mapMode = !mapMode },
                            enabled = activeId != null
                        ) {
                            Icon(
                                if (mapMode) Icons.AutoMirrored.Filled.ViewList else Icons.Default.Map,
                                contentDescription = if (mapMode) "一覧を表示" else "地図を表示"
                            )
                        }
                        IconButton(
                            onClick = {
                                reorderMode = !reorderMode
                            },
                        ) {
                            Icon(
                                if (reorderMode) Icons.Default.Check else Icons.Default.SwapVert,
                                contentDescription = if (reorderMode) "並べ替えを終了" else "並べ替え"
                            )
                        }
                    }
                )
            },
            floatingActionButton = {
                if (!mapMode) {
                    FloatingActionButton(onClick = {
                        editing = null
                        showEditor = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "目的地追加")
                    }
                }
            }
        ) { padding ->
            val activeDestination = destinations.firstOrNull { it.id == activeId }
            if (mapMode && activeDestination != null) {
                ArrivalMapView(
                    destination = activeDestination,
                    currentLocation = currentLocation,
                    followPhoneOrientation = followPhoneOrientation,
                    onBack = { mapMode = false },
                    modifier = Modifier.padding(padding)
                )
            } else {
                val visibleItems = selectedFolder?.let { folder ->
                    destinations.filter { it.folder == folder }
                } ?: destinations

                if (visibleItems.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (selectedFolder == null) {
                                "右下の＋から目的地を登録してください"
                            } else {
                                "「$selectedFolder」フォルダには目的地がありません"
                            }
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(visibleItems, key = { it.id }) { destination ->
                            DestinationCard(
                                destination = destination,
                                active = activeId == destination.id,
                                alertMethod = destination.arrivalAlertMethod,
                                dragEnabled = true,
                                dragging = draggingId == destination.id,
                                dragOffset = if (draggingId == destination.id) draggingOffset else 0f,
                                onDragStart = {
                                    draggingId = destination.id
                                    draggingOffset = 0f
                                },
                                onDrag = { deltaY -> moveDraggedDestination(deltaY, selectedFolder) },
                                onDragEnd = ::endDragging,
                                remainingDistanceMeters = if (activeId == destination.id) {
                                    currentLocation?.let { location ->
                                        val result = FloatArray(1)
                                        Location.distanceBetween(
                                            location.latitude,
                                            location.longitude,
                                            destination.latitude,
                                            destination.longitude,
                                            result
                                        )
                                        result[0]
                                    }
                                } else {
                                    null
                                },
                                reorderMode = reorderMode,
                                position = visibleItems.indexOfFirst { it.id == destination.id },
                                itemCount = visibleItems.size,
                                onMoveUp = { moveDestination(destination.id, -1, selectedFolder) },
                                onMoveDown = { moveDestination(destination.id, 1, selectedFolder) },
                                onEdit = {
                                    editing = destination
                                    showEditor = true
                                },
                                onDelete = { deleting = destination },
                                onStart = { requestStart(destination) },
                                onStop = {
                                    geofenceManager.stop {
                                        LocationMonitoringService.stop(this@MainActivity)
                                        activeId = null
                                        mapMode = false
                                        toast("監視を停止しました")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        if (showFolderManager) {
            FolderManagerDialog(
                folders = folders,
                onDismiss = { showFolderManager = false },
                onCreateFolder = ::createFolder,
                onDeleteFolder = ::deleteFolder
            )
        }

        if (showEditor) {
            DestinationEditorDialog(
                initial = editing,
                folders = folders,
                initialFolder = selectedFolder,
                onDismiss = { showEditor = false },
                onSave = { saved ->
                    destinations = if (editing == null) {
                        destinations + saved
                    } else {
                        destinations.map { if (it.id == saved.id) saved else it }
                    }
                    store.save(destinations)
                    if (activeId == saved.id) {
                        geofenceManager.start(saved) { result ->
                            result.onSuccess {
                                LocationMonitoringService.start(this@MainActivity, saved)
                            }
                        }
                    }
                    showEditor = false
                }
            )
        }

        arrivalEvent?.let { event ->
            AlertDialog(
                onDismissRequest = ::dismissArrival,
                title = {
                    Text(
                        if (event.isFinalDestination) "目的地に到着しました"
                        else "経由駅に到着しました"
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("「${event.destinationName}」に到着しました。")
                        Text(
                            if (event.isFinalDestination) {
                                "到着監視は自動的に停止しました。"
                            } else {
                                "次の経由駅と目的地の監視を続けます。"
                            }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = ::dismissArrival) {
                        Text("確認")
                    }
                }
            )
        }

        deleting?.let { destination ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("目的地を削除") },
                text = { Text("「${destination.name}」を削除しますか？") },
                confirmButton = {
                    TextButton(onClick = { deleteDestination(destination) }) {
                        Text("削除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleting = null }) {
                        Text("キャンセル")
                    }
                }
            )
        }
    }

    @Composable
    private fun DestinationCard(
        destination: Destination,
        active: Boolean,
        alertMethod: ArrivalAlertMethod,
        dragEnabled: Boolean,
        dragging: Boolean,
        dragOffset: Float,
        onDragStart: () -> Unit,
        onDrag: (Float) -> Unit,
        onDragEnd: () -> Unit,
        remainingDistanceMeters: Float?,
        reorderMode: Boolean,
        position: Int,
        itemCount: Int,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit,
        onStart: () -> Unit,
        onStop: () -> Unit
    ) {
        val dragModifier = if (dragEnabled) {
            Modifier.pointerInput(destination.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDragCancel = { onDragEnd() },
                    onDragEnd = { onDragEnd() },
                    onDrag = { _, dragAmount ->
                        onDrag(dragAmount.y)
                    }
                )
            }
        } else {
            Modifier
        }

        ElevatedCard(
            Modifier
                .fillMaxWidth()
                .zIndex(if (dragging) 1f else 0f)
                .offset { IntOffset(0, if (dragging) dragOffset.roundToInt() else 0) }
                .then(dragModifier)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(destination.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (destination.folder.isBlank()) "フォルダなし"
                            else "フォルダ: ${destination.folder}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text("到着範囲 ${destination.radiusMeters.toInt()} m")
                        Text("到着時：${alertMethod.label()}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "緯度 %.6f / 経度 %.6f".format(destination.latitude, destination.longitude),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (active) {
                    Text("● この目的地を監視中", color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = remainingDistanceMeters?.let(::formatDistance)
                            ?: "残り距離を取得中…",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                if (reorderMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "${position + 1}番目",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        IconButton(
                            onClick = onMoveUp,
                            enabled = position > 0
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上へ移動")
                        }
                        IconButton(
                            onClick = onMoveDown,
                            enabled = position < itemCount - 1
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下へ移動")
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = if (active) onStop else onStart, modifier = Modifier.weight(1f)) {
                        Icon(if (active) Icons.Default.Stop else Icons.Default.LocationOn, null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (active) "停止" else "監視開始")
                    }
                    IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "編集") }
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "削除") }
                }
            }
        }
    }

    @Composable
    private fun FolderManagerDialog(
        folders: List<String>,
        onDismiss: () -> Unit,
        onCreateFolder: (String) -> Unit,
        onDeleteFolder: (String) -> Unit
    ) {
        var newFolder by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("フォルダを管理") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newFolder,
                            onValueChange = { newFolder = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("新しいフォルダ名") },
                            singleLine = true
                        )
                        Spacer(Modifier.width(6.dp))
                        Button(onClick = {
                            onCreateFolder(newFolder)
                            newFolder = ""
                        }) {
                            Text("追加")
                        }
                    }
                    Text(
                        "フォルダを削除すると、中の目的地はフォルダなしになります。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(folders) { folder ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null)
                                Text(
                                    folder,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 8.dp)
                                )
                                if (folder.isNotBlank()) {
                                    IconButton(onClick = { onDeleteFolder(folder) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "${folder}を削除")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
        )
    }

    @Composable
    private fun DestinationEditorDialog(
        initial: Destination?,
        folders: List<String>,
        initialFolder: String?,
        onDismiss: () -> Unit,
        onSave: (Destination) -> Unit
    ) {
        var name by remember { mutableStateOf(initial?.name ?: "") }
        var latitude by remember { mutableStateOf(initial?.latitude?.toString() ?: "") }
        var longitude by remember { mutableStateOf(initial?.longitude?.toString() ?: "") }
        var radius by remember {
            mutableStateOf(
                initial?.radiusMeters?.toInt()?.toString()
                    ?: DEFAULT_ARRIVAL_RADIUS_METERS.toInt().toString()
            )
        }
        var folder by remember {
            mutableStateOf(initial?.folder ?: initialFolder ?: NO_DESTINATION_FOLDER)
        }
        var folderMenuExpanded by remember { mutableStateOf(false) }
        var alertMethod by remember {
            mutableStateOf(initial?.arrivalAlertMethod ?: ArrivalAlertMethod.VIBRATION)
        }
        var locationRequested by remember { mutableStateOf(false) }
        var showMapPicker by remember { mutableStateOf(false) }
        var waypointMapPickerIndex by remember { mutableStateOf<Int?>(null) }
        var waypoints by remember {
            mutableStateOf<List<WaypointDraft>>(initial?.waypoints?.map {
                WaypointDraft(
                    it.id, it.name, it.latitude.toString(), it.longitude.toString(),
                    it.radiusMeters.toInt().toString(), it.arrivalAlertMethod
                )
            } ?: emptyList())
        }

        val locationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                locationRequested = true
            } else toast("位置情報を許可してください")
        }

        LaunchedEffect(locationRequested) {
            if (!locationRequested) return@LaunchedEffect
            locationRequested = false
            fillCurrentLocation(
                onSuccess = { lat, lon ->
                    latitude = lat.toString()
                    longitude = lon.toString()
                    if (name.isBlank()) name = "現在地"
                }
            )
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (initial == null) "目的地を追加" else "目的地を変更") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(name, { name = it }, label = { Text("目的地名") }, singleLine = true)
                    OutlinedTextField(latitude, { latitude = it }, label = { Text("緯度") }, singleLine = true)
                    OutlinedTextField(longitude, { longitude = it }, label = { Text("経度") }, singleLine = true)
                    OutlinedTextField(radius, { radius = it.filter(Char::isDigit) }, label = { Text("到着判定距離 (m)") }, singleLine = true)
                    Box {
                        OutlinedTextField(
                            value = folder.ifBlank { "フォルダなし" },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("フォルダ") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(
                            expanded = folderMenuExpanded,
                            onDismissRequest = { folderMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("フォルダなし（すべてのフォルダに表示）") },
                                onClick = {
                                    folder = NO_DESTINATION_FOLDER
                                    folderMenuExpanded = false
                                }
                            )
                            folders.forEach { availableFolder ->
                                DropdownMenuItem(
                                    text = { Text(availableFolder) },
                                    onClick = {
                                        folder = availableFolder
                                        folderMenuExpanded = false
                                    }
                                )
                            }
                        }
                        Spacer(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable { folderMenuExpanded = true }
                        )
                    }
                    Text("目的地到着時の連絡方法")
                    ArrivalAlertMethod.entries.forEach { method ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = alertMethod == method,
                                onClick = { alertMethod = method }
                            )
                            Text(method.label())
                        }
                    }
                    HorizontalDivider()
                    Text("途中経由駅（目的地と同じ方法で鳴ります）", fontWeight = FontWeight.Bold)
                    waypoints.forEachIndexed { index, point ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("経由駅 ${index + 1}", modifier = Modifier.weight(1f))
                                    IconButton(onClick = {
                                        waypoints = waypoints.filterIndexed { itemIndex, _ -> itemIndex != index }
                                    }) { Icon(Icons.Default.Delete, "経由駅を削除") }
                                }
                                OutlinedTextField(point.name, { value ->
                                    waypoints = waypoints.toMutableList().also { it[index] = point.copy(name = value) }
                                }, label = { Text("駅名") }, singleLine = true)
                                OutlinedTextField(point.latitude, { value ->
                                    waypoints = waypoints.toMutableList().also { it[index] = point.copy(latitude = value) }
                                }, label = { Text("緯度") }, singleLine = true)
                                OutlinedTextField(point.longitude, { value ->
                                    waypoints = waypoints.toMutableList().also { it[index] = point.copy(longitude = value) }
                                }, label = { Text("経度") }, singleLine = true)
                                OutlinedTextField(point.radius, { value ->
                                    waypoints = waypoints.toMutableList().also { it[index] = point.copy(radius = value.filter(Char::isDigit)) }
                                }, label = { Text("到着判定距離 (m)") }, singleLine = true)
                                OutlinedButton(
                                    onClick = { waypointMapPickerIndex = index },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Map, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("地図から経由駅を選択")
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            waypoints = waypoints + WaypointDraft(
                                System.currentTimeMillis(), "", "", "",
                                DEFAULT_ARRIVAL_RADIUS_METERS.toInt().toString(), alertMethod
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("＋ 経由駅を追加") }
                    OutlinedButton(
                        onClick = { showMapPicker = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Map, null)
                        Spacer(Modifier.width(6.dp))
                        Text("地図から選択")
                    }
                    OutlinedButton(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) locationRequested = true
                            else locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.MyLocation, null)
                        Spacer(Modifier.width(6.dp))
                        Text("現在地を入力")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val lat = latitude.toDoubleOrNull()
                    val lon = longitude.toDoubleOrNull()
                    val rad = radius.toFloatOrNull()
                    if (name.isBlank() || lat == null || lon == null || rad == null || rad < 1f) {
                        toast("名前・緯度・経度・距離（1m以上）を確認してください")
                        return@TextButton
                    }
                    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                        toast("緯度・経度の範囲が正しくありません")
                        return@TextButton
                    }
                    val savedWaypoints = waypoints.mapNotNull { point ->
                        val pointLat = point.latitude.toDoubleOrNull()
                        val pointLon = point.longitude.toDoubleOrNull()
                        val pointRadius = point.radius.toFloatOrNull()
                        if (point.name.isBlank() || pointLat == null || pointLon == null || pointRadius == null ||
                            pointLat !in -90.0..90.0 || pointLon !in -180.0..180.0 || pointRadius < 1f
                        ) null else Waypoint(
                            point.id, point.name.trim(), pointLat, pointLon, pointRadius, alertMethod
                        )
                    }
                    if (savedWaypoints.size != waypoints.size) {
                        toast("経由駅の駅名・位置・到着判定距離を確認してください")
                        return@TextButton
                    }
                    onSave(
                        Destination(
                            id = initial?.id ?: System.currentTimeMillis(),
                            name = name.trim(),
                            latitude = lat,
                            longitude = lon,
                            radiusMeters = rad,
                            folder = folder,
                            arrivalAlertMethod = alertMethod,
                            waypoints = savedWaypoints
                        )
                    )
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
        )

        if (showMapPicker) {
            MapPickerDialog(
                initialLatitude = latitude.toDoubleOrNull(),
                initialLongitude = longitude.toDoubleOrNull(),
                onDismiss = { showMapPicker = false },
                onSelected = { lat, lon, placeName ->
                    latitude = lat.toString()
                    longitude = lon.toString()
                    if (!placeName.isNullOrBlank()) {
                        name = placeName
                    } else if (name.isBlank()) {
                        name = "地図で選択した場所"
                    }
                    showMapPicker = false
                }
            )
        }
        waypointMapPickerIndex?.let { index ->
            val point = waypoints.getOrNull(index)
            if (point != null) {
                MapPickerDialog(
                    initialLatitude = point.latitude.toDoubleOrNull(),
                    initialLongitude = point.longitude.toDoubleOrNull(),
                    isWaypoint = true,
                    onDismiss = { waypointMapPickerIndex = null },
                    onSelected = { lat, lon, placeName ->
                        waypoints = waypoints.toMutableList().also {
                            it[index] = point.copy(
                                name = placeName?.takeIf { value -> value.isNotBlank() }
                                    ?: point.name.ifBlank { "経由駅" },
                                latitude = lat.toString(),
                                longitude = lon.toString(),
                                alertMethod = alertMethod
                            )
                        }
                        waypointMapPickerIndex = null
                    }
                )
            }
        }
    }

    private fun startMonitoring(destination: Destination, onSuccess: () -> Unit) {
        geofenceManager.start(destination) { result ->
            runOnUiThread {
                result.onSuccess {
                    onSuccess()
                    store.resetWaypointProgress(destination.id)
                    LocationMonitoringService.start(this, destination)
                    checkImmediateArrival(destination)
                    toast("${destination.name} の到着監視を開始しました")
                }.onFailure {
                    toast("監視開始に失敗しました: ${it.message ?: "不明なエラー"}")
                }
            }
        }
    }

    private fun checkImmediateArrival(destination: Destination) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val client = LocationServices.getFusedLocationProviderClient(this)
        val token = CancellationTokenSource()
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
            .addOnSuccessListener { location ->
                if (location == null) return@addOnSuccessListener
                val distance = FloatArray(1)
                Location.distanceBetween(
                    location.latitude,
                    location.longitude,
                    destination.latitude,
                    destination.longitude,
                    distance
                )
                if (distance[0] <= destination.radiusMeters) {
                    geofenceManager.stop {
                        ArrivalAlertCoordinator.announce(this, destination)
                    }
                }
            }
    }

    private fun fillCurrentLocation(onSuccess: (Double, Double) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            toast("位置情報を許可してください")
            return
        }
        val client = LocationServices.getFusedLocationProviderClient(this)
        val token = CancellationTokenSource()
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
            .addOnSuccessListener { location ->
                if (location != null) onSuccess(location.latitude, location.longitude)
                else toast("現在地を取得できませんでした")
            }
            .addOnFailureListener { toast("現在地の取得に失敗しました") }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun formatDistance(distanceMeters: Float): String {
        return if (distanceMeters < 1_000f) {
            "残り距離 %.0f m".format(distanceMeters)
        } else {
            "残り距離 %.1f km".format(distanceMeters / 1_000f)
        }
    }
}
