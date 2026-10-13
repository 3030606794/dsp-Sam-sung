package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PeqFilterSelectorDrawingTest {
    @Test fun firstAndLastSavedAndMonitoredFiltersKeepRoundedCornersAndAnUncoveredBorder() {
        checkRoundedEdges()
    }

    @Test
    @Config(qualifiers = "w320dp-h720dp-port-night-mdpi")
    fun darkThemeKeepsTheSameClippingAndUsesItsOwnOutlineColor() {
        checkRoundedEdges()
    }

    private fun checkRoundedEdges() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_RootlessJamesDSP)
        val selector = PeqFilterSelector(context)
        selector.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        selector.layout(0, 0, selector.measuredWidth, selector.measuredHeight)
        val outline = MaterialColors.getColor(selector, com.google.android.material.R.attr.colorOutline)
        val purple = MaterialColors.getColor(selector, com.google.android.material.R.attr.colorPrimaryContainer)
        val backdrop = Color.MAGENTA
        for (type in listOf(PeqFilterType.PEAK, PeqFilterType.BAND_PASS)) {
            for (monitoring in listOf(false, true)) {
                selector.committedType = if (monitoring) PeqFilterType.HIGH_SHELF else type
                selector.setSelection(type)
                selector.monitoring = monitoring
                val option = selector.findViewById<View>(PeqFilterTypeDialog.buttonId(type))
                for (pressed in listOf(false, true)) {
                    option.isPressed = pressed
                    option.jumpDrawablesToCurrentState()
                    val bitmap = Bitmap.createBitmap(selector.width, selector.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(backdrop)
                        selector.draw(Canvas(bitmap))
                        val message = "$type / monitoring=$monitoring / pressed=$pressed"
                        val right = selector.width - 1
                        val bottom = selector.height - 1
                        // These pixels lie inside rectangular child backgrounds but outside
                        // the rounded parent. A highlight or ripple must never reach them.
                        listOf(2 to 2, right - 2 to 2, 2 to bottom - 2, right - 2 to bottom - 2).forEach { (x, y) ->
                            assertEquals("Rounded corner must reveal the backdrop: $message", backdrop, bitmap.getPixel(x, y))
                        }
                        assertEquals("Left outline remains above the child: $message", outline,
                            bitmap.getPixel(0, selector.height / 2))
                        assertEquals("Right outline remains above the child: $message", outline,
                            bitmap.getPixel(right, selector.height / 2))
                        if (!pressed) {
                            val interiorX = if (type == PeqFilterType.PEAK) 2 else right - 2
                            assertEquals("Clipping must preserve the actual selected fill: $message",
                                if (monitoring) Color.rgb(46, 125, 50) else purple,
                                bitmap.getPixel(interiorX, selector.height / 2))
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                option.isPressed = false
            }
        }
    }
}
