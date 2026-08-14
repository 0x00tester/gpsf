package com.ngodingsendiri.gpsf

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fungsi matematika murni untuk simulasi GPS — dipisahkan dari service supaya bisa
 * diuji secara deterministik (lihat GpsMathTest). Tidak bergantung pada Android.
 */
object GpsMath {

    /** Hasil satu langkah random walk beserta heading yang sudah dikoreksi. */
    data class WalkStep(val lat: Double, val lng: Double, val headingRad: Double)

    /** Bungkus longitude ke rentang [-180, 180]. */
    fun normalizeLongitude(lng: Double): Double {
        var x = lng % 360.0
        if (x > 180.0) x -= 360.0
        if (x < -180.0) x += 360.0
        return x
    }

    /** Jarak haversine dalam meter (model bola, R = 6.371 km). */
    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        // coerceAtLeast mencegah NaN akibat pembulatan float saat a nyaris > 1.
        val c = 2 * atan2(sqrt(a), sqrt((1 - a).coerceAtLeast(0.0)))
        return r * c
    }

    /** Bearing awal (0 = utara, searah jarum jam, derajat, rentang [0, 360)). */
    fun bearingDegrees(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** cos(latitude) yang dijamin != 0 agar pembagian longitude aman. */
    fun safeCosLat(lat: Double): Double {
        val c = cos(Math.toRadians(lat))
        return if (abs(c) < 1e-6) 1e-6 else c
    }

    /**
     * Satu langkah random walk: posisi bergerak [stepMeters] ke arah [headingRad]
     * (0 rad = utara, searah jarum jam). Bila jarak dari pin ([baseLat], [baseLng])
     * sudah melewati [radiusMeters], heading dikoreksi kembali ke arah pin.
     */
    fun nextWalkStep(
        lat: Double,
        lng: Double,
        headingRad: Double,
        baseLat: Double,
        baseLng: Double,
        stepMeters: Double,
        radiusMeters: Double
    ): WalkStep {
        val s = safeCosLat(lat)
        val dNorth = (lat - baseLat) * 111_320.0
        val dEast = (lng - baseLng) * 111_320.0 * s
        var h = headingRad
        if (sqrt(dNorth * dNorth + dEast * dEast) > radiusMeters) {
            h = atan2(-dEast, -dNorth)
        }
        val newLat = (lat + (stepMeters / 111_320.0) * cos(h)).coerceIn(-90.0, 90.0)
        val newLng = normalizeLongitude(lng + (stepMeters / (111_320.0 * s)) * sin(h))
        return WalkStep(newLat, newLng, h)
    }
}
