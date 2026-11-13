package com.kimyubin33.audioapp.data.location

import kotlin.math.*

/** Listening areas, independent of a creator's precise location/visit radius. */
data class AudioRegion(val id: String, val title: String, val eastMeters: Double,
    val northMeters: Double, val radiusMeters: Int, val contentIds: List<String>)

data class RegionPoint(val id: String, val title: String, val latitude: Double,
    val longitude: Double, val authoredRadius: Int)

fun audioRegions(points: List<RegionPoint>, fix: LocationSample): List<AudioRegion> {
    val remaining = points.sortedBy { it.id }.toMutableList()
    val groups = mutableListOf<AudioRegion>()
    while (remaining.isNotEmpty()) {
        val anchor = remaining.removeAt(0)
        val members = listOf(anchor) + remaining.filter {
            distanceMeters(anchor.latitude, anchor.longitude, it.latitude, it.longitude) <= 1500
        }
        remaining.removeAll(members.toSet())
        val extent = members.maxOf { distanceMeters(anchor.latitude, anchor.longitude, it.latitude, it.longitude) + it.authoredRadius }
        val radius = maxOf(1500.0, extent, 1500.0 + (members.size - 1) * 350).coerceAtMost(5000.0).roundToInt()
        val deltaLon = ((anchor.longitude - fix.longitude + 540) % 360) - 180
        groups += AudioRegion(anchor.id, anchor.title,
            deltaLon * 111_195 * cos(Math.toRadians(fix.latitude)),
            (anchor.latitude - fix.latitude) * 111_195, radius, members.map { it.id })
    }
    return groups.sortedBy { hypot(it.eastMeters, it.northMeters) }.take(12)
}

fun AudioRegion.contains(accuracyMeters: Float): Boolean =
    accuracyMeters.isFinite() && accuracyMeters > 0 &&
        hypot(eastMeters, northMeters) + accuracyMeters <= radiusMeters
