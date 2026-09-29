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
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionBase
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionHeight
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionOpacity
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.toNumber
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions

class SafeNaviMap(private val mapView: MapView) {
    enum class ViewMode { TWO_D, THREE_D }

    private var map: MapLibreMap? = null
    private var mode = ViewMode.TWO_D
    private var loadedStyle: Style? = null
    private val safetyMarkers = mutableListOf<Marker>()
    private var currentRoadLine: Polyline? = null

    fun attach(mapLibreMap: MapLibreMap, onReady: () -> Unit = {}) {
        map = mapLibreMap
        mapLibreMap.setStyle(
            Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")
        ) { style ->
            loadedStyle = style
            simplifyBaseStyle(style)
            installRoadHierarchy(style)
            installKoreanRoadLabels(style)
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

    private fun simplifyBaseStyle(style: Style) {
        // Safety-driving mode: suppress noisy POI/transit/shop labels from the public base style.
        style.layers.forEach { layer ->
            val id = layer.id.lowercase()
            if (id.contains("poi") || id.contains("shop") || id.contains("transit") ||
                id.contains("station") || id.contains("housenumber")) {
                layer.setProperties(visibility(Property.NONE))
            }
        }
    }

    private fun installRoadHierarchy(style: Style) {
        if (style.getLayer("safenavi-motorway") != null) return

        fun roadLayer(id: String, roadClass: String, color: String, width: Float, opacity: Float) =
            LineLayer(id, "openmaptiles")
                .withSourceLayer("transportation")
                .withFilter(eq(get("class"), literal(roadClass)))
                .withProperties(
                    lineColor(color),
                    lineWidth(width),
                    lineOpacity(opacity)
                )

        // Draw from local streets to the highest-capacity roads so the driving hierarchy is obvious.
        style.addLayer(roadLayer("safenavi-minor", "minor", "#e7e7e4", 1.15f, 0.52f))
        style.addLayer(roadLayer("safenavi-secondary", "secondary", "#f3e2ad", 2.15f, 0.68f))
        style.addLayer(roadLayer("safenavi-primary", "primary", "#f4cf79", 3.15f, 0.78f))
        style.addLayer(roadLayer("safenavi-trunk", "trunk", "#efb85c", 4.15f, 0.84f))
        style.addLayer(roadLayer("safenavi-motorway", "motorway", "#e9a84e", 5.1f, 0.90f))
    }

    private fun installKoreanRoadLabels(style: Style) {
        if (style.getLayer("safenavi-road-labels") != null) return
        val layer = SymbolLayer("safenavi-road-labels", "openmaptiles")
            .withSourceLayer("transportation_name")
            .withProperties(
                textField("{name:ko}"),
                textSize(13f),
                textColor("#4f5357"),
                textHaloColor("#ffffff"),
                textHaloWidth(1.4f)
            )
        style.addLayer(layer)
    }

    private fun installBuildingLayer(style: Style) {
        if (style.getLayer("safenavi-3d-buildings") != null) return
        val layer = FillExtrusionLayer("safenavi-3d-buildings", "openmaptiles")
            .withSourceLayer("building")
            .withProperties(
                fillExtrusionColor("#d9dcde"),
                fillExtrusionOpacity(0.48f),
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
        updateCurrentRoadHighlight(location, heading)
    }

    private fun updateCurrentRoadHighlight(location: Location, heading: Float) {
        val current = map ?: return
        currentRoadLine?.let { current.removePolyline(it) }
        val center = LatLng(location.latitude, location.longitude)
        val back = offset(center, heading + 180f, 32.0)
        val front = offset(center, heading, 78.0)
        currentRoadLine = current.addPolyline(
            PolylineOptions()
                .add(back, center, front)
                .width(8f)
                .color(android.graphics.Color.rgb(255, 184, 54))
        )
    }

    private fun offset(origin: LatLng, bearing: Float, meters: Double): LatLng {
        val radius = 6371000.0
        val angular = meters / radius
        val theta = Math.toRadians(bearing.toDouble())
        val phi1 = Math.toRadians(origin.latitude)
        val lambda1 = Math.toRadians(origin.longitude)
        val phi2 = kotlin.math.asin(
            kotlin.math.sin(phi1) * kotlin.math.cos(angular) +
                kotlin.math.cos(phi1) * kotlin.math.sin(angular) * kotlin.math.cos(theta)
        )
        val lambda2 = lambda1 + kotlin.math.atan2(
            kotlin.math.sin(theta) * kotlin.math.sin(angular) * kotlin.math.cos(phi1),
            kotlin.math.cos(angular) - kotlin.math.sin(phi1) * kotlin.math.sin(phi2)
        )
        return LatLng(Math.toDegrees(phi2), Math.toDegrees(lambda2))
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
