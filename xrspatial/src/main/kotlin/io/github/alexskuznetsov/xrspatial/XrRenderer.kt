package io.github.alexskuznetsov.xrspatial

/**
 * Draws world-locked content with OpenGL ES 3 for each eye, every frame. All calls arrive on the SDK's
 * render thread, which owns the EGL context; use `android.opengl.GLES30` there and nowhere else.
 *
 * Coordinates are OpenXR LOCAL (metres, right-handed, +X right, +Y up, -Z forward of the head at session
 * start; origin at the head at session start). A point placed at (0, 0, -2) appears 2 m straight ahead of
 * where the wearer looked when the session started, and stays there while the wearer moves.
 */
interface XrRenderer {
    /** GL context is current: create shaders, buffers, textures. Called once per session. */
    fun onGlReady() {}

    /**
     * Draw one eye. The eye's framebuffer (colour + 16-bit depth) is bound, the viewport set and cleared to
     * black (transparent on the additive display). Called only for frames whose head pose is
     * [SampleQuality.USABLE]; otherwise the eye stays black. Do not change the framebuffer binding.
     */
    fun onDrawEye(eye: EyeFrame)

    /** GL context is still current: delete what [onGlReady] created. Called once per session. */
    fun onGlRelease() {}
}

/**
 * One eye of one frame. Matrices are column-major `FloatArray(16)`s in the layout `android.opengl.Matrix` and
 * `glUniformMatrix4fv(..., false, ...)` expect: `clip = projection * view * model * point`.
 * The arrays are reused between calls; copy them if you keep them.
 */
class EyeFrame internal constructor(
    /** 0 = left, 1 = right. */
    val eye: Int,
    /** LOCAL -> eye space, from this frame's head pose and the runtime's eye pose. */
    val view: FloatArray,
    /** Eye space -> clip space, from the runtime's eye field of view (see docs/x3-pro-runtime-notes.md). */
    val projection: FloatArray,
    val width: Int,
    val height: Int,
    /** The head pose this frame is drawn with. */
    val head: PoseSample,
)
