package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import me.timschneeberger.rootlessjamesdsp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** Measure actual glyph pixels, not TextView's compound-drawable padding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PeqBandNumberDrawingTest {
    @Test fun numberAndSettingsGlyphStayWithinOneCompactCenteredBlockAtBothPhoneWidths() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_RootlessJamesDSP)
        val card = LayoutInflater.from(context).inflate(R.layout.peq_band_row, null)
        val badge = card.findViewById<PeqBandNumberView>(R.id.peq_band_number)
        badge.background = null
        badge.setTextColor(Color.BLACK)
        for (width in listOf(320, 411)) {
            for (number in listOf("1", "88", "170")) {
                badge.text = number
                card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                card.layout(0, 0, card.measuredWidth, card.measuredHeight)
                assertEquals("The entire header slot remains tappable", 40, badge.height)
                val bitmap = Bitmap.createBitmap(badge.width, badge.height, Bitmap.Config.ARGB_8888)
                try {
                    badge.draw(Canvas(bitmap))
                    val rows = (0 until bitmap.height).filter { y ->
                        (0 until bitmap.width).any { x -> Color.alpha(bitmap.getPixel(x, y)) >= 32 }
                    }
                    assertTrue("Number and icon must both render: $number at $width dp", rows.size >= 14)
                    assertTrue("Visible content fits the requested 28 dp block", rows.last() - rows.first() + 1 <= 28)
                    assertTrue("The compact block is centered inside the unchanged hit target",
                        abs((rows.first() + rows.last()) / 2f - (badge.height - 1) / 2f) <= 2f)
                    val gaps = rows.zipWithNext().map { (previous, next) -> next - previous - 1 }
                    assertTrue("No font-line leading leaves a large gap before the settings glyph", gaps.maxOrNull()!! <= 3)
                    val directory = File("build/reports/peq-filter-raster").apply { mkdirs() }
                    File(directory, "number-$width-$number.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { bitmap.recycle() }
            }
        }
    }
}
