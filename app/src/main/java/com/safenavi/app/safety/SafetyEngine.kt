package com.safenavi.app.safety

import com.safenavi.app.data.SafetyPoint

class SafetyEngine {
    private val cameraTypes = setOf("SPEED", "SIGNAL_SPEED", "SECTION")

    fun findAhead(
        lat: Double,
        lon: Double,
        heading: Double,
        points: List<SafetyPoint>,
        currentRoadName: String? = null,
        currentRoadGeometry: List<Pair<Double, Double>> = emptyList()
    ): List<SafetyAlert> {
        val candidates = points.mapNotNull { p ->
            // Only enforcement cameras. Speed-limit signs and other road-safety
            // objects are never promoted to an alert.
            if (p.type.uppercase() !in cameraTypes) return@mapNotNull null

            val d = GeoCalculator.distanceMeters(lat, lon, p.latitude, p.longitude)
            if (d > 2000.0) return@mapNotNull null

            // The camera must be physically in front of the vehicle.
            val bearingToCamera = GeoCalculator.bearing(lat, lon, p.latitude, p.longitude)
            val forwardAngle = GeoCalculator.angleDifference(heading, bearingToCamera)
            val maxForwardAngle = when {
                d < 250.0 -> 30.0
                d < 700.0 -> 20.0
                else -> 12.0
            }
            if (forwardAngle > maxForwardAngle) return@mapNotNull null

            val lateral = d * kotlin.math.sin(Math.toRadians(forwardAngle))
            val maxLateral = when {
                d < 250.0 -> 32.0
                d < 700.0 -> 26.0
                else -> 20.0
            }
            if (lateral > maxLateral) return@mapNotNull null

            // When snapped road geometry is available, require the camera to lie
            // close to that actual road shape. This rejects nearby parallel roads,
            // opposite carriageways and underground/overpass roads that happen to
            // be inside the same broad forward cone.
            // RoadSnapper currently supplies only a short local geometry
            // (roughly 70 m behind and 140 m ahead). Do not use that short
            // polyline to reject cameras farther down the road, otherwise a
            // valid camera hundreds of metres ahead would always look "far"
            // from the end of the local geometry and be incorrectly hidden.
            if (currentRoadGeometry.size >= 2 && d <= 180.0) {
                val roadDistance = distanceToPolylineMeters(
                    p.latitude, p.longitude, currentRoadGeometry
                )
                val maxRoadDistance = if (d < 80.0) 24.0 else 20.0
                if (roadDistance > maxRoadDistance) return@mapNotNull null
            }

            // Use direction metadata when the source provides it. Missing direction
            // must not hide a real camera; forward/lateral geometry still filters it.
            p.direction?.let { cameraDirection ->
                if (GeoCalculator.angleDifference(heading, cameraDirection) > 28.0) {
                    return@mapNotNull null
                }
            }

            // Compare road names only when both sides actually provide one.
            // OSM camera nodes often omit road-name metadata.
            if (!currentRoadName.isNullOrBlank() && !p.roadName.isNullOrBlank()) {
                if (normalizeRoadName(p.roadName) != normalizeRoadName(currentRoadName)) {
                    return@mapNotNull null
                }
            }

            SafetyAlert(p, d)
        }.sortedBy { it.distanceMeters }

        // Some sources contain duplicate camera nodes. Keep only one marker for
        // cameras effectively at the same position/direction.
        val kept = mutableListOf<SafetyAlert>()
        for (candidate in candidates) {
            val duplicate = kept.any { existing ->
                GeoCalculator.distanceMeters(
                    existing.point.latitude,
                    existing.point.longitude,
                    candidate.point.latitude,
                    candidate.point.longitude
                ) < 35.0
            }
            if (!duplicate) kept += candidate
        }
        return kept
    }
    private fun normalizeRoadName(value: String): String =
        value.lowercase()
            .replace(" ", "")
            .replace("-", "")
            .replace("·", "")
            .trim()

    private fun distanceToPolylineMeters(
        lat: Double,
        lon: Double,
        points: List<Pair<Double, Double>>
    ): Double {
        var best = Double.MAX_VALUE
        val latScale = 111320.0
        val lonScale = 111320.0 * kotlin.math.cos(Math.toRadians(lat))

        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            val ax = (a.second - lon) * lonScale
            val ay = (a.first - lat) * latScale
            val bx = (b.second - lon) * lonScale
            val by = (b.first - lat) * latScale
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0.0) {
                (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            } else 0.0
            val px = ax + dx * t
            val py = ay + dy * t
            val dist = kotlin.math.sqrt(px * px + py * py)
            if (dist < best) best = dist
        }
        return best
    }
}
