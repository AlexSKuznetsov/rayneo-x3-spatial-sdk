package io.github.alexskuznetsov.xrspatial

import org.junit.Assert.assertEquals
import org.junit.Test

class TimingStatsTest {
    @Test fun countsMeanMaxAndLast() {
        val stats = TimingStats()
        assertEquals("n=0", stats.toString())
        stats.record(1_000_000L)
        stats.record(4_000_000L)
        stats.record(1_000_000L)
        assertEquals(3L, stats.count)
        assertEquals(2_000_000L, stats.meanNs)
        assertEquals(4_000_000L, stats.maxNs)
        assertEquals(1_000_000L, stats.lastNs)
        assertEquals("n=3 mean=2.00ms max=4.00ms last=1.00ms", stats.toString())
        stats.reset()
        assertEquals("n=0", stats.toString())
    }
}
