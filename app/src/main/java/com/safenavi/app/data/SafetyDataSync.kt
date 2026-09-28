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

    suspend fun syncNearbyIfNeeded(lat: Double, lon: Double): Int = withContext(Dispatchers.IO) {
        if (!isCapitalArea(lat, lon)) return@withContext 0

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
        if (points.isNotEmpty()) {
            db.safetyPointDao().insertAll(points)
            prefs.edit()
                .putLong("last_lat", java.lang.Double.doubleToLongBits(lat))
                .putLong("last_lon", java.lang.Double.doubleToLongBits(lon))
                .putLong("last_time", now)
                .apply()
        }

        points.size
    }

    private fun isCapitalArea(lat: Double, lon: Double): Boolean {
        return lat in 36.85..38.35 && lon in 125.9..127.9
    }

    private fun fetchOsmSafetyPoints(lat: Double, lon: Double): List<SafetyPoint> {
        val query = """
            [out:json][timeout:20];
            (
              nwr(around:5000,$lat,$lon)["amenity"="school"];
              nwr(around:5000,$lat,$lon)["traffic_calming"];
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

            val isSchool = tags.optString("amenity") == "school"
            val trafficCalming = tags.optString("traffic_calming")
            if (!isSchool && trafficCalming.isBlank()) continue

            val type = if (isSchool) "SCHOOL" else "TRAFFIC_CALMING"
            val name = when {
                tags.optString("name").isNotBlank() -> tags.optString("name")
                isSchool -> "학교 주변"
                trafficCalming.isNotBlank() -> "과속방지시설"
                else -> "안전운행 지점"
            }

            val stableId = -abs(
                ((osmType.hashCode().toLong() and 0x7fffffffL) shl 32) xor osmId
            )

            result += SafetyPoint(
                id = stableId,
                region = "수도권",
                latitude = lat,
                longitude = lon,
                type = type,
                speedLimit = null,
                roadName = null,
                locationName = name,
                direction = null,
                sectionType = null,
                sectionLength = null,
                protectedArea = isSchool,
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
