package io.github.alexskuznetsov.xrspatial.scene

import android.opengl.GLES30
import android.opengl.Matrix
import io.github.alexskuznetsov.xrspatial.EyeFrame
import io.github.alexskuznetsov.xrspatial.XrRenderer
import io.github.alexskuznetsov.xrspatial.pose
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The content of a spatial app: a list of [SpatialNode]s drawn for both eyes every frame. Add and remove
 * nodes from any thread. Pass [renderer] to `XrSpatialSession.start`, or use [SpatialHost], which does it.
 */
class SpatialScene {
    private val nodes = CopyOnWriteArrayList<SpatialNode>()
    @Volatile private var recenterRequested = false

    val children: List<SpatialNode> get() = nodes.toList()

    fun <T : SpatialNode> add(node: T): T = node.also { nodes.addIfAbsent(it) }
    fun remove(node: SpatialNode) { nodes.remove(node) }
    fun clear() = nodes.clear()

    /** Places all [Placement.InFrontOfUser] nodes in front of the wearer again on the next frame. */
    fun recenter() { recenterRequested = true }

    /** Draws this scene; give it to `XrSpatialSession.start(renderer, listener)`. */
    val renderer: XrRenderer = SceneRenderer()

    private inner class SceneRenderer : XrRenderer {
        private val layout = SceneLayout()
        private val textures = HashMap<SpatialQuad, IntArray>()  // node -> [texture id, uploaded version]
        private val viewProjection = FloatArray(16)
        private val model = FloatArray(16)
        private val mvp = FloatArray(16)
        private var program = 0
        private var buffer = 0
        private var mvpLocation = 0
        private var opacityLocation = 0

        override fun onGlReady() {
            program = link()
            mvpLocation = GLES30.glGetUniformLocation(program, "mvp")
            opacityLocation = GLES30.glGetUniformLocation(program, "opacity")
            // Unit quad in x/y, front +Z, counter-clockwise; texture row 0 (bitmap top) at y = +0.5.
            val quad = floatArrayOf(
                -0.5f, -0.5f, 0f, 1f, 0.5f, -0.5f, 1f, 1f, 0.5f, 0.5f, 1f, 0f,
                -0.5f, -0.5f, 0f, 1f, 0.5f, 0.5f, 1f, 0f, -0.5f, 0.5f, 0f, 0f,
            )
            val data = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            data.put(quad).position(0)
            val ids = IntArray(1)
            GLES30.glGenBuffers(1, ids, 0)
            buffer = ids[0]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, data, GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            textures.clear()  // a new GL context per session: everything is uploaded again
        }

        override fun onDrawEye(eye: EyeFrame) {
            val head = eye.head
            if (eye.eye == 0 && recenterRequested) { recenterRequested = false; layout.recenter() }
            layout.onEye(eye.eye, eye.view, head.pose, head.sessionEpoch)
            Matrix.multiplyMM(viewProjection, 0, eye.projection, 0, eye.view, 0)

            val eyePosition = Mat4.eyePoseFromView(eye.view).position
            val drawn = nodes.filterIsInstance<SpatialQuad>()
                .filter { it.visible && it.opacity > 0f && it.content != null && it.widthMeters > 0f }
                .map { it to layout.poseOf(it, it.placement) }
                .sortedByDescending { (_, pose) -> (pose.position - eyePosition).length }  // back to front
            if (eye.eye == 0) dropUnused()

            GLES30.glUseProgram(program)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)
            GLES30.glDepthMask(false)
            GLES30.glEnable(GLES30.GL_CULL_FACE)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)  // Android bitmaps are premultiplied
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            for ((node, pose) in drawn) {
                val texture = texture(node) ?: continue
                Mat4.pose(pose, node.widthMeters, node.heightMeters, 1f, model)
                Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
                GLES30.glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
                GLES30.glUniform1f(opacityLocation, node.opacity.coerceIn(0f, 1f))
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glDepthMask(true)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDisableVertexAttribArray(1)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            GLES30.glUseProgram(0)
        }

        override fun onGlRelease() {
            for (entry in textures.values) GLES30.glDeleteTextures(1, entry, 0)
            textures.clear()
            if (buffer != 0) GLES30.glDeleteBuffers(1, intArrayOf(buffer), 0)
            if (program != 0) GLES30.glDeleteProgram(program)
            buffer = 0
            program = 0
        }

        // Uploads the node's bitmap when it is new or changed. The swapchain is sRGB, so the texture is too:
        // sampling decodes to linear and the framebuffer encodes back, keeping the bitmap's colours.
        private fun texture(node: SpatialQuad): Int? {
            val version = node.version
            val bitmap = node.content ?: return null
            val entry = textures.getOrPut(node) {
                IntArray(2).also { GLES30.glGenTextures(1, it, 0); it[1] = version - 1 }
            }
            if (entry[1] != version) {
                val pixels = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
                bitmap.copyPixelsToBuffer(pixels)
                pixels.position(0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, entry[0])
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_SRGB8_ALPHA8, bitmap.width, bitmap.height, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                entry[1] = version
            }
            return entry[0]
        }

        private fun dropUnused() {
            if (textures.isEmpty()) return
            val iterator = textures.entries.iterator()
            while (iterator.hasNext()) {
                val (node, entry) = iterator.next()
                if (!nodes.contains(node)) { GLES30.glDeleteTextures(1, entry, 0); iterator.remove() }
            }
        }

        private fun link(): Int {
            fun compile(type: Int, source: String) = GLES30.glCreateShader(type).also { shader ->
                GLES30.glShaderSource(shader, source)
                GLES30.glCompileShader(shader)
                val ok = IntArray(1)
                GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "shader: " + GLES30.glGetShaderInfoLog(shader) }
            }
            val vs = compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fs = compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
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
    }

    private companion object {
        const val VERTEX_SHADER = """#version 300 es
uniform mat4 mvp;
layout(location = 0) in vec2 position;
layout(location = 1) in vec2 uv;
out vec2 vUv;
void main() { vUv = uv; gl_Position = mvp * vec4(position, 0.0, 1.0); }
"""
        const val FRAGMENT_SHADER = """#version 300 es
precision mediump float;
uniform sampler2D tex;
uniform float opacity;
in vec2 vUv;
out vec4 outColor;
void main() { outColor = texture(tex, vUv) * opacity; }
"""
    }
}
