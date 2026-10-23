package io.github.alexskuznetsov.xrspatial.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneGeometryTest {
    private val n = SceneGeometry.FLOATS_PER_VERTEX

    @Test fun twoCubesOfSixFacesAndOneBar() {
        assertEquals((2 * 6 + 1) * 6 * n, SceneGeometry.triangles().size)
        assertEquals(2 * 12 * 2 * n, SceneGeometry.edges().size)
    }

    @Test fun everyTriangleFacesAwayFromItsCubeCentre() {
        val t = SceneGeometry.triangles()
        for (tri in 0 until 2 * 6 * 2) {
            val cube = SceneGeometry.cubes[tri / 12]
            val v = (0..2).map { i -> FloatArray(3) { k -> t[(tri * 3 + i) * n + k] } }
            val e1 = FloatArray(3) { v[1][it] - v[0][it] }
            val e2 = FloatArray(3) { v[2][it] - v[0][it] }
            val normal = floatArrayOf(e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
            val out = floatArrayOf(v[0][0] + v[1][0] + v[2][0] - 3 * cube.cx, v[0][1] + v[1][1] + v[2][1] - 3 * cube.cy, v[0][2] + v[1][2] + v[2][2] - 3 * cube.cz)
            assertTrue("triangle $tri winds inwards", normal[0] * out[0] + normal[1] * out[1] + normal[2] * out[2] > 0f)
        }
    }

    @Test fun barFacesTheStartPositionAtEyeHeight() {
        val t = SceneGeometry.triangles()
        val first = 2 * 6 * 6
        for (i in 0 until 6) {
            assertEquals(SceneGeometry.BAR_Z, t[(first + i) * n + 2], 0f)
            assertTrue(Math.abs(t[(first + i) * n + 1] - SceneGeometry.BAR_Y) <= 0.006f + 1e-6f)
        }
        val e1x = t[(first + 1) * n] - t[first * n]
        val e2y = t[(first + 2) * n + 1] - t[first * n + 1]
        assertTrue(e1x * e2y > 0f)  // counter-clockwise from +Z, so not culled
    }

    @Test fun edgesAreTwentyCentimetresLongAndAxisAligned() {
        val e = SceneGeometry.edges()
        for (line in 0 until e.size / (2 * n)) {
            val d = (0..2).map { k -> Math.abs(e[(2 * line + 1) * n + k] - e[2 * line * n + k]) }
            assertEquals(1, d.count { it > 1e-6f })
            assertEquals(0.2f, d.max(), 1e-6f)
        }
    }
}
