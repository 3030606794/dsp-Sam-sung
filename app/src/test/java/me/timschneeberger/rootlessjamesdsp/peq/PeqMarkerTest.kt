package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.google.android.material.color.MaterialColors
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
import java.util.Locale

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

    @Test fun markerStartsHiddenAndShowingUsesSelectionWithoutChangingParameters() {
        val curve = launch()
        val first = PeqBand(id = "first", frequency = 300.0, gain = 5.25)
        val selected = PeqBand(id = "second", frequency = 1234.567, gain = -6.75)
        curve.setState(PeqState(listOf(first, selected)), false)
        assertFalse(curve.isMarkerVisible)
        assertNull(draw(curve))
        curve.showMarker()
        assertEquals(first.id, draw(curve)!!.bandId)
        curve.hideMarker()
        curve.setSelectedBand(selected.id)
        assertNull(draw(curve))
        curve.showMarker()
        val marker = draw(curve)!!
        assertEquals(selected.id, marker.bandId)
        assertEquals(selected.frequency, marker.frequency, 0.0)
        assertEquals(selected.gain, marker.gain, 0.0)
        assertTrue(marker.frequencyText.contains("1234.567"))
        assertEquals("-6.75 dB", marker.gainText)
        assertTrue(curve.contentDescription.contains(marker.frequencyText))
    }

    @Test fun hidingClearsPointLabelsAndCrosshairUntilExplicitlyShownAgain() {
        val curve = launch()
        val band = PeqBand(id = "selected", frequency = 678.125, gain = 3.0)
        curve.setState(PeqState(listOf(band)), false)
        curve.showMarker(band.id)
        assertNotNull(draw(curve))
        curve.hideMarker()
        assertFalse(curve.isMarkerVisible)
        assertNull(curve.renderedMarker)
        assertNull(draw(curve))
        assertEquals(curve.context.getString(R.string.peq_response_title), curve.contentDescription)
        curve.setState(PeqState(listOf(band.copy(gain = -3.0))), false)
        assertNull(draw(curve))
        curve.showMarker()
        assertEquals(-3.0, draw(curve)!!.gain, 0.0)
    }

    @Test fun selectedParametersAndMarkerPositionUpdateBeforeCurveWorkerFinishes() {
        val curve = launch()
        val band = PeqBand(id = "selected", frequency = 100.0, gain = -10.0)
        curve.setSelectedBand(band.id)
        curve.setState(PeqState(listOf(band)), false)
        curve.showMarker()
        val before = draw(curve)!!
        val changed = band.copy(frequency = 2500.0, gain = 9.5)
        curve.setState(PeqState(listOf(changed)), false)
        val after = draw(curve)!!
        assertEquals(changed.frequency, after.frequency, 0.0)
        assertEquals(changed.gain, after.gain, 0.0)
        assertTrue(after.x > before.x)
        assertTrue(after.y < before.y)
        assertEquals(after.y, after.horizontalCrosshair.y1, 0f)
        assertEquals(after.x, after.verticalCrosshair.x1, 0f)
        assertTrue(after.horizontalCrosshair.y1 < before.horizontalCrosshair.y1)
        assertTrue(after.verticalCrosshair.x1 > before.verticalCrosshair.x1)
        assertEquals(after.plotBounds.left, after.gainAxisMarker.x, 0f)
        assertEquals(after.y, after.gainAxisMarker.y, 0f)
        assertEquals(after.x, after.frequencyAxisMarker.x, 0f)
        assertEquals(after.plotBounds.bottom, after.frequencyAxisMarker.y, 0f)
        assertTrue(after.gainAxisMarker.y < before.gainAxisMarker.y)
        assertTrue(after.frequencyAxisMarker.x > before.frequencyAxisMarker.x)
        assertEquals("+9.50 dB", after.gainText)
    }

    @Test fun everyFilterShowsItsActualGainAndDeletingSelectionFallsBack() {
        val curve = launch()
        val first = PeqBand(id = "first")
        for (type in PeqFilterType.entries) {
            val band = PeqBand(id = "selected", type = type, gain = 20.0)
            curve.setSelectedBand(band.id)
            curve.setState(PeqState(listOf(first, band)), false)
            curve.showMarker()
            assertEquals(20.0, draw(curve)!!.gain, 0.0)
            assertEquals("+20.00 dB", draw(curve)!!.gainText)
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
                curve.showMarker()
                val marker = draw(curve)!!
                assertEquals(frequency, marker.frequency, 0.0)
                assertEquals(gain, marker.gain, 0.0)
                assertTrue(marker.plotBounds.contains(marker.labelBounds))
                assertTrue(marker.x - marker.radius >= marker.plotBounds.left)
                assertTrue(marker.x + marker.radius <= marker.plotBounds.right)
                assertTrue(marker.y - marker.radius >= marker.plotBounds.top)
                assertTrue(marker.y + marker.radius <= marker.plotBounds.bottom)
                assertEquals(3f * curve.resources.displayMetrics.density, marker.radius, 0f)
                assertEquals(marker.plotBounds.left, marker.horizontalCrosshair.x1, 0f)
                assertEquals(marker.plotBounds.right, marker.horizontalCrosshair.x2, 0f)
                assertEquals(marker.y, marker.horizontalCrosshair.y1, 0f)
                assertEquals(marker.y, marker.horizontalCrosshair.y2, 0f)
                assertEquals(marker.plotBounds.top, marker.verticalCrosshair.y1, 0f)
                assertEquals(marker.plotBounds.bottom, marker.verticalCrosshair.y2, 0f)
                assertEquals(marker.x, marker.verticalCrosshair.x1, 0f)
                assertEquals(marker.x, marker.verticalCrosshair.x2, 0f)
                val density = curve.resources.displayMetrics.density
                assertEquals(1.25f * density, marker.horizontalCrosshair.strokeWidth, 0f)
                assertEquals(1.25f * density, marker.verticalCrosshair.strokeWidth, 0f)
                assertEquals(marker.horizontalCrosshair.x1, marker.gainAxisMarker.x, 0f)
                assertEquals(marker.horizontalCrosshair.y1, marker.gainAxisMarker.y, 0f)
                assertEquals(marker.verticalCrosshair.x2, marker.frequencyAxisMarker.x, 0f)
                assertEquals(marker.verticalCrosshair.y2, marker.frequencyAxisMarker.y, 0f)
                for (dot in listOf(marker.gainAxisMarker, marker.frequencyAxisMarker)) {
                    assertEquals(marker.radius, dot.radius, 0f)
                    val outerRadius = dot.radius + density
                    assertTrue(dot.x - outerRadius >= 0f)
                    assertTrue(dot.x + outerRadius <= curve.width)
                    assertTrue(dot.y - outerRadius >= 0f)
                    assertTrue(dot.y + outerRadius <= curve.height)
                }
                if (frequency == 20.0) assertTrue(marker.labelBounds.left > marker.x)
                else assertTrue(marker.labelBounds.right < marker.x)
                assertEquals(band, curve.requestedState!!.bands.single())
            }
        }
    }

    @Test fun unsetFrequencyMarkerUsesLeftEdgeAndShowsActualZeroWithFiniteBw() {
        val curve = launch(widthDp = 280, heightDp = 160)
        val band = PeqBand(id = "new", frequency = 0.0, gain = 0.0, q = 0.5,
            qualityDisplay = PeqQualityDisplay.BW)
        curve.setState(PeqState(listOf(band)), false)
        curve.showMarker(band.id)
        val marker = draw(curve)!!
        assertEquals(0.0, marker.frequency, 0.0)
        assertEquals("0 Hz", marker.frequencyText)
        assertEquals("0.00 dB", marker.gainText)
        assertNull(marker.qText)
        assertEquals("BW: 2.543", marker.bwText)
        assertEquals(PeqResponse.bandwidthOctaves(0.5, 0.0), marker.bw!!, 0.0)
        assertTrue(marker.bw!!.isFinite())
        assertEquals(marker.plotBounds.left + marker.radius, marker.x, 0f)
        assertTrue(marker.plotBounds.contains(marker.labelBounds))
        assertEquals(marker.x, marker.verticalCrosshair.x1, 0f)
        assertEquals(marker.plotBounds.left, marker.horizontalCrosshair.x1, 0f)
        assertEquals(marker.plotBounds.left, marker.gainAxisMarker.x, 0f)
        assertEquals(marker.x, marker.frequencyAxisMarker.x, 0f)
        assertEquals(marker.plotBounds.bottom, marker.frequencyAxisMarker.y, 0f)
    }

    @Test fun landscapeStillKeepsRightmostSelectedBandLabelInsideGraph() {
        val curve = launch(widthDp = 820, heightDp = 160)
        val band = PeqBand(frequency = 20000.0, gain = 100.0)
        curve.setState(PeqState(listOf(band), gainRange = 100.0), false)
        curve.showMarker()
        val marker = draw(curve)!!
        assertTrue(marker.plotBounds.contains(marker.labelBounds))
        assertTrue(marker.labelBounds.right < marker.x)
    }

    @Test fun everyFilterShowsOnlyTheSelectedQualityModeToThreeDecimalPlaces() {
        val curve = launch()
        for (type in PeqFilterType.entries) {
            val original = PeqBand(type = type, frequency = 4321.125, q = 1.234567)
            for (mode in PeqQualityDisplay.entries) {
                val band = original.copy(qualityDisplay = mode)
                curve.setState(PeqState(listOf(band)), false)
                curve.showMarker()
                val marker = draw(curve)!!
                val expectedBw = PeqResponse.bandwidthOctaves(band.q, band.frequency)
                assertEquals(band.q, marker.q, 0.0)
                if (mode == PeqQualityDisplay.Q) {
                    assertEquals("Q: 1.235", marker.qText)
                    assertNull(marker.bw)
                    assertNull(marker.bwText)
                    assertTrue(curve.contentDescription.contains(marker.qText!!))
                    assertFalse(curve.contentDescription.contains("BW:"))
                } else {
                    assertEquals(expectedBw, marker.bw!!, 0.0)
                    assertEquals("BW: ${String.format(Locale.ROOT, "%.3f", expectedBw)}", marker.bwText)
                    assertNull(marker.qText)
                    assertTrue(curve.contentDescription.contains(marker.bwText!!))
                    assertFalse(curve.contentDescription.contains("Q:"))
                }
                assertEquals(band, curve.requestedState!!.bands.single())
                assertFalse(curve.contentDescription.contains("倍频程"))
            }
        }
    }

    @Test fun qModeShowsTrailingZeroPrecisionForEveryFilter() {
        val curve = launch()
        for (type in PeqFilterType.entries) {
            val band = PeqBand(type = type, q = 2.0)
            curve.setState(PeqState(listOf(band)), false)
            curve.showMarker()
            val marker = draw(curve)!!
            assertEquals("Q: 2.000", marker.qText)
            assertNull(marker.bw)
            assertNull(marker.bwText)
            assertFalse(curve.contentDescription.contains("BW"))
        }
    }

    @Test fun qAndBwLabelValuesUpdateWhileMarkerIsVisible() {
        val curve = launch()
        val band = PeqBand(id = "selected", frequency = 600.0, q = 0.5, qualityDisplay = PeqQualityDisplay.BW)
        curve.setState(PeqState(listOf(band)), false)
        curve.showMarker(band.id)
        val before = draw(curve)!!
        val updated = band.copy(q = 4.32198, frequency = 2800.0)
        curve.setState(PeqState(listOf(updated)), false)
        val after = draw(curve)!!
        assertEquals(updated.q, after.q, 0.0)
        assertNull(after.qText)
        assertTrue(after.bw!! < before.bw!!)
        assertNotEquals(before.bwText, after.bwText)
        curve.setState(PeqState(listOf(updated.copy(qualityDisplay = PeqQualityDisplay.Q))), false)
        val switched = draw(curve)!!
        assertEquals("Q: 4.322", switched.qText)
        assertNull(switched.bwText)
        assertEquals(after.x, switched.x, 0f)
        assertEquals(after.y, switched.y, 0f)
    }

    @Test fun disabledBandPreviewChangesOnlyTheCurveBankAndEndsWhenTheMarkerHides() {
        val curve = launch()
        val first = PeqBand("first-disabled", enabled = false, gain = 8.0, frequency = 1000.0)
        val second = PeqBand("second-disabled", enabled = false, gain = -9.0, frequency = 3000.0)
        val state = PeqState(listOf(first, second))
        val audioPacket = PeqProtocol.payload(state, true)
        curve.setState(state, false)
        curve.setPreviewBand(first.id)
        assertSame(state, curve.requestedPlotState)
        curve.showMarker(first.id)
        val preview = requireNotNull(curve.requestedPlotState)
        assertTrue(preview.bands.first().enabled)
        assertFalse(preview.bands.last().enabled)
        assertSame(state, curve.requestedState)
        assertArrayEquals(audioPacket, PeqProtocol.payload(requireNotNull(curve.requestedState), true))
        assertEquals(0.0, PeqResponse.responseDb(state, 1000.0), 0.0)
        assertEquals(8.0, PeqResponse.responseDb(preview, 1000.0), 0.001)
        assertEquals("未开启", draw(curve)!!.statusText)
        assertTrue(curve.contentDescription.contains("未开启"))
        assertNull(PeqPreviewSession.current())
        curve.hideMarker()
        assertSame(state, curve.requestedPlotState)
        assertNull(draw(curve))
        curve.showMarker(first.id)
        assertSame(state, curve.requestedPlotState) // Merely showing the marker does not re-enable preview.
    }

    @Test fun disabledPreviewFollowsOnlyTheSelectedBandAndCanBeTurnedOff() {
        val curve = launch()
        val state = PeqState(listOf(PeqBand("first", enabled = false, gain = 6.0),
            PeqBand("second", enabled = false, gain = -3.0)))
        curve.setState(state, false)
        curve.showMarker("first")
        curve.setPreviewBand("first")
        assertTrue(curve.requestedPlotState!!.bands.first().enabled)
        curve.showMarker("second")
        assertSame(state, curve.requestedPlotState)
        curve.setPreviewBand("second")
        assertEquals(listOf(false, true), curve.requestedPlotState!!.bands.map { it.enabled })
        curve.setDisplayOptions(PeqEditorSettings(previewDisabledBands = false))
        assertSame(state, curve.requestedPlotState)
        assertEquals("未开启", draw(curve)!!.statusText)
        curve.setDisplayOptions(PeqEditorSettings())
        assertEquals(listOf(false, true), curve.requestedPlotState!!.bands.map { it.enabled })
        curve.setPreviewBand(null)
        assertSame(state, curve.requestedPlotState)
    }

    @Test fun markerVisibilityAndTextSizeOptionsChangeOnlyTheirLabels() {
        val curve = launch()
        val band = PeqBand("selected", frequency = 1234.5, gain = 6.0, q = 2.5)
        val state = PeqState(listOf(band))
        curve.setState(state, false)
        curve.showMarker(band.id)
        val original = draw(curve)!!
        curve.setDisplayOptions(PeqEditorSettings(markerTextSizeSp = 20, showMarkerFrequency = false,
            showMarkerGain = false, showMarkerFilterType = false))
        val large = draw(curve)!!
        assertEquals(listOf("Q: 2.500"), large.labelLines)
        assertEquals("", large.frequencyText)
        assertEquals("", large.gainText)
        assertEquals(20f, large.textSizeSp, 0.01f)
        assertEquals(original.x, large.x, 0f)
        assertEquals(original.y, large.y, 0f)
        assertFalse(curve.contentDescription.contains("Hz"))
        assertFalse(curve.contentDescription.contains("dB"))
        assertSame(state, curve.requestedState)
        curve.setDisplayOptions(PeqEditorSettings(showMarkerFrequency = false, showMarkerGain = false,
            showMarkerQuality = false, showMarkerFilterType = false))
        val hidden = draw(curve)!!
        assertTrue(hidden.labelLines.isEmpty())
        assertTrue(hidden.labelBounds.isEmpty)
        assertNull(hidden.qText)
        assertEquals(original.x, hidden.x, 0f)
        assertEquals(original.y, hidden.y, 0f)
        assertEquals(curve.context.getString(R.string.peq_response_title), curve.contentDescription)
    }

    @Test fun externalEqResponseNeverUsesDisabledBandPreviewOrPeqMarkerOptions() {
        val curve = launch()
        curve.setState(PeqState(listOf(PeqBand("disabled", enabled = false, gain = 8.0))), false)
        curve.showMarker("disabled")
        curve.setPreviewBand("disabled")
        curve.setExternalResponse(doubleArrayOf(20.0, 1000.0, 20000.0), doubleArrayOf(2.0, -3.0, 4.0))
        assertNull(curve.requestedState)
        assertNull(curve.requestedPlotState)
        assertFalse(curve.isMarkerVisible)
        fun bitmap() = Bitmap.createBitmap(curve.width, curve.height, Bitmap.Config.ARGB_8888).also {
            curve.draw(Canvas(it))
        }
        val before = bitmap()
        try {
            curve.setDisplayOptions(PeqEditorSettings(markerTextSizeSp = 20))
            curve.setPreviewBand("disabled")
            val after = bitmap()
            try { assertTrue(before.sameAs(after)) } finally { after.recycle() }
            assertNull(curve.requestedState)
            assertNull(curve.requestedPlotState)
            assertNull(curve.renderedMarker)
        } finally { before.recycle() }
    }

    @Test fun filterTypeLabelTracksTheCurrentBandAndCanHideWithoutAffectingOtherMarkerContent() {
        val curve = launch()
        val options = PeqEditorSettings(showMarkerFrequency = false, showMarkerGain = false,
            showMarkerQuality = false)
        curve.setDisplayOptions(options)
        PeqFilterType.entries.forEach { type ->
            val band = PeqBand("disabled", type = type, enabled = false, gain = 3.0)
            val state = PeqState(listOf(band))
            curve.setState(state, false)
            curve.showMarker(band.id)
            curve.setPreviewBand(band.id)
            val label = PeqBandAdapter.typeLabel(curve.context, type)
            val marker = draw(curve)!!
            assertEquals(label, marker.filterTypeText)
            assertEquals(listOf("未开启", label), marker.labelLines)
            assertTrue(curve.contentDescription.contains(label))
            assertSame(state, curve.requestedState)
            assertTrue(curve.requestedPlotState!!.bands.single().enabled)
            curve.setDisplayOptions(options.copy(showMarkerFilterType = false))
            val hidden = draw(curve)!!
            assertNull(hidden.filterTypeText)
            assertEquals(listOf("未开启"), hidden.labelLines)
            assertFalse(curve.contentDescription.contains(label))
            assertTrue(curve.requestedPlotState!!.bands.single().enabled)
            assertFalse(curve.requestedState!!.bands.single().enabled)
            curve.setDisplayOptions(options)
        }
    }

    @Test fun bandNumberCanJoinEveryFilterTypeOrAppearAloneWithoutChangingTheAudioBank() {
        val curve = launch()
        val options = PeqEditorSettings(showMarkerFrequency = false, showMarkerGain = false,
            showMarkerQuality = false)
        curve.setDisplayOptions(options)
        PeqFilterType.entries.forEach { type ->
            val selected = PeqBand("selected", type = type, frequency = 1500.0, gain = 4.0)
            val state = PeqState(listOf(PeqBand("disabled-first", enabled = false), selected))
            val payload = PeqProtocol.payload(state, true)
            curve.setState(state, false)
            curve.showMarker(selected.id)
            val typeLabel = PeqBandAdapter.typeLabel(curve.context, type)
            assertEquals(listOf(typeLabel), draw(curve)!!.labelLines)
            assertFalse(curve.contentDescription.contains("第2段"))

            curve.setDisplayOptions(options.copy(showMarkerBandNumber = true))
            val numbered = draw(curve)!!
            assertEquals(listOf("$typeLabel • 第2段"), numbered.labelLines)
            assertEquals(typeLabel, numbered.filterTypeText)
            assertTrue(curve.contentDescription.contains("$typeLabel • 第2段"))

            curve.setDisplayOptions(options.copy(showMarkerBandNumber = true, showMarkerFilterType = false))
            val numberOnly = draw(curve)!!
            assertEquals(listOf("第2段"), numberOnly.labelLines)
            assertNull(numberOnly.filterTypeText)
            assertTrue(curve.contentDescription.contains("第2段"))
            assertFalse(curve.contentDescription.contains(typeLabel))
            assertEquals(numbered.x, numberOnly.x, 0f)
            assertEquals(numbered.y, numberOnly.y, 0f)
            assertSame(state, curve.requestedState)
            assertArrayEquals(payload, PeqProtocol.payload(requireNotNull(curve.requestedState), true))
            curve.setDisplayOptions(options)
        }
    }

    @Test fun markerBandNumberTracksFullBankOrderAndDeletionDuringDisabledBandPreview() {
        val curve = launch()
        curve.setDisplayOptions(PeqEditorSettings(showMarkerBandNumber = true,
            showMarkerFilterType = false, showMarkerFrequency = false,
            showMarkerGain = false, showMarkerQuality = false))
        val first = PeqBand("first", enabled = false)
        val middle = PeqBand("middle", enabled = false)
        val selected = PeqBand("selected", enabled = false, gain = 6.0)
        curve.setState(PeqState(listOf(first, middle, selected)), false)
        curve.showMarker(selected.id)
        curve.setPreviewBand(selected.id)
        assertEquals(listOf("未开启", "第3段"), draw(curve)!!.labelLines)
        assertEquals(listOf(false, false, true), curve.requestedPlotState!!.bands.map { it.enabled })

        curve.setState(PeqState(listOf(middle, selected, first)), false)
        assertEquals(listOf("未开启", "第2段"), draw(curve)!!.labelLines)
        assertEquals(selected.id, draw(curve)!!.bandId)
        assertEquals(listOf(false, true, false), curve.requestedPlotState!!.bands.map { it.enabled })
        curve.setState(PeqState(listOf(first, middle)), false)
        val fallback = draw(curve)!!
        assertEquals(first.id, fallback.bandId)
        assertEquals(listOf("未开启", "第1段"), fallback.labelLines)
        assertFalse(curve.requestedPlotState!!.bands.any { it.enabled })
        curve.setState(PeqState(emptyList()), false)
        assertNull(draw(curve))
        assertEquals(curve.context.getString(R.string.peq_response_title), curve.contentDescription)
    }

    @Test fun onlyMarkerGainLineUsesPositiveRedNegativeBlueAndZeroThemeTextColor() {
        val curve = launch()
        val normal = MaterialColors.getColor(curve, com.google.android.material.R.attr.colorOnSurface)
        curve.setDisplayOptions(PeqEditorSettings(showMarkerBandNumber = true))
        val initial = PeqBand("selected", enabled = false, frequency = 1250.0, q = 2.5)
        // Reuse one view across sign changes to catch Paint color leaking into other lines.
        for ((gain, color) in listOf(6.0 to Color.rgb(211, 47, 47),
                -4.0 to Color.rgb(25, 118, 210), 0.0 to normal, 2.0 to Color.rgb(211, 47, 47))) {
            val band = initial.copy(gain = gain)
            curve.setState(PeqState(listOf(band)), false)
            curve.showMarker(band.id)
            val marker = draw(curve)!!
            val gainLine = marker.labelLines.indexOf(marker.gainText)
            assertTrue(gainLine >= 0)
            assertEquals(marker.labelLines.size, marker.labelColors.size)
            marker.labelColors.forEachIndexed { index, actual ->
                assertEquals(if (index == gainLine) color else normal, actual)
            }
            assertEquals(band, curve.requestedState!!.bands.single())
        }
        curve.setDisplayOptions(PeqEditorSettings(showMarkerBandNumber = true, showMarkerGain = false))
        val hiddenGain = draw(curve)!!
        assertTrue(hiddenGain.gainText.isEmpty())
        assertTrue(hiddenGain.labelColors.isNotEmpty())
        assertTrue(hiddenGain.labelColors.all { it == normal })
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
