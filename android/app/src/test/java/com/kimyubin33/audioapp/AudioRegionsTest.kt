package com.kimyubin33.audioapp

import com.kimyubin33.audioapp.data.location.*
import org.junit.Assert.*
import org.junit.Test

class AudioRegionsTest {
    private val fix = LocationSample(37.0,127.0,50f,1000)
    @Test fun expandsTinyCreatorAreasAndVariesByDensity() {
        val single = audioRegions(listOf(RegionPoint("a","Park",37.0,127.0,50)),fix).single()
        assertEquals(1500,single.radiusMeters)
        val dense = audioRegions(listOf(RegionPoint("a","Park",37.0,127.0,50),
            RegionPoint("b","Walk",37.005,127.0,50)),fix).single()
        assertTrue(dense.radiusMeters > single.radiusMeters)
        assertTrue(dense.contains(50f))
        assertEquals(setOf("a","b"),dense.contentIds.toSet())
    }
    @Test fun neverExceedsFiveKmAndExcludesUncertainBoundary() {
        val region=audioRegions(listOf(RegionPoint("a","Wide",37.0,127.0,9000)),fix).single()
        assertEquals(5000,region.radiusMeters)
        assertFalse(region.copy(eastMeters=4900.0).contains(200f))
        assertFalse(region.contains(Float.NaN))
    }
    @Test fun stableGroupingUsesActualPositionsAndWrapsDateLine() {
        val points=listOf(RegionPoint("b","South",36.95,127.0,2000),RegionPoint("a","North",37.05,127.0,1500))
        assertEquals(audioRegions(points,fix),audioRegions(points.reversed(),fix))
        assertEquals(2,audioRegions(points,fix).size)
        val date=audioRegions(listOf(RegionPoint("a","Across",0.0,-179.99,1500)),LocationSample(0.0,179.99,20f,1000)).single()
        assertTrue(date.eastMeters in 2000.0..2500.0)
    }
}
