package com.safenavi.app.map

import android.location.Location
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.safenavi.app.R
import com.safenavi.app.data.SafetyPoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.*

class OsmDrivingMap(private val map: MapView) {
    enum class ViewMode { TWO_D, THREE_D }
    private var viewMode = ViewMode.TWO_D
    private val safetyMarkers = mutableListOf<Marker>()
    private val vehicle = Marker(map)

    init {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(16.5)
        vehicle.icon = ContextCompat.getDrawable(map.context, R.drawable.ic_navigation_arrow)
        vehicle.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        map.overlays.add(vehicle)
    }

    fun setViewMode(mode: ViewMode) {
        viewMode = mode
        // osmdroid is a 2D raster renderer. Keep this explicit: THREE_D is
        // persisted now, but a real pitched/extruded renderer will replace it.
        map.invalidate()
    }

    fun zoomIn() { map.controller.zoomIn() }
    fun zoomOut() { map.controller.zoomOut() }

    fun updateVehicle(location: Location, heading: Float, forceZoom: Boolean) {
        val speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        val ahead = (45.0 + speed * 1.2).coerceIn(45.0, 70.0)
        val target = pointAhead(location.latitude, location.longitude, heading.toDouble(), ahead)
        vehicle.position = GeoPoint(location.latitude, location.longitude)
        vehicle.rotation = -heading
        map.mapOrientation = -heading
        if (forceZoom) map.controller.setZoom(16.5)
        map.controller.setCenter(target)
        map.invalidate()
    }

    fun showSafetyPoints(points: List<SafetyPoint>) {
        safetyMarkers.forEach { map.overlays.remove(it) }
        safetyMarkers.clear()
        points.forEach { p ->
            Marker(map).apply {
                position = GeoPoint(p.latitude, p.longitude)
                title = when (p.type) {
                    "SIGNAL_SPEED" -> "신호·과속"
                    "SECTION" -> "구간단속"
                    else -> "과속단속"
                }
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map.overlays.add(this)
            }.also(safetyMarkers::add)
        }
        map.invalidate()
    }

    private fun pointAhead(lat: Double, lon: Double, bearing: Double, meters: Double): GeoPoint {
        val radius = 6371000.0
        val delta = meters / radius
        val theta = Math.toRadians(bearing)
        val phi1 = Math.toRadians(lat)
        val lambda1 = Math.toRadians(lon)
        val phi2 = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta))
        val lambda2 = lambda1 + atan2(sin(theta) * sin(delta) * cos(phi1), cos(delta) - sin(phi1) * sin(phi2))
        return GeoPoint(Math.toDegrees(phi2), Math.toDegrees(lambda2))
    }
}
