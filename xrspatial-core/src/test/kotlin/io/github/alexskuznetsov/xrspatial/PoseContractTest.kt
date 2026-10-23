package io.github.alexskuznetsov.xrspatial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoseContractTest {
    private fun sample(flags: Long = 15L) = PoseSample(
        sessionEpoch = 1, frameIndex = 0, xrTime = 16666667L, hostReceiptNs = 1_000L, locationFlags = flags,
        px = 0f, py = 0f, pz = 0f, qx = 0f, qy = 0f, qz = 0f, qw = 1f,
    )

    @Test fun flagsAreIndependentBits() {
        val s = sample(flags = PoseSample.ORIENTATION_VALID or PoseSample.POSITION_TRACKED)
        assertTrue(s.orientationValid)
        assertFalse(s.positionValid)
        assertFalse(s.orientationTracked)
        assertTrue(s.positionTracked)
        assertFalse(s.allFourFlags)
        assertTrue(sample().allFourFlags)
    }

    @Test fun xrTimeIsKeptRawAndSeparateFromReceiptTime() {
        val s = sample()
        assertEquals(16666667L, s.xrTime)
        assertEquals(1_000L, s.hostReceiptNs)
    }

    @Test fun rawValuesAreNotNormalisedOrRepaired() {
        val s = sample().copy(px = Float.NaN, qw = 2f)
        assertFalse(s.finite)
        assertTrue(s.px.isNaN())
        assertEquals(2.0, s.quaternionNorm, 1e-12)
    }

    @Test fun sameRawPoseComparesBits() {
        assertTrue(sample().sameRawPose(sample().copy(frameIndex = 5, hostReceiptNs = 9)))
        assertFalse(sample().sameRawPose(sample().copy(px = Math.nextUp(0f))))
        assertFalse(sample().sameRawPose(sample().copy(px = -0f)))
    }

    @Test fun fakeProviderIsTestOnlyAndHasNoPoseWhenStopped() {
        val fake = object : SpatialTracking {
            override fun start(listener: PoseListener): Result<Unit> = Result.failure(SpatialError.SessionUnsupported("test", -1))
            override fun stop() = Unit
            override fun latestUsable(): PoseSample? = null
            override fun diagnostics() = mapOf("scope" to "TEST_ONLY")
        }
        val error = fake.start(object : PoseListener { override fun onSample(sample: PoseSample, quality: SampleQuality) = Unit })
            .exceptionOrNull() as SpatialError
        assertEquals("test", error.operation)
        assertEquals(-1, error.resultCode)
        assertNull(fake.latestUsable())
    }
}
