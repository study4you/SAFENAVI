package com.safenavi.app.safety

import com.safenavi.app.data.SafetyPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyEngineTest {
    private val engine = SafetyEngine()

    private fun camera(
        id: Long,
        lat: Double,
        lon: Double,
        type: String = "SPEED",
        road: String? = "테스트로",
        direction: Double? = null
    ) = SafetyPoint(
        id = id,
        region = "SEOUL",
        latitude = lat,
        longitude = lon,
        type = type,
        speedLimit = 60,
        roadName = road,
        locationName = null,
        direction = direction,
        sectionType = null,
        sectionLength = null,
        protectedArea = false,
        dataDate = null
    )

    @Test
    fun acceptsCameraAheadOnCurrentRoad() {
        val result = engine.findAhead(
            37.5000, 127.0000, 0.0,
            listOf(camera(1, 37.5040, 127.0000)),
            "테스트로"
        )
        assertEquals(1, result.size)
    }

    @Test
    fun rejectsCameraBehindVehicle() {
        val result = engine.findAhead(
            37.5000, 127.0000, 0.0,
            listOf(camera(1, 37.4960, 127.0000)),
            "테스트로"
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun rejectsWrongRoadNameWhenBothKnown() {
        val result = engine.findAhead(
            37.5000, 127.0000, 0.0,
            listOf(camera(1, 37.5040, 127.0000, road = "다른로")),
            "테스트로"
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun rejectsOppositeDirectionMetadata() {
        val result = engine.findAhead(
            37.5000, 127.0000, 0.0,
            listOf(camera(1, 37.5040, 127.0000, direction = 180.0)),
            "테스트로"
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun rejectsNearbyParallelRoadByGeometry() {
        val geometry = listOf(
            37.4995 to 127.0000,
            37.5015 to 127.0000
        )
        val result = engine.findAhead(
            37.5000, 127.0000, 0.0,
            listOf(camera(1, 37.5010, 127.0004, road = null)),
            null,
            geometry
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun localGeometryDoesNotHideFarValidCamera() {
        val geometry = listOf(
            37.4995 to 127.0000,
            37.5010 to 127.0000
        )
        val result = engine.findAhead(
            37.5000, 127.0000, 0.0,
            listOf(camera(1, 37.5080, 127.0000)),
            "테스트로",
            geometry
        )
        assertEquals(1, result.size)
    }

    @Test
    fun collapsesNearDuplicateCameras() {
        val result = engine.findAhead(
            37.5000, 127.0000, 0.0,
            listOf(
                camera(1, 37.5040, 127.0000),
                camera(2, 37.5041, 127.0000)
            ),
            "테스트로"
        )
        assertEquals(1, result.size)
    }
}
