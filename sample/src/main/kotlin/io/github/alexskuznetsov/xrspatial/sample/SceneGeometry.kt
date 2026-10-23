package io.github.alexskuznetsov.xrspatial.sample

/** Vertex data (x, y, z, r, g, b) of the sample scene in LOCAL metres. Pure Kotlin, unit-tested. */
object SceneGeometry {
    const val FLOATS_PER_VERTEX = 6

    class Cube(val cx: Float, val cy: Float, val cz: Float, val half: Float, val colors: List<FloatArray>)

    /** Face order +X, -X, +Y, -Y, +Z (towards the start position), -Z. */
    val cubes = listOf(
        Cube(0f, -0.2f, -1.5f, 0.1f, listOf(rgb(1f, 0f, 1f), rgb(0f, 1f, 1f), rgb(1f, 1f, 0f), rgb(0.6f, 0.6f, 0.6f), rgb(0f, 1f, 0f), rgb(0.2f, 0.4f, 1f))),
        Cube(0.3f, -0.2f, -2.5f, 0.1f, listOf(rgb(1f, 0.5f, 0f), rgb(0.8f, 0.4f, 0f), rgb(1f, 0.75f, 0.3f), rgb(0.6f, 0.3f, 0f), rgb(1f, 0.6f, 0.1f), rgb(0.7f, 0.35f, 0f))),
    )

    /** Eye-height bar: 2 m ahead, 1.2 m wide, 1.2 cm high, at the eyes' height in LOCAL (about -1 cm). */
    const val BAR_Y = -0.01f
    const val BAR_Z = -2f

    private class Face(val n: FloatArray, val u: FloatArray, val v: FloatArray)  // u x v = n
    private val faces = listOf(
        Face(f(1, 0, 0), f(0, 1, 0), f(0, 0, 1)), Face(f(-1, 0, 0), f(0, 0, 1), f(0, 1, 0)),
        Face(f(0, 1, 0), f(0, 0, 1), f(1, 0, 0)), Face(f(0, -1, 0), f(1, 0, 0), f(0, 0, 1)),
        Face(f(0, 0, 1), f(1, 0, 0), f(0, 1, 0)), Face(f(0, 0, -1), f(0, 1, 0), f(1, 0, 0)),
    )
    private val corners = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to -1f, 1f to 1f, -1f to 1f)

    /** Cube faces (60 % brightness) and the bar, counter-clockwise seen from outside / from the start. */
    fun triangles(): FloatArray {
        val out = ArrayList<Float>()
        for (cube in cubes) for ((i, face) in faces.withIndex()) for ((a, b) in corners) {
            for (k in 0..2) out += cube.centre(k) + cube.half * (face.n[k] + a * face.u[k] + b * face.v[k])
            for (k in 0..2) out += 0.6f * cube.colors[i][k]
        }
        for ((a, b) in corners) {
            out += listOf(a * 0.6f, BAR_Y + b * 0.006f, BAR_Z, 1f, 1f, 1f)
        }
        return out.toFloatArray()
    }

    /** White edges, 12 per cube, as line pairs. */
    fun edges(): FloatArray {
        val out = ArrayList<Float>()
        for (cube in cubes) for (a in 0 until 8) for (bit in intArrayOf(1, 2, 4)) {
            if (a and bit != 0) continue
            for (corner in intArrayOf(a, a or bit)) {
                out += cube.cx + cube.half * sign(corner and 1)
                out += cube.cy + cube.half * sign(corner and 2)
                out += cube.cz + cube.half * sign(corner and 4)
                out += listOf(1f, 1f, 1f)
            }
        }
        return out.toFloatArray()
    }

    private fun Cube.centre(k: Int) = when (k) { 0 -> cx; 1 -> cy; else -> cz }
    private fun sign(bit: Int) = if (bit != 0) 1f else -1f
    private fun f(x: Int, y: Int, z: Int) = floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat())
    private fun rgb(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)
}
