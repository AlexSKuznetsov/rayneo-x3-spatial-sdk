package io.github.alexskuznetsov.xrspatial.sample

import android.opengl.GLES30
import android.opengl.Matrix
import io.github.alexskuznetsov.xrspatial.EyeFrame
import io.github.alexskuznetsov.xrspatial.XrRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Two coloured cubes with white edges and a white bar at the start eye height, all fixed in LOCAL space:
 * - a 20 cm cube 1.5 m ahead and 0.2 m below the start eye height (front green, left cyan, right magenta,
 *   top yellow),
 * - a 20 cm orange cube 0.3 m right and 2.5 m ahead (parallax: it shifts against the first cube when you
 *   step sideways),
 * - a 1.2 m white bar 2 m ahead at the start eye height: it lines up with a real mark at your eye height.
 */
class CubeSceneRenderer : XrRenderer {
    private var program = 0
    private var mvpLocation = 0
    private var buffer = 0
    private var triangleVertices = 0
    private var lineVertices = 0
    private val mvp = FloatArray(16)

    override fun onGlReady() {
        program = link(VERTEX_SHADER, FRAGMENT_SHADER)
        mvpLocation = GLES30.glGetUniformLocation(program, "mvp")
        val triangles = SceneGeometry.triangles()
        val lines = SceneGeometry.edges()
        triangleVertices = triangles.size / FLOATS_PER_VERTEX
        lineVertices = lines.size / FLOATS_PER_VERTEX
        val data = ByteBuffer.allocateDirect((triangles.size + lines.size) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        data.put(triangles).put(lines).position(0)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        buffer = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.capacity() * 4, data, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    override fun onDrawEye(eye: EyeFrame) {
        // The scene is defined directly in LOCAL coordinates, so model = identity.
        Matrix.multiplyMM(mvp, 0, eye.projection, 0, eye.view, 0)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 0)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 3 * 4)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1f, 1f)  // faces slightly back so the edges win the depth test
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, triangleVertices)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glLineWidth(3f)
        GLES30.glDrawArrays(GLES30.GL_LINES, triangleVertices, lineVertices)
        GLES30.glLineWidth(1f)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glUseProgram(0)
    }

    override fun onGlRelease() {
        if (buffer != 0) GLES30.glDeleteBuffers(1, intArrayOf(buffer), 0)
        if (program != 0) GLES30.glDeleteProgram(program)
        buffer = 0
        program = 0
    }

    private fun link(vertexSource: String, fragmentSource: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES30.glGetShaderInfoLog(shader) }
            return shader
        }
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program: " + GLES30.glGetProgramInfoLog(p) }
        return p
    }

    private companion object {
        const val FLOATS_PER_VERTEX = SceneGeometry.FLOATS_PER_VERTEX
        const val VERTEX_SHADER = """#version 300 es
uniform mat4 mvp;
layout(location = 0) in vec3 position;
layout(location = 1) in vec3 color;
out vec3 tint;
void main() { tint = color; gl_Position = mvp * vec4(position, 1.0); }
"""
        const val FRAGMENT_SHADER = """#version 300 es
precision mediump float;
in vec3 tint;
out vec4 outColor;
void main() { outColor = vec4(tint, 1.0); }
"""
    }
}
