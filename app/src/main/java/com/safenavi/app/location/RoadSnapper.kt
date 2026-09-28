package com.safenavi.app.location

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SnappedRoadPoint(
    val latitude: Double,
    val longitude: Double,
    val roadName: String?,
    val snapDistanceMeters: Double
)

class RoadSnapper {
    private var lastRequestAt = 0L
    private var lastSource: Location? = null
    private var lastResult: SnappedRoadPoint? = null
    private var stableResult: SnappedRoadPoint? = null

    suspend fun snap(location: Location): SnappedRoadPoint? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val previous = lastSource
        val moved = previous?.distanceTo(location) ?: Float.MAX_VALUE

        if (lastResult != null && now - lastRequestAt < 3000L && moved < 12f) {
            return@withContext lastResult
        }

        lastRequestAt = now
        lastSource = Location(location)

        val urlText =
            "https://router.project-osrm.org/nearest/v1/driving/" +
                "${location.longitude},${location.latitude}?number=3"

        val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2500
            readTimeout = 2500
            setRequestProperty("User-Agent", "SafeNavi/0.09")
        }

        try {
            if (connection.responseCode !in 200..299) return@withContext lastResult

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val waypoints = root.optJSONArray("waypoints") ?: return@withContext lastResult
            if (waypoints.length() == 0) return@withContext lastResult

            // Divided roads often return both carriageways as equally-near candidates.
            // Prefer continuity with the previously selected carriageway instead of
            // jumping to the opposite side because of a few metres of GPS noise.
            val candidates = (0 until waypoints.length()).mapNotNull { index ->
                val wp = waypoints.optJSONObject(index) ?: return@mapNotNull null
                val a = wp.optJSONArray("location") ?: return@mapNotNull null
                val distance = wp.optDouble("distance", Double.MAX_VALUE)
                if (distance > 45.0) return@mapNotNull null
                SnappedRoadPoint(
                    latitude = a.getDouble(1),
                    longitude = a.getDouble(0),
                    roadName = wp.optString("name").takeIf { it.isNotBlank() },
                    snapDistanceMeters = distance
                )
            }
            if (candidates.isEmpty()) return@withContext null

            val previousRoad = stableResult
            val selected = if (previousRoad != null && location.hasBearing() && location.speed > 3f) {
                candidates.minByOrNull { candidate ->
                    val continuity = FloatArray(1)
                    Location.distanceBetween(
                        previousRoad.latitude, previousRoad.longitude,
                        candidate.latitude, candidate.longitude,
                        continuity
                    )
                    // Strong hysteresis: a parallel carriageway must be substantially
                    // better before we allow a lane-side switch.
                    candidate.snapDistanceMeters + continuity[0] * 0.65
                } ?: candidates.first()
            } else {
                candidates.minByOrNull { it.snapDistanceMeters } ?: candidates.first()
            }

            val keepPrevious = previousRoad != null &&
                location.speed > 3f &&
                selected.roadName == previousRoad.roadName &&
                selected.snapDistanceMeters > previousRoad.snapDistanceMeters &&
                selected.snapDistanceMeters - previousRoad.snapDistanceMeters < 8.0

            val result = if (keepPrevious) previousRoad else selected
            stableResult = result
            result.also { lastResult = it }
        } catch (_: Exception) {
            lastResult
        } finally {
            connection.disconnect()
        }
    }
}
