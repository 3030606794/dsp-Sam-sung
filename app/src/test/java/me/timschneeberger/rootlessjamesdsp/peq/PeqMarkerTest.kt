package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import me.timschneeberger.rootlessjamesdsp.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqMarkerTest {
    private var controller: ActivityController<HostActivity>? = null

    @After fun close() { controller?.pause()?.stop()?.destroy() }

    private fun launch(widthDp: Int = 411, heightDp: Int = 220): PeqResponseView {
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        return controller!!.get().curve.apply {
            val density = resources.displayMetrics.density
            val w = (widthDp * density).toInt()
            val h = (heightDp * density).toInt()
            measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            layout(0, 0, w, h)
        }
    }

    private fun draw(curve: PeqResponseView): PeqResponseView.BandMarker? {
        val bitmap = Bitmap.createBitmap(curve.width, curve.height, Bitmap.Config.ARGB_8888)
        try { curve.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
        return curve.renderedMarker
    }

    @Test fun defaultMarkerShowsFirstBandAndSelectionFollowsBandId() {
        val curve = launch()
        val first = PeqBand(id = "first", frequency = 300.0, gain = 5.25)
        val selected = PeqBand(id = "second", frequency = 1234.567, gain = -6.75)
        curve.setState(PeqState(listOf(first, selected)), false)
        assertEquals(first.id, draw(curve)!!.bandId)
        curve.setSelectedBand(selected.id)
        val marker = draw(curve)!!
        assertEquals(selected.id, marker.bandId)
        assertEquals(selected.frequency, marker.frequency, 0.0)
        assertEquals(selected.gain, marker.gain, 0.0)
        assertTrue(marker.frequencyText.contains("1234.567"))
        assertTrue(marker.gainText.contains("-6.75"))
        assertTrue(curve.contentDescription.contains(marker.frequencyText))
    }

    @Test fun selectedParametersAndMarkerPositionUpdateBeforeCurveWorkerFinishes() {
        val curve = launch()
        val band = PeqBand(id = "selected", frequency = 100.0, gain = -10.0)
        curve.setSelectedBand(band.id)
        curve.setState(PeqState(listOf(band)), false)
        val before = draw(curve)!!
        val changed = band.copy(frequency = 2500.0, gain = 9.5)
        curve.setState(PeqState(listOf(changed)), false)
        val after = draw(curve)!!
        assertEquals(changed.frequency, after.frequency, 0.0)
        assertEquals(changed.gain, after.gain, 0.0)
        assertTrue(after.x > before.x)
        assertTrue(after.y < before.y)
        assertTrue(after.gainText.contains("+9.5"))
    }

    @Test fun passAndNotchFiltersReportZeroGainAndDeletingSelectionFallsBack() {
        val curve = launch()
        val first = PeqBand(id = "first")
        for (type in PeqFilterType.entries.filterNot { it.usesGain }) {
            val band = PeqBand(id = "selected", type = type, gain = 20.0)
            curve.setSelectedBand(band.id)
            curve.setState(PeqState(listOf(first, band)), false)
            assertEquals(0.0, draw(curve)!!.gain, 0.0)
        }
        curve.setState(PeqState(listOf(first)), false)
        assertEquals(first.id, draw(curve)!!.bandId)
        curve.setState(PeqState(emptyList()), false)
        assertNull(draw(curve))
    }

    @Test fun graphEdgesKeepMarkerAndLabelsVisibleWithoutChangingStoredValues() {
        val curve = launch(widthDp = 280, heightDp = 160)
        for (frequency in listOf(20.0, 20000.0)) {
            for (gain in listOf(-100.0, 100.0)) {
                val band = PeqBand(frequency = frequency, gain = gain)
                curve.setState(PeqState(listOf(band), gainRange = 6.0), false)
                val marker = draw(curve)!!
                assertEquals(frequency, marker.frequency, 0.0)
                assertEquals(gain, marker.gain, 0.0)
                assertTrue(marker.plotBounds.contains(marker.labelBounds))
                assertTrue(marker.x - marker.radius >= marker.plotBounds.left)
                assertTrue(marker.x + marker.radius <= marker.plotBounds.right)
                assertTrue(marker.y - marker.radius >= marker.plotBounds.top)
                assertTrue(marker.y + marker.radius <= marker.plotBounds.bottom)
                if (frequency == 20.0) assertTrue(marker.labelBounds.left > marker.x)
                else assertTrue(marker.labelBounds.right < marker.x)
                assertEquals(band, curve.requestedState!!.bands.single())
            }
        }
    }

    @Test fun landscapeStillKeepsRightmostSelectedBandLabelInsideGraph() {
        val curve = launch(widthDp = 820, heightDp = 160)
        val band = PeqBand(frequency = 20000.0, gain = 100.0)
        curve.setState(PeqState(listOf(band), gainRange = 100.0), false)
        val marker = draw(curve)!!
        assertTrue(marker.plotBounds.contains(marker.labelBounds))
        assertTrue(marker.labelBounds.right < marker.x)
    }

    class HostActivity : FragmentActivity() {
        lateinit var curve: PeqResponseView
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            curve = PeqResponseView(this)
            setContentView(curve)
        }
    }
}
