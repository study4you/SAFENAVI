package com.safenavi.app.safety

import com.safenavi.app.data.SafetyPoint

class SafetyEngine {
    private val cameraTypes = setOf("SPEED", "SIGNAL_SPEED", "SECTION")

    fun findAhead(
        lat: Double,
        lon: Double,
        heading: Double,
        points: List<SafetyPoint>,
        currentRoadName: String? = null
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
            if (forwardAngle > 12.0) return@mapNotNull null

            val lateral = d * kotlin.math.sin(Math.toRadians(forwardAngle))
            if (lateral > 18.0) return@mapNotNull null

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

}
