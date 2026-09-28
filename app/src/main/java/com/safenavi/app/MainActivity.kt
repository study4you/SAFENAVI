package com.safenavi.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.safenavi.app.data.SafetyDatabase
import com.safenavi.app.location.DrivingLocationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.*

class MainActivity : AppCompatActivity(), LocationListener {
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

    private lateinit var locationManager: LocationManager
    private lateinit var db: SafetyDatabase

    private var carMarker: Marker? = null
    private val safetyMarkers = mutableListOf<Marker>()
    private var lastLocation: Location? = null
    private var firstFix = true
    private var driving = false
    private var followMode = true
    private var safetyTotal = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startMapLocationUpdates()
                startDrive()
            } else {
                status.text = "위치 권한이 필요합니다"
                dataStatus.text = "안전운행을 시작하려면 위치 권한을 허용해 주세요"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        bindViews()
        applySystemInsets()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        db = SafetyDatabase.getInstance(this)

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.isTilesScaledToDpi = true
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(37.5665, 126.9780))

        lifecycleScope.launch(Dispatchers.IO) {
            safetyTotal = db.safetyPointDao().count()
            withContext(Dispatchers.Main) {
                updateIdleStatus()
            }
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startMapLocationUpdates()
                startDrive()
            } else {
                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        findViewById<Button>(R.id.myLocationButton).setOnClickListener { recenter(false) }
        findViewById<Button>(R.id.recenterButton).setOnClickListener { recenter(true) }
        findViewById<Button>(R.id.driveRecenterButton).setOnClickListener { recenter(true) }

        findViewById<Button>(R.id.zoomInButton).setOnClickListener { map.controller.zoomIn() }
        findViewById<Button>(R.id.zoomOutButton).setOnClickListener { map.controller.zoomOut() }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, DrivingLocationService::class.java))
            exitDriveMode()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startMapLocationUpdates()
        }
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

    private fun startDrive() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, DrivingLocationService::class.java)
        )
        enterDriveMode()
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
            "GPS 주행모드 · 안전정보 데이터 0건"
        }

        lastLocation?.let { updateNavigationCamera(it, true) }
    }

    private fun exitDriveMode() {
        driving = false
        followMode = false

        idleControls.visibility = View.VISIBLE
        driveControls.visibility = View.GONE
        speedPanel.visibility = View.GONE
        floatingButtons.visibility = View.GONE

        map.mapOrientation = 0f
        updateIdleStatus()
        lastLocation?.let {
            map.controller.animateTo(GeoPoint(it.latitude, it.longitude))
        }
    }

    private fun updateIdleStatus() {
        status.text = if (lastLocation == null) "안전운행 대기" else "현재 위치 확인"
        dataStatus.text = if (safetyTotal > 0) {
            "안전정보 데이터 ${safetyTotal}건 · 시작 버튼을 누르세요"
        } else {
            "안전정보 데이터 0건 · 지도/GPS만 동작 중"
        }
    }

    private fun startMapLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        try {
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
        lastLocation = location
        updateCarMarker(location)

        val speedKmh = if (location.hasSpeed()) {
            (location.speed * 3.6f).roundToInt()
        } else {
            0
        }
        currentSpeed.text = speedKmh.toString()

        if (driving) {
            status.text = "안전운행 중"
            dataStatus.text = "GPS ${location.accuracy.roundToInt()}m · ${speedKmh}km/h"
            locationLabel.text = "현재 위치 추적 중"
            updateNavigationCamera(location, firstFix)
        } else {
            status.text = "현재 위치 확인"
            dataStatus.text = if (safetyTotal > 0) {
                "GPS ${location.accuracy.roundToInt()}m · 안전정보 ${safetyTotal}건"
            } else {
                "GPS ${location.accuracy.roundToInt()}m · 안전정보 데이터 0건"
            }

            if (firstFix) {
                map.controller.setZoom(17.0)
                map.controller.animateTo(GeoPoint(location.latitude, location.longitude))
            }
        }

        firstFix = false
        loadNearbySafetyPoints(location)
        map.invalidate()
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

        val heading = when {
            location.hasBearing() && location.speed > 1.2f -> location.bearing
            else -> 0f
        }

        if (forceZoom || map.zoomLevelDouble < 17.0) {
            map.controller.setZoom(18.0)
        }

        if (heading != 0f) {
            map.mapOrientation = -heading
            val ahead = pointAhead(
                location.latitude,
                location.longitude,
                heading.toDouble(),
                110.0
            )
            map.controller.animateTo(ahead)
        } else {
            map.controller.animateTo(GeoPoint(location.latitude, location.longitude))
        }
    }

    private fun recenter(navigationMode: Boolean) {
        lastLocation?.let {
            followMode = navigationMode || driving
            if (driving) {
                updateNavigationCamera(it, false)
            } else {
                map.mapOrientation = 0f
                map.controller.setZoom(17.0)
                map.controller.animateTo(GeoPoint(it.latitude, it.longitude))
            }
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
                safetyMarkers.forEach { map.overlays.remove(it) }
                safetyMarkers.clear()

                points.forEach { p ->
                    val marker = Marker(map).apply {
                        position = GeoPoint(p.latitude, p.longitude)
                        title = p.locationName ?: p.roadName ?: "안전운행 지점"
                        snippet = buildString {
                            append(
                                when (p.type) {
                                    "SPEED" -> "과속"
                                    "SIGNAL_SPEED" -> "신호·과속"
                                    "SECTION" -> "구간단속"
                                    else -> "안전정보"
                                }
                            )
                            p.speedLimit?.let { append(" · 제한속도 ${it}km/h") }
                        }
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    safetyMarkers.add(marker)
                    map.overlays.add(marker)
                }

                if (driving) {
                    driveHint.text = if (points.isEmpty()) {
                        if (safetyTotal == 0) {
                            "안전정보 데이터 필요"
                        } else {
                            "5km 이내 안전정보 없음"
                        }
                    } else {
                        "5km 이내 안전정보 ${points.size}건"
                    }
                }
                map.invalidate()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        super.onDestroy()
    }
}
