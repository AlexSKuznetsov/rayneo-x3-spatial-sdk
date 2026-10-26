package io.github.alexskuznetsov.xrspatial

/**
 * How far an app should trust world-locked positions right now. Derived from the [SampleQuality] history: the
 * X3 Pro runtime reports no tracking loss (its flags read 15 even on garbage), so a recent [SampleQuality.JUMP]
 * is the only available sign that the coordinate frame may have shifted.
 */
enum class TrackingConfidence {
    /** No usable pose at the moment (session start-up, or the warm-up after a rejected sample). */
    NONE,
    /** Usable poses and no jump in the recent past. */
    NORMAL,
    /**
     * A jump was seen within [ConfidenceTracker.degradedNs]. [WarmupGate] accepts the new position after its
     * warm-up window (so tracking can recover), which means world-locked content may now be shown offset. Apps can
     * hide such content, show a hint, or recenter.
     */
    DEGRADED,
}

/** Turns per-sample qualities into a [TrackingConfidence]. One instance per session; feed every sample in order. */
class ConfidenceTracker(val degradedNs: Long = 2_000_000_000L) {
    /** [PoseSample.hostReceiptNs] of the latest [SampleQuality.JUMP] in this session, or null. */
    var lastJumpNs: Long? = null
        private set
    var jumps = 0
        private set

    fun update(sample: PoseSample, quality: SampleQuality): TrackingConfidence {
        if (quality == SampleQuality.JUMP) {
            lastJumpNs = sample.hostReceiptNs
            jumps++
        }
        val recentJump = lastJumpNs?.let { sample.hostReceiptNs - it < degradedNs } ?: false
        return when {
            recentJump -> TrackingConfidence.DEGRADED
            quality == SampleQuality.USABLE -> TrackingConfidence.NORMAL
            else -> TrackingConfidence.NONE
        }
    }
}
