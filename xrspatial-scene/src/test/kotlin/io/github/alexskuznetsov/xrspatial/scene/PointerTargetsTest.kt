package io.github.alexskuznetsov.xrspatial.scene

import io.github.alexskuznetsov.xrspatial.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PointerTargetsTest {
    private val queue = ArrayList<Runnable>()
    private val targets = PointerTargets { queue.add(it) }
    private val seen = ArrayList<Pair<String, PointerHit?>>()

    private fun pointer(name: String) = SpatialPointer().apply { listener = PointerListener { seen.add(name to it) } }
    private fun runMain() { val tasks = queue.toList(); queue.clear(); tasks.forEach { it.run() } }

    // A quad without content: the bookkeeping only compares node identity.
    private class Node : SpatialQuad(Placement.InFrontOfUser(), 0.3f)
    private val card = Node()
    private val other = Node()
    private fun hit(node: SpatialQuad, frame: Long) = PointerHit(node, Vector3(0f, 0f, -1f), 1f, 0.5f, 0.5f, 10f, 10f,
        sessionEpoch = 1, frameIndex = frame, hostReceiptNs = frame * 16_666_667L)

    @Test fun hitIsUpdatedEveryFrameButTheListenerOnlyOnATargetChange() {
        val p = pointer("p")
        targets.publish(p, card, hit(card, 1)); runMain()
        targets.publish(p, card, hit(card, 2)); runMain()
        assertEquals(2L, p.hit!!.frameIndex)
        assertEquals(listOf(1L), seen.map { it.second!!.frameIndex })
    }

    @Test fun aBusyMainThreadGetsOnlyTheLatestTarget() {
        val p = pointer("p")
        targets.publish(p, card, hit(card, 1))
        targets.publish(p, other, hit(other, 2))
        targets.publish(p, null, null)
        targets.publish(p, card, hit(card, 4))
        assertEquals(1, queue.size)  // one posted task for all four changes
        runMain()
        assertEquals(1, seen.size)
        assertSame(card, seen[0].second!!.node)
        assertEquals(4L, seen[0].second!!.frameIndex)
    }

    @Test fun aSkippedFrameDropsTheHitAndTellsTheListenerOnce() {
        val p = pointer("p")
        targets.publish(p, card, hit(card, 1)); runMain()
        targets.drop(p)
        targets.drop(p)
        runMain()
        assertNull(p.hit)
        assertEquals(listOf<PointerHit?>(null), seen.drop(1).map { it.second })
        targets.publish(p, card, hit(card, 9)); runMain()  // tracking is back: the target is reported again
        assertEquals(9L, seen.last().second!!.frameIndex)
    }

    @Test fun aDisabledPointerHearsNothingFromADrop() {
        val p = pointer("p")
        p.enabled = false
        targets.publish(null, null, null)
        targets.drop(p)
        runMain()
        assertNull(p.hit)
        assertEquals(emptyList<Pair<String, PointerHit?>>(), seen)
    }

    @Test fun aReplacedPointerHearsThatItHasNoTarget() {
        val a = pointer("a")
        val b = pointer("b")
        targets.publish(a, card, hit(card, 1)); runMain()
        targets.publish(b, card, hit(card, 2)); runMain()
        assertEquals(listOf("a" to null, "b" to 2L), seen.drop(1).map { it.first to it.second?.frameIndex })
    }
}
