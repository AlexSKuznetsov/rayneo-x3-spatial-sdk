package io.github.alexskuznetsov.xrspatial.scene

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import io.github.alexskuznetsov.xrspatial.EyeFrame
import io.github.alexskuznetsov.xrspatial.Pose3
import io.github.alexskuznetsov.xrspatial.PoseSample
import io.github.alexskuznetsov.xrspatial.TimingStats
import io.github.alexskuznetsov.xrspatial.Vector3
import io.github.alexskuznetsov.xrspatial.XrRenderer
import io.github.alexskuznetsov.xrspatial.pose
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.pow
import kotlin.math.tan

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

    /**
     * The pointer drawn over the scene, or null for none. Set, replace or remove it at any time; switch it off and
     * on with [SpatialPointer.enabled].
     */
    @Volatile var pointer: SpatialPointer? = null

    /**
     * Places all [Placement.InFrontOfUser] nodes in front of the wearer again on the next frame. The pointer's
     * [SpatialPointer.hit] is null until that frame finds the target again.
     */
    fun recenter() {
        recenterRequested = true
        pointer?.hit = null
    }

    /** Draws this scene; give it to `XrSpatialSession.start(renderer, listener)`. */
    val renderer: XrRenderer = SceneRenderer()

    private val uploadTime = TimingStats()
    @Volatile private var uploadedBytes = 0L

    /**
     * For logs: `textureUpload` (host CPU time to copy and upload a changed bitmap, including mipmaps, since the
     * scene was created), `textureUploadBytes`, and over all panels `panelUpdates` / `panelBitmapsCreated`.
     */
    fun diagnostics(): Map<String, String> {
        val panels = nodes.filterIsInstance<SpatialPanel>()
        return mapOf(
            "textureUpload" to uploadTime.toString(),
            "textureUploadBytes" to uploadedBytes.toString(),
            "panelUpdates" to panels.sumOf { it.updates }.toString(),
            "panelBitmapsCreated" to panels.sumOf { it.bitmapsCreated }.toString(),
        )
    }

    private inner class SceneRenderer : XrRenderer {
        private val layout = SceneLayout()
        private val textures = HashMap<SpatialQuad, IntArray>()  // node -> [texture id, uploaded version, width, height]
        private var staging: ByteBuffer? = null  // reused for every upload, grown when a bitmap is larger

        /** One node as drawn this frame: a single content snapshot, so size and texture always match. */
        private inner class Drawn(val node: SpatialQuad, val frame: Versioned<Bitmap>, val pose: Pose3) {
            val width = node.widthMeters
            val height = width * frame.value.height / frame.value.width
        }
        private val viewProjection = FloatArray(16)
        private val model = FloatArray(16)
        private val mvp = FloatArray(16)
        private var program = 0
        private var buffer = 0
        private var mvpLocation = 0
        private var opacityLocation = 0
        private var solid = 0
        private var solidMvp = 0
        private var solidColor = 0
        private var solidShape = 0
        private val main = Handler(Looper.getMainLooper())
        private var cast: PointerCast.Result? = null
        private var castPointer: SpatialPointer? = null
        private val targets = PointerTargets { main.post(it) }

        override fun onGlReady() {
            program = link()
            mvpLocation = GLES30.glGetUniformLocation(program, "mvp")
            opacityLocation = GLES30.glGetUniformLocation(program, "opacity")
            solid = link(SOLID_FRAGMENT_SHADER)
            solidMvp = GLES30.glGetUniformLocation(solid, "mvp")
            solidColor = GLES30.glGetUniformLocation(solid, "color")
            solidShape = GLES30.glGetUniformLocation(solid, "shape")
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
                .filter { it.visible && it.opacity > 0f && it.widthMeters > 0f }
                .mapNotNull { node -> node.content.current?.let { Drawn(node, it, layout.poseOf(node, node.placement)) } }
                .sortedByDescending { (it.pose.position - eyePosition).length }  // back to front
            if (eye.eye == 0) {
                dropUnused()
                castPointer(drawn, head)
            }

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
            for (d in drawn) {
                val node = d.node
                val texture = texture(node, d.frame)
                Mat4.pose(d.pose, d.width, d.height, 1f, model)
                Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
                GLES30.glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
                GLES30.glUniform1f(opacityLocation, node.opacity.coerceIn(0f, 1f))
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            drawPointer(eyePosition)
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
            if (solid != 0) GLES30.glDeleteProgram(solid)
            buffer = 0
            program = 0
            solid = 0
            dropPointer()  // the session ended: no frames, so no target
        }

        // No usable pose this frame: nothing is drawn, so nothing may stay targeted.
        override fun onFrameSkipped(head: PoseSample) = dropPointer()

        private fun dropPointer() {
            cast = null
            targets.drop(pointer)
        }

        // Once per frame (left eye): what the pointer hits, published to SpatialPointer.hit and, when the target
        // node changes, to its listener on the main thread.
        private fun castPointer(drawn: List<Drawn>, head: PoseSample) {
            val p = pointer?.takeIf { it.enabled }
            cast = p?.let { pointer ->
                val targets = drawn.map { d -> PointerCast.Target(d, d.pose, d.width, d.height) }
                runCatching { PointerCast.cast(pointer.source, pointer.style, layout.display, targets) }.getOrNull()
            }
            castPointer = p
            val result = cast
            val target = result?.target?.key as? Drawn
            val node = target?.node
            val hit = result?.hit
            val published = if (target != null && node != null && hit != null) {
                val bitmap = target.frame.value
                PointerHit(node, hit.point, hit.distance, hit.u, hit.v, hit.u * bitmap.width, hit.v * bitmap.height,
                    head.sessionEpoch, head.frameIndex, head.hostReceiptNs)
            } else null
            targets.publish(p, node, published)
        }

        // The pointer over everything (no depth test): a beam and/or a dot, camera-facing for this eye.
        private fun drawPointer(eyePosition: Vector3) {
            val result = cast ?: return
            val style = castPointer?.style ?: return
            GLES30.glUseProgram(solid)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            val dot = when (style) {
                is PointerStyle.Dot -> style
                is PointerStyle.Ray -> {
                    drawBeam(result.beamStart, result.end, style.widthMeters, style.color, eyePosition)
                    style.endDot
                }
            }
            if (dot != null) drawDot(result.end, dot, eyePosition)
            GLES30.glUseProgram(program)
        }

        private fun drawBeam(a: Vector3, b: Vector3, width: Float, color: Int, eyePosition: Vector3) {
            val along = b - a
            if (along.length < 1e-4f) return
            val middle = (a + b) * 0.5f
            val side = (along cross (eyePosition - middle)).normalized() * width
            draw(Mat4.basis(along, side, (along cross side).normalized(), middle, model), color, 0f)
        }

        private fun drawDot(at: Vector3, dot: PointerStyle.Dot, eyePosition: Vector3) {
            val toEye = eyePosition - at
            val distance = toEye.length
            if (distance < 1e-3f) return
            val size = 2f * distance * tan(Math.toRadians(dot.sizeDegrees / 2.0)).toFloat()
            val z = toEye * (1f / distance)
            val x = (Vector3.UP cross z).let { if (it.length < 1e-3f) Vector3.RIGHT else it.normalized() }
            val y = z cross x
            draw(Mat4.basis(x * size, y * size, z, at, model), dot.color, 1f)
        }

        private fun draw(modelMatrix: FloatArray, color: Int, shape: Float) {
            Matrix.multiplyMM(mvp, 0, viewProjection, 0, modelMatrix, 0)
            GLES30.glUniformMatrix4fv(solidMvp, 1, false, mvp, 0)
            // The framebuffer is sRGB: pass the colour linear and premultiplied, like the decoded bitmaps.
            val alpha = Color.alpha(color) / 255f
            GLES30.glUniform4f(solidColor, linear(Color.red(color)) * alpha, linear(Color.green(color)) * alpha,
                linear(Color.blue(color)) * alpha, alpha)
            GLES30.glUniform1f(solidShape, shape)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        }

        private fun linear(channel: Int): Float {
            val c = channel / 255f
            return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }

        // Uploads the node's bitmap when it is new or changed. The swapchain is sRGB, so the texture is too:
        // sampling decodes to linear and the framebuffer encodes back, keeping the bitmap's colours.
        private fun texture(node: SpatialQuad, frame: Versioned<Bitmap>): Int {
            val version = frame.version
            val bitmap = frame.value
            val entry = textures.getOrPut(node) {
                IntArray(4).also { GLES30.glGenTextures(1, it, 0); it[1] = version - 1 }
            }
            if (entry[1] != version) {
                val t0 = System.nanoTime()
                val pixels = staging?.takeIf { it.capacity() >= bitmap.byteCount }
                    ?: ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder()).also { staging = it }
                pixels.clear()
                bitmap.copyPixelsToBuffer(pixels)
                pixels.position(0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, entry[0])
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
                if (entry[2] == bitmap.width && entry[3] == bitmap.height) {
                    // Same size: overwrite the existing storage instead of allocating new.
                    GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap.width, bitmap.height,
                        GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                } else {
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_SRGB8_ALPHA8, bitmap.width, bitmap.height, 0,
                        GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                    entry[2] = bitmap.width
                    entry[3] = bitmap.height
                }
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                entry[1] = version
                node.content.consumed(version)  // the panel may draw into the bitmap before this one again
                uploadTime.record(System.nanoTime() - t0)
                uploadedBytes += bitmap.byteCount
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

        private fun link(fragmentShader: String = FRAGMENT_SHADER): Int {
            fun compile(type: Int, source: String) = GLES30.glCreateShader(type).also { shader ->
                GLES30.glShaderSource(shader, source)
                GLES30.glCompileShader(shader)
                val ok = IntArray(1)
                GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "shader: " + GLES30.glGetShaderInfoLog(shader) }
            }
            val vs = compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentShader)
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
        // Pointer: shape 0 = beam (soft across its width), 1 = round dot; colour is premultiplied.
        const val SOLID_FRAGMENT_SHADER = """#version 300 es
precision mediump float;
uniform vec4 color;
uniform float shape;
in vec2 vUv;
out vec4 outColor;
void main() {
    vec2 d = vUv * 2.0 - 1.0;
    float a = shape > 0.5 ? clamp((1.0 - length(d)) * 6.0, 0.0, 1.0) : clamp((1.0 - abs(d.y)) * 3.0, 0.0, 1.0);
    outColor = color * a;
}
"""
    }
}
