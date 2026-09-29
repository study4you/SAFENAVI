package com.safenavi.app.map

import android.location.Location
import com.safenavi.app.data.SafetyPoint
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionBase
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionHeight
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionOpacity
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.toNumber
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions

class SafeNaviMap(private val mapView: MapView) {
    enum class ViewMode { TWO_D, THREE_D }

    private var map: MapLibreMap? = null
    private var mode = ViewMode.TWO_D
    private var loadedStyle: Style? = null
    private val safetyMarkers = mutableListOf<Marker>()

    fun attach(mapLibreMap: MapLibreMap, onReady: () -> Unit = {}) {
        map = mapLibreMap
        mapLibreMap.setStyle(
            Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")
        ) { style ->
            loadedStyle = style
            installBuildingLayer(style)
            applyBuildingMode()
            onReady()
        }
    }

    fun setViewMode(value: ViewMode) {
        mode = value
        map?.cameraPosition = map?.cameraPosition?.let {
            CameraPosition.Builder(it)
                .tilt(if (value == ViewMode.THREE_D) 58.0 else 0.0)
                .build()
        } ?: return
        applyBuildingMode()
    }

    private fun installBuildingLayer(style: Style) {
        if (style.getLayer("safenavi-3d-buildings") != null) return
        val layer = FillExtrusionLayer("safenavi-3d-buildings", "openmaptiles")
            .withSourceLayer("building")
            .withMinZoom(15f)
            .withProperties(
                fillExtrusionColor("#d7d9dc"),
                fillExtrusionOpacity(0.78f),
                fillExtrusionHeight(toNumber(get("render_height"))),
                fillExtrusionBase(toNumber(get("render_min_height"))),
                visibility(Property.NONE)
            )
        style.addLayer(layer)
    }

    private fun applyBuildingMode() {
        loadedStyle?.getLayer("safenavi-3d-buildings")?.setProperties(
            visibility(if (mode == ViewMode.THREE_D) Property.VISIBLE else Property.NONE)
        )
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
