package com.safenavi.app.location

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SnappedRoadPoint(
    val latitude: Double,
    val longitude: Double,
    val roadName: String?,
    val snapDistanceMeters: Double,
    val roadBearing: Double?,
    val roadGeometry: List<Pair<Double, Double>> = emptyList()
)

class RoadSnapper {
    private var lastRequestAt = 0L
    private var lastSource: Location? = null
    private var lastResult: SnappedRoadPoint? = null
    private var stableResult: SnappedRoadPoint? = null
    private val snapMutex = Mutex()
    private var newestFixTimeNanos = Long.MIN_VALUE
    private var geometryFetchedAt = 0L
    private var geometryAnchor: SnappedRoadPoint? = null
    private var lastSnapRequestAt = 0L
    private var consecutiveSnapFailures = 0
    private var nextNetworkAttemptAt = 0L
    private var lastGoodSnapAt = 0L

    fun lastKnownRoad(): SnappedRoadPoint? = lastResult

    suspend fun snap(location: Location): SnappedRoadPoint? = snapMutex.withLock {
        val fixTimeNanos = location.elapsedRealtimeNanos
        if (fixTimeNanos > 0L && fixTimeNanos < newestFixTimeNanos) {
            return@withLock lastResult
        }
        if (fixTimeNanos > 0L) newestFixTimeNanos = fixTimeNanos
        snapInternal(location)
    }

    private suspend fun snapInternal(location: Location): SnappedRoadPoint? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val previous = lastSource
        val moved = previous?.distanceTo(location) ?: Float.MAX_VALUE
        val speed = if (location.hasSpeed()) location.speed else 0f
        val minInterval = when {
            speed >= 20f -> 650L
            speed >= 10f -> 850L
            speed >= 3f -> 1200L
            else -> 1800L
        }
        val minMove = when {
            speed >= 20f -> 8f
            speed >= 10f -> 5f
            speed >= 3f -> 3f
            else -> 2f
        }
        if (lastResult != null && now - lastSnapRequestAt < minInterval && moved < minMove) {
            return@withContext lastResult
        }

        lastRequestAt = now
        lastSnapRequestAt = now
        lastSource = Location(location)

        if (now < nextNetworkAttemptAt && lastResult != null) {
            freshFallback(location, now)?.let { return@withContext it }
            // Keep cached metadata for tunnel continuity, but do not reuse an old
            // snapped coordinate after the vehicle has moved. MainActivity will
            // fall back to the fresh GPS position instead of freezing the car.
            return@withContext null
        }

        val bearingOption = if (location.hasBearing() && location.speed > 2f) {
            val bearing = ((location.bearing % 360f) + 360f) % 360f
            "&bearings=${bearing.toInt()},35"
        } else ""
        val urlText =
            "https://router.project-osrm.org/nearest/v1/driving/" +
                "${location.longitude},${location.latitude}?number=5&radiuses=45${bearingOption}"

        val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2500
            readTimeout = 2500
            setRequestProperty("User-Agent", "SafeNavi/21")
        }

        try {
            if (connection.responseCode !in 200..299) {
                registerNetworkFailure(now)
                return@withContext freshFallback(location, now)
            }
            consecutiveSnapFailures = 0
            nextNetworkAttemptAt = 0L
            lastGoodSnapAt = now

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val waypoints = root.optJSONArray("waypoints") ?: return@withContext freshFallback(location, now)
            if (waypoints.length() == 0) return@withContext freshFallback(location, now)

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
                    snapDistanceMeters = distance,
                    roadBearing = null,
                    roadGeometry = emptyList()
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
                    val directionPenalty = candidate.roadBearing?.let {
                        val d = kotlin.math.abs(((location.bearing - it + 540.0) % 360.0) - 180.0)
                        d * 0.35
                    } ?: 0.0
                    candidate.snapDistanceMeters + continuity[0] * 1.8 + directionPenalty
                } ?: candidates.first()
            } else {
                candidates.minByOrNull { it.snapDistanceMeters } ?: candidates.first()
            }

            val previousDistance = previousRoad?.let { road ->
                val out = FloatArray(1)
                Location.distanceBetween(
                    location.latitude, location.longitude,
                    road.latitude, road.longitude, out
                )
                out[0].toDouble()
            } ?: Double.MAX_VALUE

            // A divided highway can put both carriageways only a few metres apart.
            // Keep the current carriageway unless the new candidate is clearly better;
            // this prevents GPS jitter from hopping between parallel directions.
            val road = previousRoad
            val sameNamedRoad = road != null &&
                !selected.roadName.isNullOrBlank() &&
                !road.roadName.isNullOrBlank() &&
                selected.roadName == road.roadName

            val unnamedButDirectionConsistent = road != null &&
                selected.roadName.isNullOrBlank() &&
                road.roadName.isNullOrBlank() &&
                road.roadBearing?.let { roadBearing ->
                    val delta = kotlin.math.abs(
                        ((location.bearing.toDouble() - roadBearing + 540.0) % 360.0) - 180.0
                    )
                    delta <= 30.0
                } == true

            val continuityLimit = if (sameNamedRoad) 28.0 else 18.0
            val keepPrevious = road != null &&
                location.speed > 3f &&
                previousDistance < continuityLimit &&
                (sameNamedRoad || unnamedButDirectionConsistent) &&
                selected.snapDistanceMeters + 18.0 >= previousDistance

            val result = if (keepPrevious) road!! else selected
            val anchor = geometryAnchor
            val anchorDistance = anchor?.let {
                val out = FloatArray(1)
                Location.distanceBetween(result.latitude, result.longitude, it.latitude, it.longitude, out)
                out[0].toDouble()
            } ?: Double.MAX_VALUE
            val sameRoad = anchor != null && result.roadName == anchor.roadName
            val cacheFresh = now - geometryFetchedAt < 8000L
            val reuseGeometry = sameRoad && anchorDistance < 65.0 && cacheFresh && anchor!!.roadGeometry.size >= 2
            val geometry = if (reuseGeometry) anchor!!.roadGeometry else fetchLocalGeometry(result, location)
            val enriched = if (geometry.isNotEmpty()) {
                result.copy(
                    roadBearing = geometryBearing(geometry, location.bearing.toDouble()),
                    roadGeometry = geometry
                )
            } else result
            if (!reuseGeometry && enriched.roadGeometry.size >= 2) {
                geometryAnchor = enriched
                geometryFetchedAt = now
            }
            stableResult = enriched
            enriched.also { lastResult = it }
        } catch (_: Exception) {
            registerNetworkFailure(now)
            freshFallback(location, now)
        } finally {
            connection.disconnect()
        }
    }

    private fun freshFallback(location: Location, now: Long): SnappedRoadPoint? {
        val cached = lastResult ?: return null
        if (lastGoodSnapAt <= 0L || now - lastGoodSnapAt > 3_000L) return null

        val moved = FloatArray(1)
        Location.distanceBetween(
            location.latitude, location.longitude,
            cached.latitude, cached.longitude, moved
        )
        return cached.takeIf { moved[0] <= 15f }
    }

    private fun registerNetworkFailure(now: Long) {
        consecutiveSnapFailures = (consecutiveSnapFailures + 1).coerceAtMost(5)
        val delay = when (consecutiveSnapFailures) {
            1 -> 1_500L
            2 -> 3_000L
            3 -> 6_000L
            else -> 10_000L
        }
        nextNetworkAttemptAt = now + delay
    }

    private fun fetchLocalGeometry(point: SnappedRoadPoint, location: Location): List<Pair<Double, Double>> {
        val heading = if (location.hasBearing()) location.bearing.toDouble() else 0.0
        val back = destination(point.latitude, point.longitude, heading + 180.0, 70.0)
        val front = destination(point.latitude, point.longitude, heading, 140.0)
        val urlText = "https://router.project-osrm.org/route/v1/driving/" +
            "${back.second},${back.first};${front.second},${front.first}" +
            "?overview=full&geometries=geojson&steps=false"
        val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 1800; readTimeout = 1800
            setRequestProperty("User-Agent", "SafeNavi/32")
        }
        return try {
            if (connection.responseCode !in 200..299) emptyList() else {
                val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val coords = root.optJSONArray("routes")?.optJSONObject(0)
                    ?.optJSONObject("geometry")?.optJSONArray("coordinates")
                    ?: return emptyList()
                (0 until coords.length()).mapNotNull { i ->
                    val p = coords.optJSONArray(i) ?: return@mapNotNull null
                    p.optDouble(1) to p.optDouble(0)
                }
            }
        } catch (_: Exception) { emptyList() } finally { connection.disconnect() }
    }

    private fun geometryBearing(points: List<Pair<Double, Double>>, fallback: Double): Double {
        if (points.size < 2) return fallback
        val a = points[points.size / 3]
        val b = points[(points.size * 2 / 3).coerceAtMost(points.lastIndex)]
        val out = FloatArray(2)
        Location.distanceBetween(a.first, a.second, b.first, b.second, out)

        // Route geometry can be returned in either direction. Normalize it to
        // the direction of travel so camera filtering never treats the same
        // road geometry as the opposite carriageway solely because its point
        // order is reversed.
        var bearing = ((out[1].toDouble() % 360.0) + 360.0) % 360.0
        val travel = ((fallback % 360.0) + 360.0) % 360.0
        val delta = kotlin.math.abs(((bearing - travel + 540.0) % 360.0) - 180.0)
        if (delta > 90.0) {
            bearing = (bearing + 180.0) % 360.0
        }
        return bearing
    }

    private fun destination(lat: Double, lon: Double, bearing: Double, meters: Double): Pair<Double, Double> {
        val r = 6371000.0
        val d = meters / r
        val t = Math.toRadians(bearing)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = kotlin.math.asin(kotlin.math.sin(p1) * kotlin.math.cos(d) + kotlin.math.cos(p1) * kotlin.math.sin(d) * kotlin.math.cos(t))
        val l2 = l1 + kotlin.math.atan2(kotlin.math.sin(t) * kotlin.math.sin(d) * kotlin.math.cos(p1), kotlin.math.cos(d) - kotlin.math.sin(p1) * kotlin.math.sin(p2))
        return Math.toDegrees(p2) to Math.toDegrees(l2)
    }
}
