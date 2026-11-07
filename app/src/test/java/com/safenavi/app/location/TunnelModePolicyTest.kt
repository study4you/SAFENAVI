package com.safenavi.app.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelModePolicyTest {
    @Test
    fun trustsGpsAtThirtyFiveMeters() {
        assertFalse(TunnelModePolicy.isPredicted(35f))
    }

    @Test
    fun marksAccuracyWorseThanThirtyFiveMetersAsPrediction() {
        assertTrue(TunnelModePolicy.isPredicted(35.1f))
        assertTrue(TunnelModePolicy.isPredicted(36f))
    }

    @Test
    fun predictedFixAlwaysStaysOutsideTrustedAccuracyRange() {
        assertTrue(TunnelModePolicy.isPredicted(TunnelModePolicy.predictedAccuracy(4f)))
        assertTrue(TunnelModePolicy.isPredicted(TunnelModePolicy.predictedAccuracy(35f)))
    }
}
