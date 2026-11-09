package com.kimyubin33.audioapp

import com.kimyubin33.audioapp.data.location.*
import org.junit.Assert.*
import org.junit.Test

class LocationRulesTest {
    @Test fun onlyRecentFinitePositionsCanDiscoverContent() {
        assertTrue(LocationSample(37.5, 127.0, 20f, 1000).isFresh(61_000))
        assertFalse(LocationSample(37.5, 127.0, 20f, 1000).isFresh(61_001))
        assertFalse(LocationSample(37.5, 127.0, 20f, 1001).isFresh(1000))
        for (s in listOf(LocationSample(Double.NaN, 0.0, 10f, 1000), LocationSample(91.0, 0.0, 10f, 1000),
            LocationSample(0.0, -181.0, 10f, 1000), LocationSample(0.0, 0.0, Float.NaN, 1000),
            LocationSample(0.0, 0.0, 10001f, 1000))) assertFalse(s.isFresh(1000))
    }
    @Test fun approximateLocationNeverClaimsAVisitOutsideItsAccuracyCircle() {
        val fix = LocationSample(37.5, 127.0, 500f, 1000)
        assertFalse(fix.isInside(37.5, 127.0, 200))
        assertTrue(fix.isInside(37.5, 127.0, 1000))
        assertTrue(distanceMeters(0.0, 179.999, 0.0, -179.999) < 223)
    }
    @Test fun encountersRequireSeparateObservationsAndResetOnDepartureOrPause() {
        val tracker = EncounterTracker()
        assertTrue(tracker.update(setOf("one"), 1000).isEmpty())
        assertTrue(tracker.update(setOf("one"), 1000).isEmpty())
        assertTrue(tracker.update(setOf("one"), 30_999).isEmpty())
        assertEquals(setOf("one"), tracker.update(setOf("one"), 31_000))
        tracker.update(setOf("two"), 40_000)
        tracker.update(emptySet(), 50_000)
        assertEquals(setOf("one"), tracker.update(setOf("two"), 70_000))
        tracker.interrupt()
        assertEquals(setOf("one"), tracker.update(setOf("two"), 100_000))
        tracker.clear()
        assertTrue(tracker.update(setOf("one"), 200_000).isEmpty())
    }
}
