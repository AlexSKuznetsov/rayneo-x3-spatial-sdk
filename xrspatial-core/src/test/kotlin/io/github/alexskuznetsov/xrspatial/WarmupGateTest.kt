package io.github.alexskuznetsov.xrspatial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Synthetic sequences shaped like the device start-up patterns. NOT device evidence. */
class WarmupGateTest {
    private val frameNs = 16_666_667L
    private var index = 0L

    private fun pose(t: Long, x: Float = 0f, yawDeg: Double = 0.0, flags: Long = 15L, qw: Float? = null, px: Float? = null): PoseSample {
        val half = Math.toRadians(yawDeg) / 2
        return PoseSample(1, index++, 16666667L, t, flags, px ?: x, -0.008f, 0.025f,
            0f, Math.sin(half).toFloat(), 0f, qw ?: Math.cos(half).toFloat())
    }

    private fun run(gate: WarmupGate, samples: List<PoseSample>) = samples.map { gate.classify(it) }

    @Test fun cleanStreamBecomesUsableAfterWindow() {
        val q = run(WarmupGate(), (0 until 30).map { pose(it * frameNs, x = it * 1e-4f) })
        assertEquals(SampleQuality.WARMING_UP, q.first())
        val firstUsable = q.indexOf(SampleQuality.USABLE)
        assertEquals(18, firstUsable) // 18 frames = 300 ms after the first clean sample
        assertTrue(q.drop(firstUsable).all { it == SampleQuality.USABLE })
    }

    @Test fun firstSampleAloneIsNeverStaleButRepeatsAre() {
        val q = run(WarmupGate(), List(5) { pose(it * frameNs) })
        assertEquals(SampleQuality.WARMING_UP, q[0])
        assertEquals(List(4) { SampleQuality.STALE_START }, q.drop(1))
    }

    @Test fun deviceLikeStartupNeverYieldsGarbageAsUsable() {
        // previous-session pose held, non-finite, zero quaternion, one wild pose, then real data
        val stale = List(20) { pose(it * frameNs, x = 0.3f, yawDeg = -79.0) }
        val nonFinite = List(17) { pose((20 + it) * frameNs, px = Float.NaN) }
        val zeroQ = List(5) { pose((37 + it) * frameNs, qw = 0f).copy(qy = 0f) }
        val wild = listOf(pose(42 * frameNs, x = 0.01f, yawDeg = 67.0))
        val real = List(60) { pose((43 + it) * frameNs, x = it * 1e-4f) }
        val samples = stale + nonFinite + zeroQ + wild + real
        val q = run(WarmupGate(), samples)
        assertEquals(List(19) { SampleQuality.STALE_START }, q.subList(1, 20))
        assertTrue(q.subList(20, 37).all { it == SampleQuality.NON_FINITE })
        assertTrue(q.subList(37, 42).all { it == SampleQuality.NON_UNIT_QUATERNION })
        assertEquals(SampleQuality.WARMING_UP, q[42])
        assertEquals(SampleQuality.JUMP, q[43]) // first real pose jumps from the wild one
        val firstUsable = q.indexOf(SampleQuality.USABLE)
        assertTrue(firstUsable > 43)
        assertTrue(samples[firstUsable].hostReceiptNs - samples[44].hostReceiptNs >= 300_000_000L)
    }

    @Test fun jumpAfterUsableRestartsWarmup() {
        val samples = List(30) { pose(it * frameNs, x = it * 1e-4f) } + pose(30 * frameNs, x = 0.5f) +
            List(30) { pose((31 + it) * frameNs, x = 0.5f + it * 1e-4f) }
        val q = run(WarmupGate(), samples)
        assertEquals(SampleQuality.USABLE, q[29])
        assertEquals(SampleQuality.JUMP, q[30])
        assertEquals(SampleQuality.WARMING_UP, q[31])
        assertEquals(SampleQuality.USABLE, q.last())
    }

    @Test fun persistentOffsetAfterJumpIsAcceptedAfterTheWindowAndFlaggedDegraded() {
        // Device-like: ~0.4 m position jump that stays (seen after a ~90 deg head turn). The gate recovers by
        // design, so the shifted frame becomes USABLE after 300 ms; ConfidenceTracker keeps it DEGRADED for 2 s.
        val before = List(40) { pose(it * frameNs).copy(px = it * 1e-4f) }
        val after = List(150) { pose((40 + it) * frameNs).copy(px = 0.4f + it * 1e-4f) }
        val gate = WarmupGate()
        val tracker = ConfidenceTracker()
        val samples = before + after
        val q = samples.map { gate.classify(it) }
        val c = samples.zip(q).map { (s, quality) -> tracker.update(s, quality) }
        assertEquals(SampleQuality.USABLE, q[39])
        assertEquals(TrackingConfidence.NORMAL, c[39])
        assertEquals(SampleQuality.JUMP, q[40])
        assertTrue(q.subList(41, 58).all { it == SampleQuality.WARMING_UP })  // no content for ~300 ms
        assertEquals(SampleQuality.USABLE, q[59])                              // offset accepted
        assertEquals(0.4f, samples[59].px, 0.01f)
        assertTrue(c.subList(40, 40 + 119).all { it == TrackingConfidence.DEGRADED })  // 2 s after the jump
        assertEquals(TrackingConfidence.NORMAL, c.last())
        assertEquals(1, tracker.jumps)
    }

    @Test fun startupJumpsDoNotDegradeConfidence() {
        // Seen on the glasses: every session's start-up wild pose is a JUMP; it must not make
        // the new session DEGRADED.
        val tracker = ConfidenceTracker()
        val gate = WarmupGate()
        val samples = List(5) { pose(it * frameNs, x = it * 1e-4f) } + pose(5 * frameNs, x = 0.5f, yawDeg = 60.0) +
            List(40) { pose((6 + it) * frameNs, x = 0.5f + it * 1e-4f, yawDeg = 60.0) }
        val c = samples.map { tracker.update(it, gate.classify(it)) }
        assertTrue(TrackingConfidence.DEGRADED !in c)
        assertEquals(TrackingConfidence.NORMAL, c.last())
        assertEquals(0, tracker.jumps)
    }

    @Test fun confidenceIsNoneWhileWarmingUp() {
        val tracker = ConfidenceTracker()
        val s = pose(0)
        assertEquals(TrackingConfidence.NONE, tracker.update(s, SampleQuality.WARMING_UP))
        assertEquals(TrackingConfidence.NONE, tracker.update(s, SampleQuality.NON_FINITE))
        assertEquals(TrackingConfidence.NORMAL, tracker.update(s, SampleQuality.USABLE))
        assertEquals(null, tracker.lastJumpNs)
    }

    @Test fun incompleteFlagsAreNeverUsable() {
        val q = run(WarmupGate(), List(40) { pose(it * frameNs, x = it * 1e-4f, flags = 7L) })
        assertTrue(q.all { it == SampleQuality.FLAGS_INCOMPLETE })
    }

    @Test fun fastButPlausibleHeadTurnIsNotAJump() {
        // 160 deg/s, the fastest head turn seen in the device runs
        val q = run(WarmupGate(), List(40) { pose(it * frameNs, yawDeg = it * 160.0 * frameNs / 1e9, x = it * 1e-4f) })
        assertTrue(SampleQuality.JUMP !in q)
    }
}
