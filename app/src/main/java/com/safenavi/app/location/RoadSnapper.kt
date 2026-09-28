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
                "${location.longitude},${location.latitude}?number=1"

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

            val wp = waypoints.getJSONObject(0)
            val locationArray = wp.getJSONArray("location")
            val distance = wp.optDouble("distance", Double.MAX_VALUE)

            if (distance > 45.0) return@withContext null

            SnappedRoadPoint(
                latitude = locationArray.getDouble(1),
                longitude = locationArray.getDouble(0),
                roadName = wp.optString("name").takeIf { it.isNotBlank() },
                snapDistanceMeters = distance
            ).also { lastResult = it }
        } catch (_: Exception) {
            lastResult
        } finally {
            connection.disconnect()
        }
    }
}
