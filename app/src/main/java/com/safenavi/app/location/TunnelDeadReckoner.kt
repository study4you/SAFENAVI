package com.safenavi.app.location

import android.location.Location
import kotlin.math.*

/**
 * Short GPS-outage dead reckoning for tunnels/underground roads.
 * Keeps the last trusted travel line and speed for at most 30 seconds.
 * This is intentionally bounded because phone-only inertial navigation drifts.
 */
class TunnelDeadReckoner {
    private var trusted: Location? = null
    private var headingDeg = 0.0
    private var speedMps = 0.0
    private var lastUpdateMs = 0L
    private var outageStartMs = 0L

    fun acceptGps(location: Location): Location {
        val now = location.elapsedRealtimeNanos.takeIf { it > 0L }?.div(1_000_000L)
            ?: android.os.SystemClock.elapsedRealtime()
        val good = location.accuracy <= 35f
        if (good) {
            trusted = Location(location)
            if (location.hasBearing() && location.speed > 1.5f) headingDeg = location.bearing.toDouble()
            if (location.hasSpeed()) speedMps = location.speed.toDouble().coerceAtLeast(0.0)
            lastUpdateMs = now
            outageStartMs = 0L
            return Location(location)
        }
        return predict(now) ?: Location(location)
    }

    fun predict(nowMs: Long = android.os.SystemClock.elapsedRealtime()): Location? {
        val base = trusted ?: return null
        if (lastUpdateMs == 0L || speedMps < 1.0) return Location(base)
        if (outageStartMs == 0L) outageStartMs = nowMs
        if (nowMs - outageStartMs > 30_000L) return Location(base)

        val dt = ((nowMs - lastUpdateMs).coerceIn(0L, 1500L)) / 1000.0
        if (dt <= 0.0) return Location(base)
        val next = move(base, headingDeg, speedMps * dt)
        next.speed = speedMps.toFloat()
        next.bearing = headingDeg.toFloat()
        trusted = Location(next)
        lastUpdateMs = nowMs
        return next
    }

    fun adjustHeading(sensorHeading: Float) {
        if (outageStartMs == 0L) return
        var delta = (sensorHeading - headingDeg + 540.0) % 360.0 - 180.0
        delta = delta.coerceIn(-8.0, 8.0)
        headingDeg = (headingDeg + delta * 0.12 + 360.0) % 360.0
    }

    private fun move(from: Location, bearing: Double, meters: Double): Location {
        val r = 6371000.0
        val d = meters / r
        val t = Math.toRadians(bearing)
        val p1 = Math.toRadians(from.latitude)
        val l1 = Math.toRadians(from.longitude)
        val p2 = asin(sin(p1)*cos(d)+cos(p1)*sin(d)*cos(t))
        val l2 = l1 + atan2(sin(t)*sin(d)*cos(p1), cos(d)-sin(p1)*sin(p2))
        return Location(from).apply {
            latitude = Math.toDegrees(p2)
            longitude = Math.toDegrees(l2)
            accuracy = max(from.accuracy, 35f)
        }
    }
}
