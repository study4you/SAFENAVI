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
import android.widget.Button
import android.widget.TextView
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.*

class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {
    private lateinit var map: MapView
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
    private var sensorHeading = 0f
    private var gpsHeading = 0f

    private lateinit var locationManager: LocationManager
    private lateinit var db: SafetyDatabase
    private lateinit var dataUpdater: EnforcementDataUpdater

    private var carMarker: Marker? = null
    private var lastLocation: Location? = null
    private var firstFix = true
    private var driving = false
    private var followMode = true
    private var safetyTotal = 0

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

        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        bindViews()
        applySystemInsets()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        db = SafetyDatabase.getInstance(this)
        dataUpdater = EnforcementDataUpdater(this, db)

        map.setTileSource(roadTileSource())
        map.setMultiTouchControls(true)
        map.isTilesScaledToDpi = true
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(37.5665, 126.9780))

        lifecycleScope.launch(Dispatchers.IO) {
            dataUpdater.pruneLegacyData()
            val result = dataUpdater.updateIfNeeded()
            safetyTotal = result.totalCount
            withContext(Dispatchers.Main) {
                if (driving && result.checked) {
                    dataStatus.text = result.message + " · ${result.totalCount}건"
                }
            }
        }

        findViewById<Button>(R.id.startButton).setOnClickListener { startAutomaticDrive() }
        findViewById<Button>(R.id.myLocationButton).setOnClickListener { recenter(false) }
        findViewById<Button>(R.id.dataUpdateButton).setOnClickListener {
            startActivity(Intent(this, DataUpdateActivity::class.java))
        }
        findViewById<Button>(R.id.driveRecenterButton).setOnClickListener { recenter(true) }
        findViewById<Button>(R.id.zoomInButton).setOnClickListener { map.controller.zoomIn() }
        findViewById<Button>(R.id.zoomOutButton).setOnClickListener { map.controller.zoomOut() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopSafetyAndExit() }

        requestOptionalPermissions()
        startAutomaticDrive()
    }

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
        stopService(Intent(this, DrivingLocationService::class.java))
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
        finishAndRemoveTask()
    }

    private fun bindViews() {
        map = findViewById(R.id.map)
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
        if (location.hasBearing() && location.speed > 1.5f) {
            gpsHeading = location.bearing
            updateCompass(gpsHeading)
        }

        val raw = Location(location)
        lifecycleScope.launch {
            val snapped = roadSnapper.snap(raw)
            val displayLocation = Location(raw).apply {
                if (snapped != null) {
                    latitude = snapped.latitude
                    longitude = snapped.longitude
                }
            }

            lastLocation = displayLocation
            updateCarMarker(displayLocation)

            val speedKmh = if (raw.hasSpeed()) {
                (raw.speed * 3.6f).roundToInt()
            } else 0

            currentSpeed.text = speedKmh.toString()
            status.text = "안전운행 중"

            val roadText = snapped?.roadName?.takeIf { it.isNotBlank() }
            dataStatus.text = buildString {
                append("GPS ")
                append(raw.accuracy.roundToInt())
                append("m · ")
                append(speedKmh)
                append("km/h")
                if (snapped != null) append(" · 도로보정")
            }
            locationLabel.text = roadText ?: "현재 도로 추적 중"

            updateNavigationCamera(displayLocation, firstFix)
            firstFix = false
            loadNearbySafetyPoints(displayLocation)
            map.invalidate()
        }
    }

    private fun updateCarMarker(location: Location) {
        if (carMarker == null) {
            carMarker = Marker(map).apply {
                title = "현재 위치"
                icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_navigation_arrow)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setFlat(false)
                map.overlays.add(this)
            }
        }
        carMarker?.position = GeoPoint(location.latitude, location.longitude)
    }

    private fun updateNavigationCamera(location: Location, forceZoom: Boolean) {
        if (!followMode) return

        val heading = if (location.hasBearing() && location.speed > 1.2f) {
            location.bearing
        } else 0f

        if (forceZoom || map.zoomLevelDouble < 17.0) {
            map.controller.setZoom(18.0)
        }

        if (heading != 0f) {
            map.mapOrientation = -heading
            map.controller.animateTo(
                pointAhead(location.latitude, location.longitude, heading.toDouble(), 110.0)
            )
        } else {
            map.controller.animateTo(GeoPoint(location.latitude, location.longitude))
        }
    }

    private fun recenter(navigationMode: Boolean) {
        lastLocation?.let {
            followMode = navigationMode || driving
            updateNavigationCamera(it, false)
        }
    }

    private fun pointAhead(
        lat: Double,
        lon: Double,
        bearing: Double,
        meters: Double
    ): GeoPoint {
        val radius = 6371000.0
        val delta = meters / radius
        val theta = Math.toRadians(bearing)
        val phi1 = Math.toRadians(lat)
        val lambda1 = Math.toRadians(lon)

        val phi2 = asin(
            sin(phi1) * cos(delta) +
                cos(phi1) * sin(delta) * cos(theta)
        )
        val lambda2 = lambda1 + atan2(
            sin(theta) * sin(delta) * cos(phi1),
            cos(delta) - sin(phi1) * sin(phi2)
        )

        return GeoPoint(Math.toDegrees(phi2), Math.toDegrees(lambda2))
    }

    private fun loadNearbySafetyPoints(location: Location) {
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
            withContext(Dispatchers.Main) {
                driveHint.text = when {
                    points.isNotEmpty() -> "5km 이내 안전정보 ${points.size}건"
                    safetyTotal == 0 -> "안전정보 데이터 업데이트 필요"
                    else -> "5km 이내 안전정보 없음"
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
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

        val moving = lastLocation?.speed?.let { it > 1.5f } == true
        if (!moving) updateCompass(sensorHeading)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun updateCompass(heading: Float) {
        compassView.animate().rotation(heading).setDuration(180L).start()
    }

    private fun roadTileSource(): OnlineTileSourceBase {
        return object : OnlineTileSourceBase(
            "SafeNaviRoad",
            0,
            20,
            256,
            ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
                "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
                "https://c.basemaps.cartocdn.com/rastertiles/voyager/",
                "https://d.basemaps.cartocdn.com/rastertiles/voyager/"
            ),
            "© OpenStreetMap contributors © CARTO"
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl +
                    MapTileIndex.getZoom(pMapTileIndex) + "/" +
                    MapTileIndex.getX(pMapTileIndex) + "/" +
                    MapTileIndex.getY(pMapTileIndex) + ".png"
            }
        }
    }

    private fun Int.dp(): Int =
        (this * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
        super.onDestroy()
    }
}
