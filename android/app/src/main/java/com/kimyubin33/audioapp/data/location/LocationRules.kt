package com.kimyubin33.audioapp.data.location

import kotlin.math.*

/** 현재 좌표는 메모리에서만 사용한다. data class의 자동 toString으로 좌표를 노출하지 않는다. */
class LocationSample(val latitude: Double, val longitude: Double, val accuracyMeters: Float, val elapsedMillis: Long) {
    fun isFresh(now: Long): Boolean = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        accuracyMeters.isFinite() && accuracyMeters > 0 && accuracyMeters <= 10_000 &&
        elapsedMillis > 0 && now - elapsedMillis in 0..60_000
    fun distanceTo(lat: Double, lon: Double): Double = distanceMeters(latitude, longitude, lat, lon)
    fun isInside(lat: Double, lon: Double, radius: Int): Boolean = distanceTo(lat, lon) + accuracyMeters <= radius
}
fun distanceMeters(a: Double, b: Double, c: Double, d: Double): Double {
    val rad = Math.PI / 180
    val h = sin((c - a) * rad / 2).pow(2) + cos(a * rad) * cos(c * rad) * sin((d - b) * rad / 2).pow(2)
    return 2 * 6371008.8 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** 서로 다른 위치 관측으로 30초 이상 체류한 것만 만남으로 센다. 좌표/이동 경로는 기록하지 않는다. */
class EncounterTracker {
    private val started = mutableMapOf<String, Long>()
    private val met = mutableSetOf<String>()
    fun update(inside: Set<String>, sampleMillis: Long): Set<String> {
        started.keys.retainAll(inside)
        for (id in inside) {
            val first = started.getOrPut(id) { sampleMillis }
            if (sampleMillis - first >= 30_000 && met.size < 500) met.add(id)
        }
        return met.toSet()
    }
    fun interrupt() { started.clear() }
    fun clear() { started.clear(); met.clear() }
}
