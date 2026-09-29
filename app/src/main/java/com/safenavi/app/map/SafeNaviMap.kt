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
    private var lastRoadGeometry: List<Pair<Double, Double>> = emptyList()
    private var lastCameraTarget: LatLng? = null
    private var lastCameraBearing = Double.NaN
    private var smoothedCameraBearing = Double.NaN
    private var smoothedTargetLat = Double.NaN
    private var smoothedTargetLon = Double.NaN
    private var lastCameraUpdateAt = 0L
    private var dynamicZoom = Double.NaN
    private var manualZoomUntil = 0L
    private var lookAheadTarget: LatLng? = null
    private var lastVehicleLocation: Location? = null
    private var lastReliableHeading = Float.NaN

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

    fun zoomIn() {
        manualZoomUntil = android.os.SystemClock.elapsedRealtime() + 12_000L
        map?.animateCamera(CameraUpdateFactory.zoomIn())
        dynamicZoom = map?.cameraPosition?.zoom ?: dynamicZoom
    }
    fun zoomOut() {
        manualZoomUntil = android.os.SystemClock.elapsedRealtime() + 12_000L
        map?.animateCamera(CameraUpdateFactory.zoomOut())
        dynamicZoom = map?.cameraPosition?.zoom ?: dynamicZoom
    }

    fun updateVehicle(location: Location, heading: Float, forceZoom: Boolean) {
        val current = map ?: return
        val previousVehicle = lastVehicleLocation
        val jumpDistance = previousVehicle?.distanceTo(location) ?: 0f
        val speedMpsForJump = if (location.hasSpeed()) location.speed else 0f
        val plausibleJump = 35f + speedMpsForJump * 2.5f
        if (!forceZoom && previousVehicle != null && jumpDistance > plausibleJump && location.accuracy > 12f) {
            return
        }
        lastVehicleLocation = Location(location)
        val speedForHeading = if (location.hasSpeed()) location.speed else 0f
        val effectiveHeading = if (location.hasBearing() && speedForHeading >= 2.5f) {
            lastReliableHeading = heading
            heading
        } else if (!lastReliableHeading.isNaN()) {
            lastReliableHeading
        } else {
            heading
        }
        val rawLat = location.latitude
        val rawLon = location.longitude
        if (smoothedTargetLat.isNaN() || forceZoom) {
            smoothedTargetLat = rawLat
            smoothedTargetLon = rawLon
        } else {
            val speed = if (location.hasSpeed()) location.speed else 0f
            val positionAlpha = when {
                speed >= 20f -> 0.72
                speed >= 10f -> 0.62
                speed >= 3f -> 0.50
                else -> 0.34
            }
            smoothedTargetLat += (rawLat - smoothedTargetLat) * positionAlpha
            smoothedTargetLon += (rawLon - smoothedTargetLon) * positionAlpha
        }
        val vehicleTarget = LatLng(smoothedTargetLat, smoothedTargetLon)
        val speedMps = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        val lookAheadMeters = (28.0 + speedMps * 1.65).coerceIn(28.0, 72.0)
        val projected = offset(vehicleTarget, effectiveHeading, lookAheadMeters)
        val previousLookAhead = lookAheadTarget
        val target = if (previousLookAhead == null || forceZoom) projected else {
            val alpha = if (speedMps >= 15.0) 0.62 else 0.48
            LatLng(
                previousLookAhead.latitude + (projected.latitude - previousLookAhead.latitude) * alpha,
                previousLookAhead.longitude + (projected.longitude - previousLookAhead.longitude) * alpha
            )
        }
        lookAheadTarget = target
        val speedKmh = if (location.hasSpeed()) location.speed * 3.6 else 0.0
        val desiredZoom = when {
            speedKmh >= 100.0 -> 15.1
            speedKmh >= 80.0 -> 15.4
            speedKmh >= 60.0 -> 15.7
            speedKmh >= 40.0 -> 16.0
            speedKmh >= 20.0 -> 16.3
            else -> 16.6
        }
        val manualZoomActive = android.os.SystemClock.elapsedRealtime() < manualZoomUntil
        if (dynamicZoom.isNaN() || forceZoom) dynamicZoom = desiredZoom
        else if (manualZoomActive) dynamicZoom = current.cameraPosition.zoom
        else dynamicZoom += (desiredZoom - dynamicZoom) * 0.18

        val builder = CameraPosition.Builder()
            .target(target)
            .bearing(heading.toDouble())
            .tilt(if (mode == ViewMode.THREE_D) 58.0 else 0.0)
            .zoom(dynamicZoom)
        val rawBearing = effectiveHeading.toDouble()
        if (smoothedCameraBearing.isNaN()) smoothedCameraBearing = rawBearing
        val turnDelta = ((rawBearing - smoothedCameraBearing + 540.0) % 360.0) - 180.0
        val bearingAlpha = when {
            kotlin.math.abs(turnDelta) >= 35.0 -> 0.58
            kotlin.math.abs(turnDelta) >= 12.0 -> 0.42
            else -> 0.26
        }
        smoothedCameraBearing = (smoothedCameraBearing + turnDelta * bearingAlpha + 360.0) % 360.0
        val cameraPosition = CameraPosition.Builder(builder.build())
            .bearing(smoothedCameraBearing)
            .build()

        val previousTarget = lastCameraTarget
        val moved = previousTarget?.let {
            val out = FloatArray(1)
            Location.distanceBetween(it.latitude, it.longitude, target.latitude, target.longitude, out)
            out[0]
        } ?: Float.MAX_VALUE
        val bearingDelta = if (lastCameraBearing.isNaN()) 180.0 else
            kotlin.math.abs(((smoothedCameraBearing - lastCameraBearing + 540.0) % 360.0) - 180.0)
        val now = android.os.SystemClock.elapsedRealtime()
        val minCameraInterval = when {
            location.hasSpeed() && location.speed >= 20f -> 220L
            location.hasSpeed() && location.speed >= 10f -> 260L
            location.hasSpeed() && location.speed >= 3f -> 320L
            else -> 420L
        }
        if (forceZoom || ((moved >= 1.5f || bearingDelta >= 1.2) && now - lastCameraUpdateAt >= minCameraInterval)) {
            current.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), minCameraInterval.toInt() + 120)
            lastCameraTarget = target
            lastCameraBearing = smoothedCameraBearing
            lastCameraUpdateAt = now
        }
        updateCurrentRoadHighlight(location, effectiveHeading)
    }

    fun updateRoadGeometry(points: List<Pair<Double, Double>>) {
        val current = map ?: return
        if (points.size < 2 || sameGeometry(points, lastRoadGeometry)) return
        currentRoadLine?.let { current.removePolyline(it) }
        val latLngs = points.map { LatLng(it.first, it.second) }.toTypedArray()
        currentRoadLine = current.addPolyline(
            PolylineOptions()
                .add(*latLngs)
                .width(8f)
                .color(android.graphics.Color.rgb(255, 184, 54))
        )
        lastRoadGeometry = points
    }

    private fun sameGeometry(a: List<Pair<Double, Double>>, b: List<Pair<Double, Double>>): Boolean {
        if (a.size != b.size || a.isEmpty()) return false
        val indexes = intArrayOf(0, a.lastIndex / 2, a.lastIndex).distinct()
        return indexes.all { i ->
            kotlin.math.abs(a[i].first - b[i].first) < 0.000001 &&
                kotlin.math.abs(a[i].second - b[i].second) < 0.000001
        }
    }

    private fun updateCurrentRoadHighlight(location: Location, heading: Float) {
        if (currentRoadLine != null) return
        val current = map ?: return
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
