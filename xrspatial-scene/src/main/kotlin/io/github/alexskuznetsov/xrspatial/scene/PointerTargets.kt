package io.github.alexskuznetsov.xrspatial.scene

/**
 * The renderer's pointer bookkeeping (no GL, unit-tested): sets [SpatialPointer.hit] each frame and tells the
 * pointer's listener when the target node changes, through [post] (the main thread), coalesced: per listener only
 * the latest change waits, and one posted task delivers everything pending. A busy main thread therefore gets the
 * current target, not a queue of old ones. Render thread only, except the delivery.
 */
internal class PointerTargets(private val post: (Runnable) -> Unit) {
    private var notifiedTarget: SpatialQuad? = null
    private var notifiedPointer: SpatialPointer? = null
    private val pending = LinkedHashMap<PointerListener, PointerHit?>()
    private var scheduled = false

    /** This frame's result for the enabled pointer [p] (null: none or disabled): [node] / [hit] or nothing. */
    fun publish(p: SpatialPointer?, node: SpatialQuad?, hit: PointerHit?) {
        p?.hit = hit
        if (node === notifiedTarget && p === notifiedPointer) return
        val previous = notifiedPointer
        val previousTarget = notifiedTarget
        notifiedTarget = node
        notifiedPointer = p
        // A pointer that was switched off or replaced while on a target hears that it has none now.
        if (previous != null && previous !== p && previousTarget != null) previous.listener?.let { send(it, null) }
        p?.listener?.let { send(it, hit) }
    }

    /** No frame is drawn (no usable pose, or the session ended): [pointer] targets nothing. */
    fun drop(pointer: SpatialPointer?) {
        pointer?.hit = null
        publish(pointer?.takeIf { it.enabled }, null, null)
    }

    private fun send(listener: PointerListener, hit: PointerHit?) {
        synchronized(pending) {
            pending.remove(listener)  // re-insert: deliveries follow the order of the latest changes
            pending[listener] = hit
            if (scheduled) return
            scheduled = true
        }
        post(Runnable { deliver() })
    }

    private fun deliver() {
        val batch = synchronized(pending) {
            scheduled = false
            pending.entries.map { it.key to it.value }.also { pending.clear() }
        }
        for ((listener, hit) in batch) listener.onTargetChanged(hit)
    }
}
