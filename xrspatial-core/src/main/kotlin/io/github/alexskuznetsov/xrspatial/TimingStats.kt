package io.github.alexskuznetsov.xrspatial

import java.util.Locale

/**
 * Durations or intervals (nanoseconds) for diagnostics: how many, mean, max and the last one. Record from one
 * thread, read from any. Host CPU times only; they say nothing about GPU or display timing.
 */
class TimingStats {
    private var n = 0L
    private var total = 0L
    private var max = 0L
    private var last = 0L

    @Synchronized fun record(ns: Long) {
        n++
        total += ns
        if (ns > max) max = ns
        last = ns
    }

    @Synchronized fun reset() { n = 0; total = 0; max = 0; last = 0 }

    val count: Long @Synchronized get() = n
    val meanNs: Long @Synchronized get() = if (n == 0L) 0L else total / n
    val maxNs: Long @Synchronized get() = max
    val lastNs: Long @Synchronized get() = last

    /** For example `n=120 mean=1.25ms max=4.10ms last=1.02ms`, or `n=0`. */
    @Synchronized override fun toString(): String =
        if (n == 0L) "n=0"
        else String.format(Locale.ROOT, "n=%d mean=%.2fms max=%.2fms last=%.2fms", n, total / n / 1e6, max / 1e6, last / 1e6)
}
