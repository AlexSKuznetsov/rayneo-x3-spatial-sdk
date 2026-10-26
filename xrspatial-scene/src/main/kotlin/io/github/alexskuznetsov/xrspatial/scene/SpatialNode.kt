package io.github.alexskuznetsov.xrspatial.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Something in a [SpatialScene]. Properties may be changed from any thread; the change shows on the next
 * frame. On the see-through (additive) display black is transparent and white is brightest, so prefer light
 * content on a black or transparent background.
 */
abstract class SpatialNode internal constructor(placement: Placement) {
    @Volatile var placement: Placement = placement
    @Volatile var visible: Boolean = true
    /** 0..1, multiplies the content. */
    @Volatile var opacity: Float = 1f
}

/**
 * A flat rectangle showing a bitmap, [widthMeters] wide; the height follows the bitmap's aspect ratio. Its
 * front is the +Z side; seen from behind it is invisible.
 */
open class SpatialQuad internal constructor(placement: Placement, widthMeters: Float) : SpatialNode(placement) {
    @Volatile var widthMeters: Float = widthMeters
    val heightMeters: Float get() = content?.let { widthMeters * it.height / it.width } ?: 0f

    @Volatile internal var content: Bitmap? = null
        private set
    @Volatile internal var version = 0
        private set

    /** Shows [bitmap] from the next frame. The scene keeps a reference: do not change or recycle it afterwards. */
    protected fun show(bitmap: Bitmap) {
        content = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        version++
    }
}

/** A picture in space. */
class SpatialImage(bitmap: Bitmap, widthMeters: Float, placement: Placement = Placement.InFrontOfUser()) :
    SpatialQuad(placement, widthMeters) {
    init { show(bitmap) }

    fun setBitmap(bitmap: Bitmap) = show(bitmap)
}

/**
 * An ordinary Android [View] (any layout: TextView, ImageView, LinearLayout, ...) drawn into a texture and
 * shown [widthMeters] wide. The view is measured at `widthMeters * pixelsPerMeter` pixels wide and its own
 * height. It is not attached to a window and receives no input. Call [update] on the main thread after
 * changing the view.
 *
 * [pixelsPerMeter] 1000 matches the X3 Pro display (~640 px over ~24 degrees) at about 1.5 m; text of
 * 0.02 m (20 px) is comfortably readable there.
 */
open class SpatialPanel(
    val view: View,
    widthMeters: Float,
    placement: Placement = Placement.InFrontOfUser(),
    val pixelsPerMeter: Float = 1000f,
) : SpatialQuad(placement, widthMeters) {
    init { update() }

    /** Re-draws [view] into the panel's texture. Main thread. */
    fun update() {
        val widthPx = max(1, (widthMeters * pixelsPerMeter).roundToInt())
        // A View without a parent has no LayoutParams, and e.g. TextView.setText then fails in
        // checkForRelayout (seen on the glasses). Give it the panel's width.
        val params = view.layoutParams
        if (params == null) view.layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        else if (params.width != widthPx) { params.width = widthPx; view.layoutParams = params }
        view.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(MAX_HEIGHT_PX, View.MeasureSpec.AT_MOST))
        val heightPx = view.measuredHeight.coerceIn(1, MAX_HEIGHT_PX)
        view.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        show(bitmap)
    }

    private companion object { const val MAX_HEIGHT_PX = 4096 }
}

/** Text in space: a [TextView] with sizes in metres. Change [text] on the main thread. */
class SpatialText(
    context: Context,
    text: CharSequence,
    widthMeters: Float,
    placement: Placement = Placement.InFrontOfUser(),
    textSizeMeters: Float = 0.02f,
    color: Int = Color.WHITE,
    pixelsPerMeter: Float = 1000f,
) : SpatialPanel(TextView(context).apply {
    this.text = text
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizeMeters * pixelsPerMeter)
}, widthMeters, placement, pixelsPerMeter) {
    var text: CharSequence
        get() = (view as TextView).text
        set(value) { (view as TextView).text = value; update() }
}
