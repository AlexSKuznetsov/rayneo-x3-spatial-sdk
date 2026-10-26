package io.github.alexskuznetsov.xrspatial.samplescene

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import io.github.alexskuznetsov.xrspatial.PoseListener
import io.github.alexskuznetsov.xrspatial.PoseSample
import io.github.alexskuznetsov.xrspatial.SampleQuality
import io.github.alexskuznetsov.xrspatial.TrackingState
import io.github.alexskuznetsov.xrspatial.Vector3
import io.github.alexskuznetsov.xrspatial.scene.Placement
import io.github.alexskuznetsov.xrspatial.scene.SpatialHost
import io.github.alexskuznetsov.xrspatial.scene.SpatialPanel
import io.github.alexskuznetsov.xrspatial.scene.SpatialScene
import io.github.alexskuznetsov.xrspatial.scene.SpatialText

/** A maintenance card from an ordinary XML layout, fixed in the room 1.5 m in front of the wearer. */
class MaintenanceActivity : Activity() {
    @SuppressLint("InflateParams")  // the card is drawn into a texture, it has no parent view
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scene = SpatialScene()
        val card = layoutInflater.inflate(R.layout.maintenance_card, null)
        scene.add(SpatialPanel(card, widthMeters = 0.32f, placement = Placement.InFrontOfUser(1.5f)))
        val status = scene.add(SpatialText(this, "Starting", widthMeters = 0.32f, textSizeMeters = 0.014f,
            placement = Placement.InFrontOfUser(1.5f, offset = Vector3(0f, -0.15f, 0f))))
        SpatialHost.attach(this, scene, listener = object : PoseListener {
            override fun onSample(sample: PoseSample, quality: SampleQuality) = Unit
            override fun onStateChanged(state: TrackingState) { status.text = "Tracking: $state" }  // main thread
        })
    }
}
