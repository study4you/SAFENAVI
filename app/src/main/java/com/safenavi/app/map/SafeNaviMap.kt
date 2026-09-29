package com.safenavi.app.map

import android.location.Location
import com.safenavi.app.data.SafetyPoint
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions

class SafeNaviMap(private val mapView: MapView) {
    enum class ViewMode { TWO_D, THREE_D }

    private var map: MapLibreMap? = null
    private var mode = ViewMode.TWO_D
    private val safetyMarkers = mutableListOf<Marker>()

    fun attach(mapLibreMap: MapLibreMap, onReady: () -> Unit = {}) {
        map = mapLibreMap
        mapLibreMap.setStyle(
            Style.Builder().fromUri("https://demotiles.maplibre.org/style.json")
        ) { onReady() }
    }

    fun setViewMode(value: ViewMode) {
        mode = value
        map?.cameraPosition = map?.cameraPosition?.let {
            CameraPosition.Builder(it)
                .tilt(if (value == ViewMode.THREE_D) 58.0 else 0.0)
                .build()
        } ?: return
    }

    fun zoomIn() { map?.animateCamera(CameraUpdateFactory.zoomIn()) }
    fun zoomOut() { map?.animateCamera(CameraUpdateFactory.zoomOut()) }

    fun updateVehicle(location: Location, heading: Float, forceZoom: Boolean) {
        val current = map ?: return
        val target = LatLng(location.latitude, location.longitude)
        val builder = CameraPosition.Builder()
            .target(target)
            .bearing(heading.toDouble())
            .tilt(if (mode == ViewMode.THREE_D) 58.0 else 0.0)
        if (forceZoom) builder.zoom(16.5) else builder.zoom(current.cameraPosition.zoom)
        current.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()), 350)
    }

    fun showSafetyPoints(points: List<SafetyPoint>) {
        val current = map ?: return
        safetyMarkers.forEach { current.removeMarker(it) }
        safetyMarkers.clear()
        points.forEach { point ->
            val title = when (point.type.uppercase()) {
                "SIGNAL_SPEED" -> "신호·과속"
                "SECTION" -> "구간단속"
                else -> "과속단속"
            }
            val marker = current.addMarker(
                MarkerOptions()
                    .position(LatLng(point.latitude, point.longitude))
                    .title(title)
            )
            safetyMarkers += marker
        }
    }
}
