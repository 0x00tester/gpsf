package com.ngodingsendiri.gpsf

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.random.Random

class MockLocationService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val EXTRA_LAT = "LAT"
        const val EXTRA_LNG = "LNG"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "fake_gps"
        private const val GPS_LOCK_TICKS = 8

        /** Jumlah kegagalan berturut-turut sebelum mock dihentikan secara graceful. */
        private const val MAX_CONSECUTIVE_FAILURES = 5

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        val errorEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)

        private val _currentLat = MutableStateFlow(GpsfConstants.DEFAULT_LAT)
        val currentLat = _currentLat.asStateFlow()

        private val _currentLng = MutableStateFlow(GpsfConstants.DEFAULT_LNG)
        val currentLng = _currentLng.asStateFlow()
    }

    private val allProviders = arrayOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER
    )

    /** Provider yang aktif sesuai mode (both / gps / network — indirect mocking). */
    private var activeProviders: Array<String> = allProviders

    /** Hitungan tick sejak mock start — dipakai simulasi "cold start" GPS. */
    private var mockTicks = 0

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var mockJob: Job? = null
    // ConcurrentHashMap: updateLocation berjalan di coroutine (Default) sementara
    // stopMocking membersihkan dari main thread — HashMap biasa rawan CME.
    private val locationCache = ConcurrentHashMap<String, Location>()

    // State simulasi pergerakan realistis (random walk di sekitar pin).
    private var walkLat = GpsfConstants.DEFAULT_LAT
    private var walkLng = GpsfConstants.DEFAULT_LNG
    private var walkHeading = Random.nextDouble() * 2.0 * PI
    private var lastFixLat = 0.0
    private var lastFixLng = 0.0
    private var lastFixTimeNanos = 0L

    @Volatile
    private var targetLat = 0.0

    @Volatile
    private var targetLng = 0.0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, GpsfConstants.DEFAULT_LAT)
                val lng = intent.getDoubleExtra(EXTRA_LNG, GpsfConstants.DEFAULT_LNG)
                if (promoteToForeground(lat, lng)) {
                    startMocking(lat, lng)
                }
            }
            ACTION_STOP -> {
                stopMocking()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                if (!_isRunning.value) {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopMocking()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * @return false bila FGS tidak bisa dimulai (mis. izin lokasi ditolak — pada
     * API 34+ startForeground dengan tipe location melempar SecurityException).
     */
    private fun promoteToForeground(lat: Double, lng: Double): Boolean {
        val notification = buildNotification(lat, lng)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: SecurityException) {
            errorEvent.tryEmit("Tidak bisa menjalankan service: izin lokasi belum diberikan")
            stopSelf()
            false
        }
    }

    private fun startMocking(lat: Double, lng: Double) {
        targetLat = lat
        targetLng = lng
        _currentLat.value = lat
        _currentLng.value = lng
        walkLat = lat
        walkLng = lng
        lastFixTimeNanos = 0L

        if (_isRunning.value) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(lat, lng))
            return
        }

        activeProviders = mockProvidersFromPrefs()
        mockTicks = 0

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        var hasSecurityException = false
        var providersReady = 0

        for (provider in activeProviders) {
            try {
                lm.removeTestProvider(provider)
            } catch (_: Exception) {
                // Provider may not exist yet.
            }

            try {
                // powerRequirement=1 (LOW), accuracy=1 (FINE)
                @Suppress("WrongConstant")
                lm.addTestProvider(
                    provider,
                    false,
                    false,
                    false,
                    false,
                    true,
                    true,
                    true,
                    1,
                    1
                )
                lm.setTestProviderEnabled(provider, true)
                providersReady++
            } catch (_: SecurityException) {
                hasSecurityException = true
            } catch (_: IllegalArgumentException) {
                try {
                    lm.setTestProviderEnabled(provider, true)
                    providersReady++
                } catch (_: Exception) {
                    // ignore
                }
            } catch (_: Exception) {
                // Ignore non-security setup failures per provider.
            }
        }

        if (hasSecurityException || providersReady == 0) {
            val message = if (hasSecurityException) {
                "Mock lokasi belum diizinkan. Pilih gpsf di Developer Settings, atau grant via ADB:\n" +
                    "adb shell appops set com.ngodingsendiri.gpsf android:mock_location allow"
            } else {
                "Gagal mengaktifkan mock location provider"
            }
            errorEvent.tryEmit(message)
            _isRunning.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        _isRunning.value = true
        mockJob?.cancel()
        mockJob = scope.launch {
            while (isActive && _isRunning.value) {
                updateLocation(lm, targetLat, targetLng, GpsfConstants.UPDATE_INTERVAL_MS)
                // Interval sedikit diacak (≈700–1300 ms) supaya polanya tidak "seperti mesin".
                val jitterMs = (GpsfConstants.UPDATE_INTERVAL_MS * 0.3).toLong()
                val delayMs =
                    (GpsfConstants.UPDATE_INTERVAL_MS - jitterMs) + Random.nextLong(jitterMs * 2 + 1)
                delay(delayMs)
            }
        }
    }

    private fun stopMocking() {
        _isRunning.value = false
        mockJob?.cancel()
        mockJob = null

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        // Bersihkan SEMUA provider (tidak peduli mode aktif) supaya tidak ada sisa mock.
        for (provider in allProviders) {
            try {
                lm.setTestProviderEnabled(provider, false)
            } catch (_: Exception) {
                // Best-effort cleanup.
            }
            try {
                lm.removeTestProvider(provider)
            } catch (_: Exception) {
                // Best-effort cleanup.
            }
        }
        locationCache.clear()
    }

    private fun mockProvidersFromPrefs(): Array<String> {
        // runCatching: prefs yang korup (tipe salah) tidak boleh membuat app crash.
        val mode = runCatching {
            getSharedPreferences(GpsfConstants.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(GpsfConstants.PREF_MOCK_MODE, GpsfConstants.MOCK_MODE_BOTH)
        }.getOrDefault(GpsfConstants.MOCK_MODE_BOTH) ?: GpsfConstants.MOCK_MODE_BOTH
        return when (mode) {
            GpsfConstants.MOCK_MODE_GPS -> arrayOf(LocationManager.GPS_PROVIDER)
            GpsfConstants.MOCK_MODE_NETWORK -> arrayOf(LocationManager.NETWORK_PROVIDER)
            else -> arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun updateLocation(lm: LocationManager, baseLat: Double, baseLng: Double, dtMs: Long) {
        // 1) Random walk alami: heading berubah pelan, kecepatan seperti jalan kaki,
        //    dan posisi dibatasi agar tidak keluar dari radius jitter di sekitar pin.
        walkHeading += (Random.nextDouble() - 0.5) * 0.30 // drift heading ±~8.6°/tick
        val stepMeters = (0.3 + Random.nextDouble() * 1.2) * (dtMs / 1000.0)
        val step = GpsMath.nextWalkStep(
            lat = walkLat,
            lng = walkLng,
            headingRad = walkHeading,
            baseLat = baseLat,
            baseLng = baseLng,
            stepMeters = stepMeters,
            radiusMeters = GpsfConstants.JITTER_RADIUS_METERS
        )
        walkLat = step.lat
        walkLng = step.lng
        walkHeading = step.headingRad
        val safeCosLat = GpsMath.safeCosLat(walkLat)

        // 2) Speed & bearing dihitung dari perpindahan nyata antar fix (seperti GPS asli).
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        var computedSpeed = 0f
        var computedBearing = 0f
        if (lastFixTimeNanos > 0L) {
            val dtSeconds = ((nowNanos - lastFixTimeNanos) / 1e9).coerceAtLeast(0.1)
            val movedMeters = GpsMath.haversineMeters(lastFixLat, lastFixLng, walkLat, walkLng)
            computedSpeed = (movedMeters / dtSeconds).toFloat().coerceIn(0f, 8f)
            if (movedMeters > 0.5) {
                computedBearing =
                    GpsMath.bearingDegrees(lastFixLat, lastFixLng, walkLat, walkLng).toFloat()
            }
        }
        lastFixLat = walkLat
        lastFixLng = walkLng
        lastFixTimeNanos = nowNanos

        // 3) Provider di-feed sesuai mode aktif. Saat dua provider aktif, koordinatnya
        //    diberi selisih kecil (±3 m) dan timestamp Network sedikit lebih tua —
        //    di perangkat asli kedua provider hampir tidak pernah identik.
        mockTicks++
        val lockProgress =
            (mockTicks.coerceAtMost(GPS_LOCK_TICKS).toDouble() / GPS_LOCK_TICKS).toFloat()
        var consecutiveFailures = 0
        for (provider in activeProviders) {
            try {
                val offsetMeters = (Random.nextDouble() - 0.5) * 6.0
                val ageMillis =
                    if (provider == LocationManager.NETWORK_PROVIDER && activeProviders.size > 1) {
                        Random.nextLong(150L, 500L)
                    } else {
                        0L
                    }
                val loc = locationCache.getOrPut(provider) { Location(provider) }.apply {
                    latitude =
                        (walkLat + offsetMeters / 111_320.0).coerceIn(-90.0, 90.0)
                    longitude =
                        GpsMath.normalizeLongitude(walkLng + offsetMeters / (111_320.0 * safeCosLat))
                    altitude = 40.0 + Random.nextDouble() * 12.0
                    // "Cold start" GPS: akurasi memburuk dulu, lalu membaik bertahap
                    // seperti GPS asli yang sedang mengunci sinyal.
                    accuracy = (8f + Random.nextFloat() * 12f) + (1f - lockProgress) * 70f
                    time = System.currentTimeMillis() - ageMillis
                    elapsedRealtimeNanos = nowNanos - ageMillis * 1_000_000L
                    speed = computedSpeed
                    bearing = computedBearing
                    completeForTestProvider()
                    markAsMock()
                }
                lm.setTestProviderLocation(provider, loc)
                consecutiveFailures = 0
            } catch (_: Exception) {
                // Provider dicabut / izin mock hilang di tengah jalan.
                consecutiveFailures++
            }
        }

        // 4) Kalau provider gagal terus-menerus (mis. mock app tidak lagi dipilih,
        //    izin dicabut), berhenti secara graceful alih-alih diam-diam mati.
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            errorEvent.tryEmit("Mock berhenti: provider lokasi tidak lagi tersedia")
            _isRunning.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        _currentLat.value = walkLat
        _currentLng.value = walkLng
    }

    /** Some OEMs reject incomplete Location objects from test providers. */
    private fun Location.completeForTestProvider() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            bearingAccuracyDegrees = 0.1f
            verticalAccuracyMeters = 10f
            speedAccuracyMetersPerSecond = 0.1f
        }
    }

    private fun Location.markAsMock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isMock = true
        } else {
            @Suppress("DEPRECATION")
            extras = (extras ?: Bundle()).apply {
                putBoolean("mockLocation", true)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "gpsf Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifikasi saat mock lokasi aktif"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(lat: Double, lng: Double): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Aksi Stop di notifikasi: satu-satunya cara menghentikan mock saat
        // activity sudah ditutup (swipe recent apps) tanpa membuka app lagi.
        val stopIntent = Intent(this, MockLocationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val radius = GpsfConstants.JITTER_RADIUS_METERS.toInt()
        val coordText = String.format(
            Locale.US,
            "Lokasi: %.5f, %.5f (Jitter %dm)",
            lat,
            lng,
            radius
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("gpsf Aktif")
            .setContentText(coordText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop",
                    stopPi
                ).build()
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
