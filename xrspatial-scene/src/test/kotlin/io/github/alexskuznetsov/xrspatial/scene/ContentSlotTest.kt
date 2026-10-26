package io.github.alexskuznetsov.xrspatial.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ContentSlotTest {
    /** A payload that knows the version it was published with, to detect mixed pairs. */
    private class Payload(val writer: Int, val sequence: Int)

    @Test fun versionsIncreaseAndTheLastPublicationIsCurrent() {
        val slot = ContentSlot<String>()
        assertNull(slot.current)
        val a = slot.publish("a")
        val b = slot.publish("b")
        assertTrue(b.version > a.version)
        assertEquals("b", slot.current!!.value)
        assertEquals(b.version, slot.current!!.version)
    }

    @Test fun concurrentPublishersNeverProduceAMixedSnapshot() {
        val slot = ContentSlot<Payload>()
        val published = java.util.concurrent.ConcurrentHashMap<Int, Payload>()  // version -> payload
        val start = CountDownLatch(1)
        val writers = (0 until 4).map { w ->
            thread {
                start.await()
                repeat(5_000) { i -> val v = slot.publish(Payload(w, i)); published[v.version] = v.value }
            }
        }
        val mixed = AtomicBoolean(false)
        val seen = HashMap<Int, Payload>()
        val reader = thread {
            start.await()
            repeat(50_000) {
                val snapshot = slot.current ?: return@repeat
                val earlier = seen.put(snapshot.version, snapshot.value)
                if (earlier != null && earlier !== snapshot.value) mixed.set(true)  // one version, two payloads
            }
        }
        start.countDown()
        writers.forEach { it.join() }
        reader.join()
        assertTrue("every version maps to exactly the payload it was published with", !mixed.get())
        for ((version, payload) in seen) assertTrue(published[version] === payload)
        assertEquals(20_000, published.size)  // no lost or duplicated versions
    }
}
