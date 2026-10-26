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

    @Volatile var current: Versioned<T>? = null
        private set

    fun publish(value: T): Versioned<T> = Versioned(value, versions.incrementAndGet()).also { current = it }
}
