# Scene API (`xrspatial-scene`, 0.3)

Cards, images and text in the room, from ordinary Android code: no OpenGL, no C++, no lifecycle handling.
Package `io.github.alexskuznetsov.xrspatial.scene`. Built on `xrspatial` (`XrSpatialSession`, `XrRenderer`);
the lower levels stay available.

Status: host-tested (layout against the X3 Pro's measured eye views, 9 unit tests) and run once on the
glasses for 60 s: the `sample-scene` card appeared at once in the middle of the view, the text (20–26 px at
1.5 m) was easy to read and not too bright, and the card stayed in place while the wearer walked and turned.

## Minimal app

```kotlin
class HelloActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scene = SpatialScene()
        scene.add(SpatialText(this, "Hello, room", widthMeters = 0.3f))
        SpatialHost.attach(this, scene)
    }
}
```

The app module needs the setup from the README (RayNeo libraries, `useLegacyPackaging`, `arm64-v8a`,
`minSdk 30`). A plain `android.app.Activity` is enough; no AndroidX is required.

## Classes

| Class | What it does |
| --- | --- |
| `SpatialHost.attach(activity, scene, config, listener)` | Makes the Activity a full-screen SurfaceView, keeps the display on, starts and stops the session with the Activity's lifecycle, window focus and surface. `host.session` is the underlying `XrSpatialSession`. |
| `SpatialScene` | The list of nodes; `add`, `remove`, `clear`, `recenter()`, `pointer` (see [Pointer](#pointer)). Thread-safe. `scene.renderer` is an `XrRenderer` if you run the session yourself. |
| `SpatialPanel(view, widthMeters, placement, pixelsPerMeter = 1000f)` | Any Android `View` (inflated layout, `LinearLayout` with `TextView`s and `ImageView`s, ...) drawn into a texture. Call `update()` on the main thread after changing the view. `performClickAt(hit)` clicks a view under a pointer hit. |
| `SpatialText(context, text, widthMeters, placement, textSizeMeters = 0.02f, color)` | A `TextView` with sizes in metres; set `text` on the main thread. |
| `SpatialImage(bitmap, widthMeters, placement)` | A bitmap; `setBitmap()` to change it. Height follows the aspect ratio. |
| `SpatialNode.visible`, `.opacity`, `.placement` | Change from any thread; applied on the next frame. |

All nodes are flat rectangles whose front faces the wearer (their +Z side); from behind they are invisible.

## Placement

| Placement | Meaning |
| --- | --- |
| `InFrontOfUser(distance = 1.5f, offset)` | World-locked. Placed once, `distance` metres along the display's view axis from the head, facing the wearer without roll; `offset` moves it in its own frame (x right, y up). Then it stays put while the wearer moves. |
| `InLocal(pose)` | World-locked at a fixed `Pose3` in the session's LOCAL space (origin at the head at session start, -Z ahead, +Y up). |
| `HeadLocked(distance, offset)` | Follows the head along the display axis. Use for status only. |

**Sessions.** Everything is session-local. A new tracking session (first start, or after a pause on focus
loss) re-creates LOCAL around the head; `InFrontOfUser` nodes are then placed in front of the wearer again,
and `InLocal` poses are interpreted in the new LOCAL. Nothing persists across sessions or app restarts: the
X3 Pro runtime advertises no spatial anchor extension (only `XR_EXT_hand_tracking` on runtime 1.1.7.9).
`scene.recenter()` places `InFrontOfUser` nodes in front again on demand.

**Display axis.** On the X3 Pro both eye views look about 10.8° below the head's forward direction. The
layout uses the runtime's eye views, so "in front" is the middle of what the wearer sees. A point straight
along the head's -Z at 1.5 m would sit above the top edge of the image.

## Pointer

A pointer the scene draws over its content: a **dot** or a **ray**, from the centre of the view or from a pose
the app supplies. The SDK only draws it and reports what it points at; what a tap, a swipe or a dwell does is
the app's decision.

```kotlin
val pointer = SpatialPointer(PointerStyle.Dot())            // or PointerStyle.Ray()
scene.pointer = pointer                                      // scene.pointer = null removes it
pointer.enabled = false                                      // off: nothing drawn, hit = null; true: on again
pointer.style = PointerStyle.Ray()                           // switch at any time
pointer.listener = PointerListener { hit -> /* main thread, when the target node changes */ }

override fun dispatchTouchEvent(e: MotionEvent): Boolean {  // the temple touchpad: ordinary touch events
    if (e.actionMasked == MotionEvent.ACTION_UP) pointer.hit?.let { card.performClickAt(it) }
    return true
}
```

| Type | Description |
| --- | --- |
| `PointerStyle.Dot(sizeDegrees = 0.6f, color = WHITE, distanceWithoutHit = 1.5f)` | A round dot on the target, a fixed angular size, drawn at the target's depth (a dot drawn at a fixed depth doubles in stereo when looking at a card). Without a target it floats `distanceWithoutHit` metres ahead. |
| `PointerStyle.Ray(widthMeters = 0.003f, color, maxLength = 3f, endDot = Dot(0.5f))` | A beam from the source to the target (or `maxLength` metres), optionally ending in a dot. |
| `PointerSource.Gaze(rayOrigin = (0.1, -0.15, 0))` | The default. Points along the display axis, the centre of what the wearer sees (about 11° below the head's forward on the X3 Pro). `rayOrigin` (display frame, metres) only sets where a visible beam starts, since a beam from the eyes would be seen end-on. |
| `PointerSource.Custom { pose }` | A LOCAL pose from the app every frame (render thread), e.g. from a tracked phone; the pointer runs along its −Z. Return null to hide it for that frame. |
| `SpatialPointer.hit: PointerHit?` | The latest target: `node`, `point` (LOCAL), `distance`, `u`/`v` (0..1 from top-left) and `pixelX`/`pixelY` in the node's bitmap (a panel's view coordinates). Null when nothing is hit, when off, or before tracking. Any thread. |
| `SpatialPanel.performClickAt(hit): View?` | Optional helper: `performClick()` on the deepest visible, enabled, clickable view under the hit, then redraw. Main thread. Panel views have no window, so touch events dispatched into them would not click. |

Only visible nodes with content are hit; the nearest wins. Nodes are one-sided, so they are not hit from behind.
The [`sample-pointer`](../sample-pointer) app shows a card with two buttons: tap clicks, swipe towards the eye switches
dot/ray, swipe towards the ear switches the pointer off and on, two-finger tap recenters. Checked on the glasses:
the dot does not double, taps click the aimed button, the pointer switches off and on.

## Designing for this display

- **Small field of view**: about 24° × 18° per eye, 640 × 480 pixels. At 1.5 m that is roughly 0.64 m × 0.48 m.
  Cards of 0.25–0.35 m wide fit comfortably; a 0.7 m panel at 1.5 m does not fit.
- **Pixels per metre**: panels map 1000 view pixels to 1 m, close to 1:1 with the display at 1.5 m. Use `px`
  sizes in layouts: `20px` text is 2 cm, about 20 display pixels high at 1.5 m.
- **Additive (see-through) display**: black is transparent, white is brightest. Use light text and lines on
  a transparent background; a white card background becomes a glowing rectangle that hides the room.
- **Colours**: textures are sRGB, so colours look as in the bitmap.

## Threads and resources

- Nodes and the scene may be changed from any thread; Views must be drawn (`SpatialPanel.update`,
  `SpatialText.text`) on the main thread.
- The renderer runs on the session's render thread. GL textures are created per session (a new GL context
  each time) and uploaded again from the nodes' bitmaps; textures of removed nodes are deleted.
- Do not change or recycle a bitmap after passing it to a node.

## Not in 0.3

Input handling beyond the pointer (gestures are the app's), Compose content, anchors or persistence, depth sorting beyond
back-to-front per node, 3D models. See the roadmap in the README.
