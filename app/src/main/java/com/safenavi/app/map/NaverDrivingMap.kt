package com.safenavi.app.map

import android.location.Location
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraPosition
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.NaverMap
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.OverlayImage
import com.naver.maps.map.util.MarkerIcons
import com.safenavi.app.R
import com.safenavi.app.data.SafetyPoint
import kotlin.math.*

class NaverDrivingMap {
    private var map: NaverMap? = null
    private val safetyMarkers = mutableListOf<Marker>()

    fun attach(naverMap: NaverMap) {
        map = naverMap
        naverMap.mapType = NaverMap.MapType.Navi
        naverMap.uiSettings.isZoomControlEnabled = false
        naverMap.uiSettings.isCompassEnabled = false
        naverMap.locationOverlay.apply {
            isVisible = true
            icon = OverlayImage.fromResource(R.drawable.ic_navigation_arrow)
        }
        naverMap.moveCamera(
            CameraUpdate.toCameraPosition(
                CameraPosition(LatLng(37.5665, 126.9780), 15.0)
            )
        )
    }

    fun zoomIn() { map?.moveCamera(CameraUpdate.zoomIn()) }
    fun zoomOut() { map?.moveCamera(CameraUpdate.zoomOut()) }

    fun updateVehicle(location: Location, heading: Float, forceZoom: Boolean) {
        val naverMap = map ?: return
        naverMap.locationOverlay.apply {
            position = LatLng(location.latitude, location.longitude)
            bearing = heading
            isVisible = true
        }
        val speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        val ahead = (45.0 + speed * 1.2).coerceIn(45.0, 70.0)
        val target = pointAhead(location.latitude, location.longitude, heading.toDouble(), ahead)
        val zoom = if (forceZoom) 16.5 else naverMap.cameraPosition.zoom
        naverMap.moveCamera(
            CameraUpdate.toCameraPosition(
                CameraPosition(target, zoom, 0.0, heading.toDouble())
            )
        )
    }

    fun showSafetyPoints(points: List<SafetyPoint>) {
        safetyMarkers.forEach { it.map = null }
        safetyMarkers.clear()
        val naverMap = map ?: return
        points.forEach { point ->
            Marker().apply {
                position = LatLng(point.latitude, point.longitude)
                icon = MarkerIcons.RED
                captionText = when (point.type) {
                    "SIGNAL_SPEED" -> "신호·과속"
                    "SECTION" -> "구간단속"
                    else -> "과속단속"
                }
                map = naverMap
            }.also(safetyMarkers::add)
        }
    }

    private fun pointAhead(lat: Double, lon: Double, bearing: Double, meters: Double): LatLng {
        val radius = 6371000.0
        val delta = meters / radius
        val theta = Math.toRadians(bearing)
        val phi1 = Math.toRadians(lat)
        val lambda1 = Math.toRadians(lon)
        val phi2 = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta))
        val lambda2 = lambda1 + atan2(
            sin(theta) * sin(delta) * cos(phi1),
            cos(delta) - sin(phi1) * sin(phi2)
        )
        return LatLng(Math.toDegrees(phi2), Math.toDegrees(lambda2))
    }
}
