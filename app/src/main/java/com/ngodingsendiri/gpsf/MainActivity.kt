package com.ngodingsendiri.gpsf

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private fun Context.startMockService(intent: Intent) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            @Suppress("DEPRECATION")
            startService(intent)
        }
    } catch (e: Exception) {
        Toast.makeText(
            this,
            "Gagal memulai service: ${e.message ?: e.javaClass.simpleName}",
            Toast.LENGTH_LONG
        ).show()
    }
}

private fun Context.openDeveloperSettings() {
    val candidates = listOf(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
    )
    for (intent in candidates) {
        try {
            startActivity(intent)
            return
        } catch (_: Exception) {
            // try next
        }
    }
    Toast.makeText(this, "Tidak dapat membuka Developer Options", Toast.LENGTH_SHORT).show()
}

private fun Context.hasNetwork(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

class MainActivity : ComponentActivity() {
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Must run before any MapView is created.
        OsmMapConfig.init(this)

        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permLauncher.launch(perms.toTypedArray())

        setContent {
            val dark = isSystemInDarkTheme()
            val colorScheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark ->
                    dynamicDarkColorScheme(this)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                GpsfApp()
            }
        }
    }
}

@Composable
fun MockModeChip(
    label: String,
    mode: String,
    selectedMode: String,
    onSelect: (String) -> Unit
) {
    FilterChip(
        selected = mode == selectedMode,
        onClick = { onSelect(mode) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
    )
}

@Composable
fun PulsingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
    )
}

@Composable
fun GpsfApp() {
    val ctx = LocalContext.current
    val sharedPrefs = remember(ctx) {
        ctx.getSharedPreferences(GpsfConstants.PREFS_NAME, Context.MODE_PRIVATE)
    }

    // runCatching: prefs yang korup (tipe salah) tidak boleh membuat app crash.
    var mockMode by remember {
        mutableStateOf(
            runCatching {
                sharedPrefs.getString(GpsfConstants.PREF_MOCK_MODE, GpsfConstants.MOCK_MODE_BOTH)
            }.getOrDefault(GpsfConstants.MOCK_MODE_BOTH) ?: GpsfConstants.MOCK_MODE_BOTH
        )
    }

    var lat by remember {
        mutableDoubleStateOf(
            runCatching {
                sharedPrefs.getFloat(GpsfConstants.PREF_LAT, GpsfConstants.DEFAULT_LAT.toFloat())
            }.getOrDefault(GpsfConstants.DEFAULT_LAT.toFloat()).toDouble()
        )
    }
    var lng by remember {
        mutableDoubleStateOf(
            runCatching {
                sharedPrefs.getFloat(GpsfConstants.PREF_LNG, GpsfConstants.DEFAULT_LNG.toFloat())
            }.getOrDefault(GpsfConstants.DEFAULT_LNG.toFloat()).toDouble()
        )
    }
    var centerMapTrigger by remember { mutableIntStateOf(0) }

    val isRunning by MockLocationService.isRunning.collectAsStateWithLifecycle()
    val runningLat by MockLocationService.currentLat.collectAsStateWithLifecycle()
    val runningLng by MockLocationService.currentLng.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Saat mock berjalan, pin & koordinat mengikuti posisi simulasi secara live
    // (random walk di dalam radius jitter). Saat berhenti, kembali ke pin target.
    val displayLat = if (isRunning) runningLat else lat
    val displayLng = if (isRunning) runningLng else lng

    LaunchedEffect(Unit) {
        MockLocationService.errorEvent.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Long)
        }
    }

    fun persistAndMaybeRetarget(newLat: Double, newLng: Double) {
        lat = newLat
        lng = newLng
        sharedPrefs.edit()
            .putFloat(GpsfConstants.PREF_LAT, newLat.toFloat())
            .putFloat(GpsfConstants.PREF_LNG, newLng.toFloat())
            .apply()
        if (isRunning) {
            ctx.startMockService(
                Intent(ctx, MockLocationService::class.java).apply {
                    action = MockLocationService.ACTION_START
                    putExtra(MockLocationService.EXTRA_LAT, newLat)
                    putExtra(MockLocationService.EXTRA_LNG, newLng)
                }
            )
        }
    }

    fun selectMockMode(mode: String) {
        mockMode = mode
        sharedPrefs.edit().putString(GpsfConstants.PREF_MOCK_MODE, mode).apply()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.navigationBarsPadding()
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            OsmMap(
                modifier = Modifier.fillMaxSize(),
                lat = displayLat,
                lng = displayLng,
                centerMapTrigger = centerMapTrigger,
                onSelect = { newLat, newLng -> persistAndMaybeRetarget(newLat, newLng) }
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledIconButton(
                    onClick = { ctx.openDeveloperSettings() },
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                    ),
                    shape = CircleShape
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Developer Options",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                FilledIconButton(
                    onClick = { centerMapTrigger++ },
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                    ),
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = "Pusatkan ke Pin",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isRunning) {
                                PulsingDot()
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = if (isRunning) "Mocking Aktif" else "Pilih Area",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isRunning) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = String.format(Locale.US, "%.5f, %.5f", displayLat, displayLng),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Radius ${GpsfConstants.JITTER_RADIUS_METERS.toInt()}m (Acak)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        if (!isRunning) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MockModeChip(
                                    label = "GPS + Jaringan",
                                    mode = GpsfConstants.MOCK_MODE_BOTH,
                                    selectedMode = mockMode,
                                    onSelect = ::selectMockMode
                                )
                                MockModeChip(
                                    label = "GPS saja",
                                    mode = GpsfConstants.MOCK_MODE_GPS,
                                    selectedMode = mockMode,
                                    onSelect = ::selectMockMode
                                )
                                MockModeChip(
                                    label = "Jaringan saja",
                                    mode = GpsfConstants.MOCK_MODE_NETWORK,
                                    selectedMode = mockMode,
                                    onSelect = ::selectMockMode
                                )
                            }
                        }
                    }

                    FloatingActionButton(
                        onClick = {
                            if (isRunning) {
                                ctx.startService(
                                    Intent(ctx, MockLocationService::class.java).apply {
                                        action = MockLocationService.ACTION_STOP
                                    }
                                )
                            } else {
                                sharedPrefs.edit()
                                    .putFloat(GpsfConstants.PREF_LAT, lat.toFloat())
                                    .putFloat(GpsfConstants.PREF_LNG, lng.toFloat())
                                    .apply()
                                ctx.startMockService(
                                    Intent(ctx, MockLocationService::class.java).apply {
                                        action = MockLocationService.ACTION_START
                                        putExtra(MockLocationService.EXTRA_LAT, lat)
                                        putExtra(MockLocationService.EXTRA_LNG, lng)
                                    }
                                )
                            }
                        },
                        containerColor = if (isRunning) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        contentColor = if (isRunning) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                        elevation = FloatingActionButtonDefaults.elevation(
                            defaultElevation = 0.dp,
                            pressedElevation = 0.dp
                        )
                    ) {
                        AnimatedContent(targetState = isRunning, label = "icon") { running ->
                            Icon(
                                imageVector = if (running) Icons.Default.Close else Icons.Default.PlayArrow,
                                contentDescription = if (running) "Stop mock" else "Start mock"
                            )
                        }
                    }
                }
            }
        }
    }
}

fun getCirclePoints(centerLat: Double, centerLng: Double, radiusMeters: Double): List<GeoPoint> {
    val points = ArrayList<GeoPoint>(73)
    val earthRadius = 6378137.0
    val cosLat = cos(centerLat * PI / 180.0)
    val safeCosLat = if (abs(cosLat) < 1e-6) 1e-6 else cosLat
    for (i in 0..360 step 5) {
        val angle = i * PI / 180.0
        val dx = radiusMeters * cos(angle)
        val dy = radiusMeters * sin(angle)
        val lat = centerLat + (180.0 / PI) * (dy / earthRadius)
        val lng = centerLng + (180.0 / PI) * (dx / earthRadius) / safeCosLat
        points.add(GeoPoint(lat, lng))
    }
    return points
}

@Composable
fun OsmMap(
    modifier: Modifier = Modifier,
    lat: Double,
    lng: Double,
    centerMapTrigger: Int,
    onSelect: (Double, Double) -> Unit
) {
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var marker by remember { mutableStateOf<Marker?>(null) }
    var circle by remember { mutableStateOf<Polygon?>(null) }
    val isDark = isSystemInDarkTheme()
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentLat by rememberUpdatedState(lat)
    val currentLng by rememberUpdatedState(lng)

    val fillColor = remember(isDark) {
        AndroidColor.parseColor(if (isDark) "#4490CAF9" else "#441976D2")
    }
    val outlineColor = remember(isDark) {
        AndroidColor.parseColor(if (isDark) "#8890CAF9" else "#881976D2")
    }

    val circlePoints = remember(lat, lng) {
        getCirclePoints(lat, lng, GpsfConstants.JITTER_RADIUS_METERS)
    }

    LaunchedEffect(centerMapTrigger) {
        if (centerMapTrigger > 0) {
            mapView?.controller?.animateTo(GeoPoint(lat, lng))
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewCtx ->
            // Ensure config is applied even if Activity recreated oddly.
            OsmMapConfig.init(viewCtx)

            MapView(viewCtx).apply {
                // OSM HTTPS dulu (paling andal, UA sudah di-set), lalu fallback ke Carto.
                val tileSources = arrayOf(
                    OsmMapConfig.OSM_HTTPS,
                    OsmMapConfig.CARTO_VOYAGER
                )
                var tileSourceIndex = 0
                setTileSource(tileSources[tileSourceIndex])
                setMultiTouchControls(true)
                setTilesScaledToDpi(true)
                isHorizontalMapRepetitionEnabled = true
                isVerticalMapRepetitionEnabled = false
                minZoomLevel = 3.0
                maxZoomLevel = 20.0
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

                // Auto-fallback: kalau tile gagal diunduh dari source utama
                // (CDN diblokir / rate-limit / jaringan bermasalah), pindah ke
                // source cadangan satu kali supaya peta tetap muncul.
                tileProvider.getTileRequestCompleteHandlers().add(
                    object : Handler(Looper.getMainLooper()) {
                        override fun handleMessage(msg: Message) {
                            if (msg.what == MapTileProviderBase.MAPTILE_FAIL_ID &&
                                tileSourceIndex < tileSources.size - 1
                            ) {
                                tileSourceIndex++
                                setTileSource(tileSources[tileSourceIndex])
                                invalidate()
                            }
                        }
                    }
                )

                if (!viewCtx.hasNetwork()) {
                    Toast.makeText(
                        viewCtx,
                        "Tidak ada koneksi internet — tile peta tidak bisa dimuat",
                        Toast.LENGTH_LONG
                    ).show()
                }

                val pt = GeoPoint(currentLat, currentLng)
                controller.setZoom(17.0)
                controller.setCenter(pt)

                val c = Polygon().apply {
                    points = circlePoints
                    fillPaint.color = fillColor
                    outlinePaint.color = outlineColor
                    outlinePaint.strokeWidth = 3f
                }
                overlays.add(c)
                circle = c

                val m = Marker(this).apply {
                    position = pt
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = ContextCompat.getDrawable(viewCtx, R.drawable.ic_pin)
                    infoWindow = null
                    setOnMarkerClickListener { _, _ -> true }
                }
                overlays.add(m)
                marker = m

                overlays.add(
                    MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            p?.let {
                                currentOnSelect(it.latitude, it.longitude)
                                controller.animateTo(it)
                            }
                            return true
                        }

                        override fun longPressHelper(p: GeoPoint?) = false
                    })
                )

                // Critical: activity is usually already RESUMED when Compose builds MapView,
                // so Lifecycle ON_RESUME will not fire again. Without this, tiles stay blank.
                onResume()
                mapView = this
            }
        },
        update = { view ->
            val pt = GeoPoint(lat, lng)
            marker?.position = pt
            circle?.points = circlePoints
            circle?.fillPaint?.color = fillColor
            circle?.outlinePaint?.color = outlineColor
            view.invalidate()
        }
    )

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        val map = mapView
        if (map == null) {
            return@DisposableEffect onDispose { }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    map.onResume()
                    map.invalidate()
                }
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        // If already resumed when effect attaches, start the tile engine now.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            map.onResume()
            map.invalidate()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                map.onPause()
                map.onDetach()
            } catch (_: Exception) {
                // Best-effort teardown.
            }
            mapView = null
            marker = null
            circle = null
        }
    }
}
