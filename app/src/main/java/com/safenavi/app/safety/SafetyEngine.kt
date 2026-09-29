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

            // Direction is the decisive filter. If the source has no direction,
            // do not guess and accidentally warn for the opposite carriageway.
            val cameraDirection = p.direction ?: return@mapNotNull null
            if (GeoCalculator.angleDifference(heading, cameraDirection) > 18.0) {
                return@mapNotNull null
            }

            // Keep alerts on the road currently being driven.
            if (currentRoadName != null) {
                val cameraRoad = p.roadName ?: return@mapNotNull null
                if (!cameraRoad.equals(currentRoadName, ignoreCase = true)) return@mapNotNull null
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
}
