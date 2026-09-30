package com.safenavi.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.widget.ImageView
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.safenavi.app.data.EnforcementDataUpdater
import com.safenavi.app.data.SafetyDatabase
import com.safenavi.app.location.DrivingLocationService
import com.safenavi.app.location.RoadSnapper
import com.safenavi.app.location.TunnelDeadReckoner
import com.safenavi.app.map.NaverDrivingMap
import com.safenavi.app.map.SafeNaviMap
import org.maplibre.android.MapLibre
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.naver.maps.map.MapView
import com.naver.maps.map.NaverMap
import com.naver.maps.map.OnMapReadyCallback
import com.safenavi.app.data.SafetyPoint
import com.safenavi.app.safety.SafetyEngine
import kotlin.math.*

class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener, OnMapReadyCallback {
    private lateinit var map: MapView
    private lateinit var safeMap: org.maplibre.android.maps.MapView
    private lateinit var safeNaviMap: SafeNaviMap
    private lateinit var status: TextView
    private lateinit var dataStatus: TextView
    private lateinit var currentSpeed: TextView
    private lateinit var locationLabel: TextView
    private lateinit var driveHint: TextView

    private lateinit var idleControls: View
    private lateinit var driveControls: View
    private lateinit var speedPanel: View
    private lateinit var floatingButtons: View
    private lateinit var topPanel: View
    private lateinit var compassView: ImageView
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private val roadSnapper = RoadSnapper()
    private val tunnelReckoner = TunnelDeadReckoner()
    private val safetyEngine = SafetyEngine()
    private val drivingMap = NaverDrivingMap()
    private val mapPrefs by lazy { getSharedPreferences("map_settings", Context.MODE_PRIVATE) }
    private var currentRoadName: String? = null
    private var sensorHeading = 0f
    private var gpsHeading = 0f
    private var navigationHeading = 0f
    private var smoothLat: Double? = null
    private var smoothLon: Double? = null

    private lateinit var locationManager: LocationManager
    private lateinit var db: SafetyDatabase
    private lateinit var dataUpdater: EnforcementDataUpdater

    private var lastLocation: Location? = null
    private var firstFix = true
    private var driving = false
    private var followMode = true
    private var safetyTotal = 0
    private var lastGpsCallbackMs = 0L
    private var newestDriveFixNanos = Long.MIN_VALUE

    private val tunnelTicker = object : Runnable {
        override fun run() {
            if (driving && lastGpsCallbackMs > 0L &&
                android.os.SystemClock.elapsedRealtime() - lastGpsCallbackMs >= 1600L
            ) {
                tunnelReckoner.predict()?.let { processDriveLocation(it, null) }
            }
            window.decorView.postDelayed(this, 800L)
        }
    }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startAutomaticDrive()
            else {
                status.text = "위치 권한이 필요합니다"
                dataStatus.text = "안전운행을 시작하려면 위치 권한을 허용해 주세요"
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // MapLibre must be initialized before the XML MapView is inflated.
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        bindViews()
        map.onCreate(savedInstanceState)
        safeMap.onCreate(savedInstanceState)
        safeNaviMap = SafeNaviMap(safeMap)
        safeMap.getMapAsync { safeNaviMap.attach(it) { safeNaviMap.setViewMode(if (mapPrefs.getString("safenavi_view", "2D") == "3D") SafeNaviMap.ViewMode.THREE_D else SafeNaviMap.ViewMode.TWO_D) } }
        applyMapVisibility()
        map.getMapAsync(this)
        val appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        findViewById<TextView>(R.id.versionLabel).text = "v$appVersion"
        applySystemInsets()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        db = SafetyDatabase.getInstance(this)
        dataUpdater = EnforcementDataUpdater(this, db)

        // Driving mode is offline for enforcement data. Network downloads are
        // performed only from DataUpdateActivity when the user runs Update.
        lifecycleScope.launch(Dispatchers.IO) {
            dataUpdater.pruneLegacyData()
            safetyTotal = db.safetyPointDao().count()
            withContext(Dispatchers.Main) {
                if (driving) {
                    dataStatus.text = if (safetyTotal > 0) {
                        "로컬 단속정보 ${safetyTotal}건"
                    } else {
                        "단속정보 업데이트 필요"
                    }
                }
            }
        }

        findViewById<Button>(R.id.startButton).setOnClickListener { startAutomaticDrive() }
        findViewById<Button>(R.id.myLocationButton).setOnClickListener { recenter(false) }
        findViewById<Button>(R.id.dataUpdateButton).setOnClickListener {
            startActivity(Intent(this, DataUpdateActivity::class.java))
        }
        findViewById<Button>(R.id.driveRecenterButton).setOnClickListener { recenter(true) }
        findViewById<Button>(R.id.menuButton).setOnClickListener { anchor -> showDriveMenu(anchor) }
        findViewById<Button>(R.id.zoomInButton).setOnClickListener { if (isFreeMap()) safeNaviMap.zoomIn() else drivingMap.zoomIn() }
        findViewById<Button>(R.id.zoomOutButton).setOnClickListener { if (isFreeMap()) safeNaviMap.zoomOut() else drivingMap.zoomOut() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopSafetyAndExit() }

        requestOptionalPermissions()
        window.decorView.post(tunnelTicker)
        startAutomaticDrive()
    }

    override fun onMapReady(naverMap: NaverMap) {
        drivingMap.attach(naverMap)
        lastLocation?.let { updateNavigationCamera(it, true) }
    }

    private fun showDriveMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("단속정보 업데이트").setOnMenuItemClickListener {
                startActivity(Intent(this@MainActivity, DataUpdateActivity::class.java))
                true
            }
            menu.add("지도 종류 선택").setOnMenuItemClickListener {
                showMapTypeDialog()
                true
            }
            menu.add("SafeNavi 2D / 3D").setOnMenuItemClickListener {
                showSafeNaviViewModeDialog()
                true
            }
            menu.add("음성 안내 설정").setOnMenuItemClickListener {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                true
            }
            menu.add("앱 설정").setOnMenuItemClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
                true
            }
            show()
        }
    }

    private fun showMapTypeDialog() {
        val current = mapPrefs.getString("map_type", "NAVER_NAVI") ?: "NAVER_NAVI"
        val items = arrayOf("네이버 내비맵", "SafeNavi 자체 지도")
        val checked = if (current in setOf("FREE_BASIC", "SAFENAVI")) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("지도 종류")
            .setSingleChoiceItems(items, checked) { dialog, which ->
                val type = if (which == 1) "SAFENAVI" else "NAVER_NAVI"
                mapPrefs.edit().putString("map_type", type).apply()
                applySavedMapStyle()
                applyMapVisibility()
                lastLocation?.let { updateNavigationCamera(it, false) }
                dialog.dismiss()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showSafeNaviViewModeDialog() {
        val current = mapPrefs.getString("safenavi_view", "2D") ?: "2D"
        val items = arrayOf("2D", "3D")
        AlertDialog.Builder(this)
            .setTitle("SafeNavi 지도 표시")
            .setSingleChoiceItems(items, if (current == "3D") 1 else 0) { dialog, which ->
                mapPrefs.edit().putString("safenavi_view", if (which == 1) "3D" else "2D").apply()
                if (!isFreeMap()) mapPrefs.edit().putString("map_type", "SAFENAVI").apply()
                applyMapVisibility()
                safeNaviMap.setViewMode(if (which == 1) SafeNaviMap.ViewMode.THREE_D else SafeNaviMap.ViewMode.TWO_D)
                lastLocation?.let { updateNavigationCamera(it, true) }
                dialog.dismiss()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun isFreeMap(): Boolean = mapPrefs.getString("map_type", "NAVER_NAVI") in setOf("FREE_BASIC", "SAFENAVI")

    private fun applyMapVisibility() {
        if (!::safeMap.isInitialized) return
        map.visibility = if (isFreeMap()) View.GONE else View.VISIBLE
        safeMap.visibility = if (isFreeMap()) View.VISIBLE else View.GONE
    }

    private fun applySavedMapStyle() = Unit

    private fun selectedMapLabel(): String =
        if (isFreeMap()) "SafeNavi ${mapPrefs.getString("safenavi_view", "2D")}" else "네이버 내비맵"

    private fun requestOptionalPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun startAutomaticDrive() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        startMapLocationUpdates()
        ContextCompat.startForegroundService(
            this,
            Intent(this, DrivingLocationService::class.java)
        )
        enterDriveMode()
    }

    private fun stopSafetyAndExit() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopService(Intent(this, DrivingLocationService::class.java))
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
        finishAndRemoveTask()
    }

    private fun bindViews() {
        map = findViewById(R.id.map)
        safeMap = findViewById(R.id.safeMap)
        status = findViewById(R.id.status)
        dataStatus = findViewById(R.id.dataStatus)
        currentSpeed = findViewById(R.id.currentSpeed)
        locationLabel = findViewById(R.id.locationLabel)
        driveHint = findViewById(R.id.driveHint)
        topPanel = findViewById(R.id.topPanel)
        idleControls = findViewById(R.id.idleControls)
        driveControls = findViewById(R.id.driveControls)
        speedPanel = findViewById(R.id.speedPanel)
        floatingButtons = findViewById(R.id.floatingButtons)
        compassView = findViewById(R.id.compassView)
    }

    private fun applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topPanel.setPadding(16.dp(), bars.top + 8.dp(), 16.dp(), 10.dp())
            idleControls.setPadding(14.dp(), 10.dp(), 14.dp(), bars.bottom + 10.dp())
            driveControls.setPadding(12.dp(), 10.dp(), 12.dp(), bars.bottom + 10.dp())
            insets
        }
    }

    private fun enterDriveMode() {
        driving = true
        // 안전운행 중에는 화면이 자동으로 꺼지지 않도록 유지한다.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        followMode = true
        idleControls.visibility = View.GONE
        driveControls.visibility = View.VISIBLE
        speedPanel.visibility = View.VISIBLE
        floatingButtons.visibility = View.VISIBLE

        status.text = "안전운행 중"
        dataStatus.text = if (safetyTotal > 0) {
            "전방 안전정보 감시 · 데이터 ${safetyTotal}건"
        } else {
            "GPS 주행모드 · 단속정보 확인 중"
        }
        lastLocation?.let { updateNavigationCamera(it, true) }
    }

    private fun startMapLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        try {
            locationManager.removeUpdates(this)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                800L,
                0.5f,
                this
            )
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                onLocationChanged(it)
            }
        } catch (_: Exception) {
            status.text = "GPS를 사용할 수 없습니다"
        }
    }

    override fun onLocationChanged(location: Location) {
        lastGpsCallbackMs = android.os.SystemClock.elapsedRealtime()
        if (location.hasBearing() && location.speed > 1.5f) {
            gpsHeading = location.bearing
            updateCompass(gpsHeading)
        }

        val rawGps = Location(location)
        val raw = tunnelReckoner.acceptGps(rawGps)
        processDriveLocation(raw, rawGps.accuracy)
    }

    private fun processDriveLocation(raw: Location, gpsAccuracy: Float?) {
        val fixNanos = raw.elapsedRealtimeNanos
        if (fixNanos > 0L) {
            if (fixNanos < newestDriveFixNanos) return
            newestDriveFixNanos = fixNanos
        }

        lifecycleScope.launch {
            val snapped = roadSnapper.snap(raw)

            // Road snapping may involve network I/O. If a newer GPS/tunnel fix
            // arrived while this coroutine was waiting, never let this older
            // result move the vehicle/camera backwards and create visible jitter.
            if (fixNanos > 0L && fixNanos < newestDriveFixNanos) return@launch

            val displayLocation = Location(raw).apply {
                if (snapped != null) {
                    latitude = snapped.latitude
                    longitude = snapped.longitude
                }
            }

            val smooth = Location(displayLocation).apply {
                val pLat = smoothLat
                val pLon = smoothLon
                if (pLat != null && pLon != null) {
                    val alpha = when {
                        raw.speed >= 20f -> 0.55
                        raw.speed >= 10f -> 0.42
                        raw.speed >= 3f -> 0.32
                        else -> 0.22
                    }
                    latitude = pLat + (latitude - pLat) * alpha
                    longitude = pLon + (longitude - pLon) * alpha
                }
                smoothLat = latitude
                smoothLon = longitude
            }
            lastLocation = smooth
            updateCarMarker(smooth)

            val speedKmh = if (raw.hasSpeed()) (raw.speed * 3.6f).roundToInt() else 0
            currentSpeed.text = speedKmh.toString()
            status.text = "안전운행 중"

            val roadText = snapped?.roadName?.takeIf { it.isNotBlank() }
            currentRoadName = roadText
            dataStatus.text = buildString {
                append(selectedMapLabel())
                append(" · ")
                if (gpsAccuracy != null && gpsAccuracy <= 35f) {
                    append("GPS ")
                    append(gpsAccuracy.roundToInt())
                    append("m · ")
                } else {
                    append("터널 추측주행 · ")
                }
                append(speedKmh)
                append("km/h")
                if (snapped != null) append(" · 도로보정")
            }
            locationLabel.text = roadText ?: "현재 도로 추적 중"

            if (isFreeMap() && snapped != null && snapped.roadGeometry.size >= 2) {
                safeNaviMap.updateRoadGeometry(snapped.roadGeometry)
            }
            updateNavigationCamera(smooth, firstFix, snapped?.roadBearing)
            firstFix = false
            loadNearbySafetyPoints(smooth, roadText, snapped?.roadBearing, snapped?.roadGeometry ?: emptyList())
        }
    }

    private fun updateCarMarker(location: Location) = Unit

    private fun updateNavigationCamera(
        location: Location,
        forceZoom: Boolean,
        snappedRoadBearing: Double? = null
    ) {
        if (!followMode) return

        // Heading-up navigation: keep the vehicle pointing toward the top of the
        // screen and rotate the MAP beneath it. GPS bearing is usable even at
        // walking/slow-driving speed; when it is unavailable, fall back to the
        // device heading so the map does not snap back to north-up.
        val targetHeading = when {
            // While driving, prefer the direction of the snapped road geometry.
            // This keeps the map aligned with the road instead of reacting to
            // short GPS-bearing swings toward a parallel/opposite carriageway.
            snappedRoadBearing != null && location.speed > 0.35f ->
                snappedRoadBearing.toFloat()
            location.hasBearing() && location.speed > 0.35f -> location.bearing
            gpsHeading != 0f -> gpsHeading
            else -> sensorHeading
        }
        if (navigationHeading == 0f) {
            navigationHeading = targetHeading
        } else {
            var delta = (targetHeading - navigationHeading + 540f) % 360f - 180f
            // Hold the map steady on straight roads and follow only meaningful turns.
            if (kotlin.math.abs(delta) >= 4f) {
                val factor = if (kotlin.math.abs(delta) >= 25f) 0.32f else 0.16f
                navigationHeading = (navigationHeading + delta * factor + 360f) % 360f
            }
        }
        val heading = navigationHeading

        if (isFreeMap()) safeNaviMap.updateVehicle(location, heading, forceZoom)
        else drivingMap.updateVehicle(location, heading, forceZoom)
    }

    private fun recenter(navigationMode: Boolean) {
        lastLocation?.let {
            followMode = navigationMode || driving
            updateNavigationCamera(it, false)
        }
    }

    private fun loadNearbySafetyPoints(
        location: Location,
        roadName: String?,
        snappedRoadBearing: Double?,
        snappedRoadGeometry: List<Pair<Double, Double>>
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val r = 5000.0
            val latD = r / 111320.0
            val lonD = r / (111320.0 * cos(Math.toRadians(location.latitude)))
            val points = db.safetyPointDao().findNearby(
                location.latitude - latD,
                location.latitude + latD,
                location.longitude - lonD,
                location.longitude + lonD
            )
            // Prefer the snapped road direction when available. GPS/device heading
            // can briefly point toward an adjacent or opposite carriageway, while
            // the snapped road bearing represents the road geometry being followed.
            val heading = snappedRoadBearing ?: when {
                location.hasBearing() && location.speed > 0.35f -> location.bearing.toDouble()
                gpsHeading != 0f -> gpsHeading.toDouble()
                else -> navigationHeading.toDouble()
            }
            val pathPoints = safetyEngine.findAhead(
                location.latitude, location.longitude, heading, points, roadName, snappedRoadGeometry
            ).map { it.point }
            withContext(Dispatchers.Main) {
                showSafetyMarkers(pathPoints)
                driveHint.text = when {
                    pathPoints.isNotEmpty() -> "진행경로 안전정보 ${pathPoints.size}건"
                    safetyTotal == 0 -> "안전정보 데이터 업데이트 필요"
                    else -> "5km 이내 안전정보 없음"
                }
            }
        }
    }

    private fun showSafetyMarkers(points: List<SafetyPoint>) {
        if (isFreeMap()) safeNaviMap.showSafetyPoints(points)
        else drivingMap.showSafetyPoints(points)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        if (::safeMap.isInitialized) safeMap.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        // Returning from the manual update screen must immediately refresh the
        // installed camera count used by driving-mode status and local lookup UI.
        if (::db.isInitialized) {
            lifecycleScope.launch(Dispatchers.IO) {
                val refreshedTotal = db.safetyPointDao().count()
                safetyTotal = refreshedTotal
                withContext(Dispatchers.Main) {
                    if (driving) {
                        driveHint.text = if (refreshedTotal > 0) {
                            "로컬 단속정보 ${refreshedTotal}건"
                        } else {
                            "안전정보 데이터 업데이트 필요"
                        }
                    }
                }
            }
        }

        sendBroadcast(
            Intent(DrivingLocationService.ACTION_APP_FOREGROUND).setPackage(packageName)
        )
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        sendBroadcast(
            Intent(DrivingLocationService.ACTION_APP_BACKGROUND).setPackage(packageName)
        )
        if (::safeMap.isInitialized) safeMap.onPause()
        map.onPause()
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return
        val matrix = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        SensorManager.getOrientation(matrix, orientation)
        sensorHeading = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()

        tunnelReckoner.adjustHeading(sensorHeading)

        val moving = lastLocation?.speed?.let { it > 1.5f } == true
        if (!moving) updateCompass(sensorHeading)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun updateCompass(heading: Float) {
        compassView.animate().rotation(heading).setDuration(180L).start()
    }

    private fun Int.dp(): Int =
        (this * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        window.decorView.removeCallbacks(tunnelTicker)
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
        if (::safeMap.isInitialized) safeMap.onDestroy()
        map.onDestroy()
        super.onDestroy()
    }
}
