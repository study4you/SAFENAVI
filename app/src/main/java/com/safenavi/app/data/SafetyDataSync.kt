package com.safenavi.app.data

import android.content.Context
import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

class SafetyDataSync(
    context: Context,
    private val db: SafetyDatabase
) {
    private val prefs = context.getSharedPreferences("safety_sync", Context.MODE_PRIVATE)
    private var lastAttemptAt = 0L

    suspend fun syncNearbyIfNeeded(lat: Double, lon: Double): Int = withContext(Dispatchers.IO) {
        if (!isCapitalArea(lat, lon)) return@withContext 0

        val attemptNow = System.currentTimeMillis()
        if (attemptNow - lastAttemptAt < 60_000L) return@withContext 0
        lastAttemptAt = attemptNow

        val lastLat = java.lang.Double.longBitsToDouble(
            prefs.getLong("last_lat", java.lang.Double.doubleToLongBits(0.0))
        )
        val lastLon = java.lang.Double.longBitsToDouble(
            prefs.getLong("last_lon", java.lang.Double.doubleToLongBits(0.0))
        )
        val lastTime = prefs.getLong("last_time", 0L)

        val moved = if (lastLat == 0.0 && lastLon == 0.0) {
            Double.MAX_VALUE
        } else {
            distanceMeters(lastLat, lastLon, lat, lon)
        }

        val now = System.currentTimeMillis()
        if (now - lastTime < 10 * 60 * 1000L && moved < 1500.0) {
            return@withContext 0
        }

        val points = fetchOsmSafetyPoints(lat, lon)
        // Remove legacy school/traffic-calming rows left by older builds so
        // the local database remains camera-only.
        db.safetyPointDao().deleteNonEnforcement()
        if (points.isNotEmpty()) {
            db.safetyPointDao().insertAll(points)
        }
        // A successful empty response is still a completed sync. Without recording
        // it, camera-free areas would hit Overpass again on every location update.
        prefs.edit()
            .putLong("last_lat", java.lang.Double.doubleToLongBits(lat))
            .putLong("last_lon", java.lang.Double.doubleToLongBits(lon))
            .putLong("last_time", now)
            .apply()

        points.size
    }

    private fun isCapitalArea(lat: Double, lon: Double): Boolean {
        return lat in 36.85..38.35 && lon in 125.9..127.9
    }

    private fun fetchOsmSafetyPoints(lat: Double, lon: Double): List<SafetyPoint> {
        val query = """
            [out:json][timeout:20];
            (
              nwr(around:5000,$lat,$lon)["highway"="speed_camera"];
              nwr(around:5000,$lat,$lon)["enforcement"="maxspeed"];
              nwr(around:5000,$lat,$lon)["enforcement"="traffic_signals"];
            );
            out center tags;
        """.trimIndent()

        val url = URL("https://overpass-api.de/api/interpreter")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("User-Agent", "SafeNavi/0.03")
        }

        return try {
            val body = "data=" + java.net.URLEncoder.encode(query, "UTF-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (connection.responseCode !in 200..299) return emptyList()

            val text = connection.inputStream.bufferedReader().use { it.readText() }
            parseOsm(text)
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    private fun parseOsm(json: String): List<SafetyPoint> {
        val root = JSONObject(json)
        val elements = root.optJSONArray("elements") ?: return emptyList()
        val result = ArrayList<SafetyPoint>(elements.length())

        for (i in 0 until elements.length()) {
            val e = elements.optJSONObject(i) ?: continue
            val osmType = e.optString("type")
            val osmId = e.optLong("id")
            val tags = e.optJSONObject("tags") ?: JSONObject()

            val lat = if (e.has("lat")) {
                e.optDouble("lat")
            } else {
                e.optJSONObject("center")?.optDouble("lat") ?: Double.NaN
            }
            val lon = if (e.has("lon")) {
                e.optDouble("lon")
            } else {
                e.optJSONObject("center")?.optDouble("lon") ?: Double.NaN
            }
            if (!lat.isFinite() || !lon.isFinite()) continue

            val highway = tags.optString("highway")
            val enforcement = tags.optString("enforcement")
            val isCamera = highway == "speed_camera" || enforcement == "maxspeed" || enforcement == "traffic_signals"
            if (!isCamera) continue

            val type = when (enforcement) {
                "traffic_signals" -> "SIGNAL_SPEED"
                else -> "SPEED"
            }
            val name = tags.optString("name").takeIf { it.isNotBlank() } ?: "단속 카메라"
            val maxspeed = tags.optString("maxspeed").filter { it.isDigit() }.toIntOrNull()
            val direction = tags.optString("direction").toDoubleOrNull()
            val roadName = tags.optString("road_name").takeIf { it.isNotBlank() }
                ?: tags.optString("addr:street").takeIf { it.isNotBlank() }

            val stableId = -abs(
                ((osmType.hashCode().toLong() and 0x7fffffffL) shl 32) xor osmId
            )

            result += SafetyPoint(
                id = stableId,
                region = "수도권",
                latitude = lat,
                longitude = lon,
                type = type,
                speedLimit = maxspeed,
                roadName = roadName,
                locationName = name,
                direction = direction,
                sectionType = null,
                sectionLength = null,
                protectedArea = false,
                dataDate = "OSM"
            )
        }
        return result
    }

    private fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(aLat)
        val p2 = Math.toRadians(bLat)
        val dp = Math.toRadians(bLat - aLat)
        val dl = Math.toRadians(bLon - aLon)
        val a = kotlin.math.sin(dp / 2) * kotlin.math.sin(dp / 2) +
            kotlin.math.cos(p1) * kotlin.math.cos(p2) *
            kotlin.math.sin(dl / 2) * kotlin.math.sin(dl / 2)
        return r * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }
}
