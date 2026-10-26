package io.github.alexskuznetsov.xrspatial.scene

import java.util.concurrent.atomic.AtomicInteger

/** A value and its version, published as one object so a reader never pairs one with another's. */
internal class Versioned<T : Any>(val value: T, val version: Int)

/**
 * The latest content of a node: [publish] from any thread, [current] read by the renderer. Each publication gets
 * a new version; with concurrent publishers the last write wins, and its value and version always belong
 * together.
 */
internal class ContentSlot<T : Any> {
    private val versions = AtomicInteger()
    @Volatile private var consumedVersion = 0
    @Volatile private var previous: Versioned<T>? = null

    @Volatile var current: Versioned<T>? = null
        private set

    fun publish(value: T): Versioned<T> = Versioned(value, versions.incrementAndGet()).also {
        previous = current
        current = it
    }

    /**
     * The renderer is done with every version up to [version] it read from [current] (it uploaded it). Snapshots
     * are read in order on one thread, so after this no reader holds anything older than [version].
     */
    fun consumed(version: Int) {
        if (version > consumedVersion) consumedVersion = version
    }

    /**
     * The value published just before [current], when the reader has already consumed a newer version, so nobody
     * can still be reading it: the publisher may overwrite and publish it again. Null otherwise. Only safe with a
     * single publishing thread (a [SpatialPanel] publishes on the main thread).
     */
    fun reusable(): T? = previous?.takeIf { consumedVersion > it.version }?.value
}
