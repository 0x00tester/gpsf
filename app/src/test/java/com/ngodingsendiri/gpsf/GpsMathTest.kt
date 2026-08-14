package com.ngodingsendiri.gpsf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.random.Random

/**
 * Tes deterministik untuk GpsMath (tidak bergantung Android, berjalan di JVM:
 * ./gradlew testDebugUnitTest).
 */
class GpsMathTest {

    @Test
    fun normalizeLongitude_keepsValidValuesUnchanged() {
        assertEquals(0.0, GpsMath.normalizeLongitude(0.0), 1e-9)
        assertEquals(180.0, GpsMath.normalizeLongitude(180.0), 1e-9)
        assertEquals(-180.0, GpsMath.normalizeLongitude(-180.0), 1e-9)
        assertEquals(106.8166, GpsMath.normalizeLongitude(106.8166), 1e-9)
    }

    @Test
    fun normalizeLongitude_wrapsOutsideRange() {
        assertEquals(-179.0, GpsMath.normalizeLongitude(181.0), 1e-9)
        assertEquals(179.0, GpsMath.normalizeLongitude(-181.0), 1e-9)
        assertEquals(180.0, GpsMath.normalizeLongitude(540.0), 1e-9)
        assertEquals(-180.0, GpsMath.normalizeLongitude(-540.0), 1e-9)
        assertEquals(0.0, GpsMath.normalizeLongitude(360.0), 1e-9)
        assertEquals(0.0, GpsMath.normalizeLongitude(-360.0), 1e-9)
    }

    @Test
    fun haversine_zeroDistance() {
        assertEquals(0.0, GpsMath.haversineMeters(-6.2, 106.8166, -6.2, 106.8166), 1e-6)
    }

    @Test
    fun haversine_oneDegreeLatitude() {
        // 1° lintang = π/180 × 6.371.000 m ≈ 111.194,9 m
        val expected = PI / 180.0 * 6_371_000.0
        assertEquals(expected, GpsMath.haversineMeters(0.0, 0.0, 1.0, 0.0), 0.5)
    }

    @Test
    fun haversine_smallStep() {
        // 0.001° lintang ≈ 111,2 m
        assertEquals(111.19, GpsMath.haversineMeters(0.0, 0.0, 0.001, 0.0), 0.1)
    }

    @Test
    fun bearing_cardinalDirections() {
        assertEquals(0.0, GpsMath.bearingDegrees(0.0, 0.0, 1.0, 0.0), 1e-6)      // utara
        assertEquals(90.0, GpsMath.bearingDegrees(0.0, 0.0, 0.0, 1.0), 1e-6)     // timur
        assertEquals(180.0, GpsMath.bearingDegrees(0.0, 0.0, -1.0, 0.0), 1e-6)   // selatan
        assertEquals(270.0, GpsMath.bearingDegrees(0.0, 0.0, 0.0, -1.0), 1e-6)   // barat
    }

    @Test
    fun walk_stepStaysBoundedAroundPin() {
        var lat = -6.2
        var lng = 106.8166
        var heading = 0.7
        val rng = Random(42) // seed tetap -> deterministik
        val radius = 50.0
        repeat(2000) {
            heading += (rng.nextDouble() - 0.5) * 0.30
            val stepMeters = 0.3 + rng.nextDouble() * 1.2
            val step = GpsMath.nextWalkStep(
                lat, lng, heading, -6.2, 106.8166, stepMeters, radius
            )
            lat = step.lat
            lng = step.lng
            heading = step.headingRad
            val dist = GpsMath.haversineMeters(-6.2, 106.8166, lat, lng)
            // Batas lunak: boleh melewati radius maksimal satu langkah sebelum dikoreksi.
            assertTrue("jarak ${dist}m melebihi radius", dist <= radius + 1.6)
        }
    }

    @Test
    fun walk_headingCorrectedWhenOutsideRadius() {
        // Mulai 100 m di utara pin dengan heading ke utara (menjauh) -> langkah berikutnya
        // harus dikoreksi kembali ke arah pin (bergerak ke selatan, heading ≈ π).
        val startLat = -6.2 + 100.0 / 111_320.0
        val step = GpsMath.nextWalkStep(
            lat = startLat,
            lng = 106.8166,
            headingRad = 0.0,
            baseLat = -6.2,
            baseLng = 106.8166,
            stepMeters = 1.0,
            radiusMeters = 50.0
        )
        assertTrue(step.lat < startLat)
        assertTrue(
            kotlin.math.abs(step.headingRad - PI) < 1e-6 ||
                kotlin.math.abs(step.headingRad + PI) < 1e-6
        )
    }

    @Test
    fun walk_smallStepMovesInHeadingDirection() {
        // Heading 0 (utara) harus menambah latitude.
        val step = GpsMath.nextWalkStep(
            lat = -6.2,
            lng = 106.8166,
            headingRad = 0.0,
            baseLat = -6.2,
            baseLng = 106.8166,
            stepMeters = 1.0,
            radiusMeters = 50.0
        )
        assertTrue(step.lat > -6.2)
        assertEquals(106.8166, step.lng, 1e-9)
    }
}
