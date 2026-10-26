package io.github.alexskuznetsov.xrspatial

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandSampleTest {
    private fun sample(result: Int = 0, active: Boolean = true, palmFlags: Long = 3, tipFlags: Long = 15,
                       palm: Vector3 = Vector3(-0.2f, -0.3f, -0.4f)) =
        HandSample(1, 10, 16666667, Hand.LEFT, result, active, palmFlags, palm, tipFlags, Vector3(-0.18f, -0.22f, -0.5f))

    @Test fun validNeedsSuccessActiveAndPositionBit() {
        assertTrue(sample().palmValid)
        assertTrue(sample().indexTipValid)
        assertFalse(sample(result = -30).palmValid)
        assertFalse(sample(active = false).palmValid)
        assertFalse(sample(palmFlags = 1).palmValid)  // orientation only
        assertTrue(sample(palmFlags = 1).indexTipValid)  // joints are independent
    }

    @Test fun nonFiniteRawPositionIsNotValid() {
        assertFalse(sample(palm = Vector3(Float.NaN, 0f, 0f)).palmValid)
    }
}
