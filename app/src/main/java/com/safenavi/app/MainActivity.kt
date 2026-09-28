package com.safenavi.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

class MainActivity : AppCompatActivity(), LocationListener {
    private lateinit var map: MapView
    private lateinit var status: TextView
    private lateinit var dataStatus: TextView
    private lateinit var locationManager: LocationManager
    private lateinit var db: SafetyDatabase

    private var currentMarker: Marker? = null
    private val safetyMarkers = mutableListOf<Marker>()
    private var lastLocation: Location? = null
    private var firstFix = true
    private var driving = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startMapLocationUpdates()
                startDrive()
            } else {
                status.text = "위치 권한이 필요합니다."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        dataStatus = findViewById(R.id.dataStatus)
        map = findViewById(R.id.map)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        db = SafetyDatabase.getInstance(this)

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(14.0)
        map.controller.setCenter(GeoPoint(37.5665, 126.9780))

        lifecycleScope.launch(Dispatchers.IO) {
            val count = db.safetyPointDao().count()
            withContext(Dispatchers.Main) {
                dataStatus.text = if (count > 0) {
                    "안전정보 데이터 ${count}건 로드됨"
                } else {
                    "안전정보 데이터 0건 · 지도/GPS만 동작 중"
                }
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

        findViewById<Button>(R.id.myLocationButton).setOnClickListener {
            lastLocation?.let {
                map.controller.animateTo(GeoPoint(it.latitude, it.longitude))
                map.controller.setZoom(17.0)
            }
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, DrivingLocationService::class.java))
            driving = false
            status.text = "안전운행 종료 · 지도는 계속 표시됩니다."
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startMapLocationUpdates()
        }
    }

    private fun startDrive() {
        ContextCompat.startForegroundService(this, Intent(this, DrivingLocationService::class.java))
        driving = true
        status.text = "안전운행 시작 · GPS 위치 수신 중"
    }

    private fun startMapLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                1f,
                this
            )
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                onLocationChanged(it)
            }
        } catch (_: Exception) {
            status.text = "GPS를 사용할 수 없습니다."
        }
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        val point = GeoPoint(location.latitude, location.longitude)

        if (currentMarker == null) {
            currentMarker = Marker(map).apply {
                title = "현재 위치"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                map.overlays.add(this)
            }
        }
        currentMarker?.position = point

        if (firstFix) {
            map.controller.setZoom(17.0)
            map.controller.animateTo(point)
            firstFix = false
        } else if (driving) {
            map.controller.animateTo(point)
        }

        val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        status.text = if (driving) {
            "안전운행 중 · GPS ${location.accuracy.toInt()}m · ${"%.0f".format(speedKmh)}km/h"
        } else {
            "현재 위치 확인 · GPS ${location.accuracy.toInt()}m"
        }

        loadNearbySafetyPoints(location)
        map.invalidate()
    }

    private fun loadNearbySafetyPoints(location: Location) {
        lifecycleScope.launch(Dispatchers.IO) {
            val r = 5000.0
            val latD = r / 111320.0
            val lonD = r / (111320.0 * kotlin.math.cos(Math.toRadians(location.latitude)))
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
                            p.speedLimit?.let { append(" · 제한속도 $it km/h") }
                        }
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    safetyMarkers.add(marker)
                    map.overlays.add(marker)
                }

                val total = db.safetyPointDao().count()
                dataStatus.text = if (total == 0) {
                    "안전정보 데이터 0건 · 지도/GPS만 동작 중"
                } else {
                    "전체 $total건 · 현재 5km 이내 ${points.size}건"
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

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        super.onDestroy()
    }
}
