package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.view.EqPreview
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import kotlin.math.ln
import kotlin.math.roundToInt

/** Real chart drawing with explicit response fixtures; these fixtures do not model audio DSP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ResponseGraphDomainTest {
    private var controller: ActivityController<HostActivity>? = null

    @After fun close() { controller?.pause()?.stop()?.destroy() }

    @Test fun equalizerQueriesTwentyHertzToTwentyFourKhzAndDrawsBothAxisEndpointsExactly() {
        val frequencies = EqPreview.frequencySamples()
        assertEquals(20.0, frequencies.first(), 0.0)
        assertEquals(24000.0, frequencies.last(), 0.0)
        assertTrue("The existing FIR/IIR calculator receives actual 20–24 kHz queries",
            frequencies.any { it > 20000.0 && it < 24000.0 })
        assertTrue(frequencies.all { it in 20.0..24000.0 })
        assertTrue(frequencies.asSequence().zipWithNext().all { (a, b) -> a < b })
        val chart = launch()
        chart.setExternalResponse(frequencies, DoubleArray(frequencies.size) { Double.NaN }, 12.0)
        val empty = draw(chart)
        chart.setExternalResponse(frequencies, DoubleArray(frequencies.size) { 6.0 }, 12.0)
        val rendered = draw(chart)
        try {
            assertEquals("20", chart.renderedFrequencyLabels.first().text)
            assertEquals("24k", chart.renderedFrequencyLabels.last().text)
            assertTrue("The extra 20k tick must not overlap the 24k endpoint label",
                chart.renderedFrequencyLabels.zipWithNext().all { (left, right) ->
                    left.bounds.right < right.bounds.left
                })
            val plot = plot(chart)
            val bounds = RectF()
            path(chart, "line").computeBounds(bounds, true)
            assertEquals(plot.left, bounds.left, 0f)
            assertEquals(plot.right, bounds.right, 0f)
            assertNotEquals("The extended upper range draws calculated data instead of a blank wedge",
                pixel(empty, plot, 22000.0, 1.0, 20.0, 24000.0),
                pixel(rendered, plot, 22000.0, 1.0, 20.0, 24000.0))
            save(rendered, "eq-full-domain-20hz-24khz")
        } finally { empty.recycle(); rendered.recycle() }
    }

    @Test fun unavailableLowFrequencyResponseRemainsBlankInsteadOfInventingATriangle() {
        val chart = launch()
        val frequencies = doubleArrayOf(1.0, 2.0, 20.0, 200.0, 20000.0)
        chart.setExternalResponse(frequencies, DoubleArray(frequencies.size) { Double.NaN }, 12.0)
        val empty = draw(chart)
        chart.setExternalResponse(frequencies, doubleArrayOf(Double.NaN, Double.NaN, 6.0, 6.0, 6.0), 12.0)
        val rendered = draw(chart)
        try {
            val plot = plot(chart)
            assertEquals("No curve or fill may claim an unknown sub-20 Hz response",
                pixel(empty, plot, 5.5, 1.0), pixel(rendered, plot, 5.5, 1.0))
            assertNotEquals(pixel(empty, plot, 100.5, 3.0), pixel(rendered, plot, 100.5, 3.0))
            save(rendered, "unknown-sub20-remains-blank")
        } finally { empty.recycle(); rendered.recycle() }
    }

    @Test fun gapsCloseEachKnownSegmentWithoutDroppingFillOrBridgingUnknownData() {
        val chart = launch()
        val frequencies = doubleArrayOf(1.0, 2.0, 20.0, 200.0, 2000.0, 20000.0)
        chart.setExternalResponse(frequencies, DoubleArray(frequencies.size) { Double.NaN }, 12.0)
        val empty = draw(chart)
        chart.setExternalResponse(frequencies, doubleArrayOf(6.0, 6.0, Double.NaN, Double.NaN, 6.0, 6.0), 12.0)
        val rendered = draw(chart)
        try {
            val plot = plot(chart)
            assertNotEquals(pixel(empty, plot, 1.5, 3.0), pixel(rendered, plot, 1.5, 3.0))
            assertEquals(pixel(empty, plot, 100.5, 1.0), pixel(rendered, plot, 100.5, 1.0))
            assertNotEquals(pixel(empty, plot, 5000.5, 3.0), pixel(rendered, plot, 5000.5, 3.0))
        } finally { empty.recycle(); rendered.recycle() }
    }

    @Test fun externalChartsUseTheirOwnCalculatedDomainAfterAnEqualizerPreview() {
        val chart = launch()
        chart.setExternalResponse(EqPreview.frequencySamples(), DoubleArray(256), 12.0)
        draw(chart).recycle()
        chart.setExternalResponse(doubleArrayOf(20.0, 1000.0, 20000.0), doubleArrayOf(-24.0, 0.0, -18.0), 24.0)
        val rendered = draw(chart)
        try {
            assertEquals("20", chart.renderedFrequencyLabels.first().text)
            assertEquals("20k", chart.renderedFrequencyLabels.last().text)
            val bounds = RectF()
            path(chart, "line").computeBounds(bounds, true)
            assertEquals(plot(chart).left, bounds.left, 0f)
            assertEquals(plot(chart).right, bounds.right, 0f)
        } finally { rendered.recycle() }
    }

    private fun launch(): PeqResponseView {
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        return controller!!.get().chart.apply {
            measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY))
            layout(0, 0, 411, 220)
        }
    }

    private fun draw(chart: PeqResponseView): Bitmap =
        Bitmap.createBitmap(chart.width, chart.height, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE)
            chart.draw(Canvas(it))
        }

    private fun plot(chart: PeqResponseView): RectF = RectF(
        PeqResponseView::class.java.getDeclaredField("graphBounds").apply { isAccessible = true }.get(chart) as RectF)

    private fun path(chart: PeqResponseView, name: String): Path =
        PeqResponseView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(chart) as Path

    private fun pixel(bitmap: Bitmap, plot: RectF, hz: Double, db: Double,
                      minimum: Double = 1.0, maximum: Double = 20000.0): Int {
        val x = plot.left + (ln(hz / minimum) / ln(maximum / minimum) * plot.width()).toFloat()
        val y = plot.centerY() - (db / 24.0 * plot.height()).toFloat()
        return bitmap.getPixel(x.roundToInt(), y.roundToInt())
    }

    private fun save(bitmap: Bitmap, name: String) {
        val directory = File("build/reports/response-graph-domain").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    class HostActivity : FragmentActivity() {
        lateinit var chart: PeqResponseView
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            chart = PeqResponseView(this)
            setContentView(chart)
        }
    }
}
