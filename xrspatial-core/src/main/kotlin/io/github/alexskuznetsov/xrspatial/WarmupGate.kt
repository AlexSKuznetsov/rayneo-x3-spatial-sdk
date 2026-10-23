package io.github.alexskuznetsov.xrspatial

/** Why a sample may or may not be used. Only [USABLE] samples should drive application content. */
enum class SampleQuality {
    USABLE,
    /** Sane so far, but the current run of clean samples is shorter than the warm-up window. */
    WARMING_UP,
    /** Bit-identical to the session's first sample (observed: the previous session's last pose). */
    STALE_START,
    /** Not all four location flag bits set. */
    FLAGS_INCOMPLETE,
    /** NaN/Inf in position or orientation (observed with flags 15 during start-up). */
    NON_FINITE,
    /** |q| further than [WarmupGate.Config.unitTolerance] from 1 (observed: zero quaternions, ~1e24 values). */
    NON_UNIT_QUATERNION,
    /** Step from the previous finite sample exceeds the configured head speed limits. */
    JUMP,
}

/**
 * Stability-based gate replacing a fixed warm-up time. One instance per session ([PoseSample.sessionEpoch]);
 * feed every sample in order.
 *
 * Device basis (X3 Pro, runtime 1.1.7.9): each session starts with the previous session's last pose
 * (0–0.35 s), then non-finite or non-unit poses (to 0.6–1.35 s), sometimes one wild pose, all with flags 15;
 * see docs/x3-pro-runtime-notes.md. A sample becomes [SampleQuality.USABLE] only after an uninterrupted run
 * of clean samples lasting [Config.windowNs] and [Config.minSamples].
 *
 * Checked against the authors' device recordings: first USABLE at 0.90–1.70 s, always after the last
 * start-up garbage, and no rejection during ordinary head and body motion.
 * Limits: the gate cannot see errors that look like plausible motion (for example a slow position drift).
 */
class WarmupGate(private val config: Config = Config()) {
    data class Config(
        val windowNs: Long = 300_000_000L,
        val minSamples: Int = 10,
        val unitTolerance: Double = 1e-3,
        val maxLinearSpeedMps: Double = 4.0,
        val linearSlackM: Double = 0.02,
        val maxAngularSpeedDegPerS: Double = 1000.0,
        val angularSlackDeg: Double = 5.0,
    )

    private var first: PoseSample? = null
    private var staleOver = false
    private var previous: PoseSample? = null
    private var cleanSinceNs: Long? = null
    private var cleanCount = 0

    fun classify(sample: PoseSample): SampleQuality {
        val firstSample = first ?: sample.also { first = it }
        val quality = when {
            !sample.allFourFlags -> SampleQuality.FLAGS_INCOMPLETE
            !sample.finite -> SampleQuality.NON_FINITE
            Math.abs(sample.quaternionNorm - 1.0) > config.unitTolerance -> SampleQuality.NON_UNIT_QUATERNION
            !staleOver && sample !== firstSample && sample.sameRawPose(firstSample) -> SampleQuality.STALE_START
            jumped(sample) -> SampleQuality.JUMP
            else -> null
        }
        if (sample.finite && !sample.sameRawPose(firstSample)) staleOver = true
        if (quality != null) {
            cleanSinceNs = null
            cleanCount = 0
            // A finite but rejected pose is no reference for the next speed check.
            previous = if (quality == SampleQuality.JUMP) sample else null
            return quality
        }
        previous = sample
        val since = cleanSinceNs ?: sample.hostReceiptNs.also { cleanSinceNs = it }
        cleanCount++
        return if (sample.hostReceiptNs - since >= config.windowNs && cleanCount >= config.minSamples) {
            SampleQuality.USABLE
        } else {
            SampleQuality.WARMING_UP
        }
    }

    private fun jumped(sample: PoseSample): Boolean {
        val prev = previous ?: return false
        val dt = maxOf(sample.hostReceiptNs - prev.hostReceiptNs, 1_000_000L) / 1e9
        val dx = sample.px.toDouble() - prev.px
        val dy = sample.py.toDouble() - prev.py
        val dz = sample.pz.toDouble() - prev.pz
        val distance = Math.sqrt(dx * dx + dy * dy + dz * dz)
        if (distance > config.maxLinearSpeedMps * dt + config.linearSlackM) return true
        return angleDeg(prev, sample) > config.maxAngularSpeedDegPerS * dt + config.angularSlackDeg
    }

    private fun angleDeg(a: PoseSample, b: PoseSample): Double {
        val dot = (a.qx.toDouble() * b.qx + a.qy.toDouble() * b.qy + a.qz.toDouble() * b.qz + a.qw.toDouble() * b.qw) /
            (a.quaternionNorm * b.quaternionNorm)
        return Math.toDegrees(2 * Math.acos(Math.min(1.0, Math.abs(dot))))
    }
}
