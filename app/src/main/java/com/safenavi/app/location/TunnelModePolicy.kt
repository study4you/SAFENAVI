package com.safenavi.app.location

/**
 * Shared accuracy boundary for foreground and background tunnel handling.
 * Real GPS fixes at or below 35 m are trusted; predicted fixes are marked above it.
 */
internal object TunnelModePolicy {
    const val TRUSTED_GPS_MAX_ACCURACY_METERS = 35f
    const val PREDICTED_FIX_MIN_ACCURACY_METERS = 36f

    fun isPredicted(accuracyMeters: Float): Boolean =
        !accuracyMeters.isFinite() || accuracyMeters > TRUSTED_GPS_MAX_ACCURACY_METERS

    fun predictedAccuracy(sourceAccuracyMeters: Float): Float =
        if (sourceAccuracyMeters.isFinite()) {
            maxOf(sourceAccuracyMeters, PREDICTED_FIX_MIN_ACCURACY_METERS)
        } else {
            PREDICTED_FIX_MIN_ACCURACY_METERS
        }
}
