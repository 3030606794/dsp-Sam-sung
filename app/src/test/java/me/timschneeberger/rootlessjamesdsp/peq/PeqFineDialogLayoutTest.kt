package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import me.timschneeberger.rootlessjamesdsp.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h720dp-port")
class PeqFineDialogLayoutTest {
    private val host = Robolectric.buildActivity(HostActivity::class.java).setup()
    @After fun close() { host.pause().stop().destroy() }

    private fun inflate(layout: Int, widthDp: Int): ViewGroup =
        (host.get().layoutInflater.inflate(layout, null) as ViewGroup).also { measure(it, widthDp) }
    private fun measure(root: View, widthDp: Int) {
        val width = (widthDp * root.resources.displayMetrics.density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }
    private fun bounds(root: ViewGroup, child: View) = Rect().also {
        child.getDrawingRect(it)
        root.offsetDescendantRectToMyCoords(child, it)
    }

    @Test fun frequencyAndGainHaveFullWidthTitlesAboveWiderTracksAndCompactEditableValues() {
        for (width in listOf(272, 363)) {
            val root = inflate(R.layout.peq_band_dialog, width)
            listOf(Triple(R.id.peq_frequency_label, R.id.peq_frequency_row, R.id.peq_frequency_slider),
                Triple(R.id.peq_gain_label, R.id.peq_gain_row, R.id.peq_gain_slider)).forEach { (labelId, rowId, sliderId) ->
                val label = root.findViewById<TextView>(labelId)
                val section = root.findViewById<View>(rowId)
                val slider = root.findViewById<View>(sliderId)
                assertEquals(section.width, label.width)
                assertTrue(bounds(root, label).bottom <= bounds(root, slider).top)
                assertEquals(bounds(root, section).left, bounds(root, slider).left)
            }
            assertEquals(View.GONE, root.findViewById<View>(R.id.peq_quality_label).visibility)
            assertEquals(0, root.findViewById<View>(R.id.peq_quality_range_hint).paddingStart)
            listOf(R.id.peq_frequency_box to R.id.peq_frequency_input,
                R.id.peq_gain_box to R.id.peq_gain_input, R.id.peq_quality_box to R.id.peq_quality_input).forEach { (boxId, inputId) ->
                val box = root.findViewById<TextInputLayout>(boxId)
                val input = root.findViewById<EditText>(inputId)
                assertEquals(76f, box.width / root.resources.displayMetrics.density, 1f)
                assertNull(box.suffixText)
                assertEquals(1, input.maxLines)
                val preciseNumber = "12345.123456789"
                input.setText(preciseNumber)
                input.setSelection(preciseNumber.length)
                assertEquals(preciseNumber, input.text.toString())
                assertEquals(preciseNumber.length, input.selectionStart)
            }
        }
    }

    @Test fun customTitleFitsItsBandNumberBesideAnAbsolutelyCenteredPowerAndRightAlignedMasterLock() {
        for (width in listOf(272, 363)) {
            val root = inflate(R.layout.peq_band_dialog_title, width)
            val title = root.findViewById<TextView>(R.id.peq_band_dialog_title)
            val power = root.findViewById<MaterialButton>(R.id.peq_band_dialog_power)
            val lock = root.findViewById<MaterialButton>(R.id.peq_band_dialog_lock)
            title.text = host.get().getString(R.string.peq_fine_band_title, 170)
            measure(root, width)
            assertEquals("精调频段\n第170段", title.text.toString())
            assertEquals(2, title.lineCount)
            for (line in 0 until title.lineCount) assertEquals(0, title.layout.getEllipsisCount(line))
            assertTrue(title.right <= power.left)
            assertTrue(power.right <= lock.left)
            assertEquals(root.width / 2f, (power.left + power.right) / 2f, 1f)
            assertEquals((lock.top + lock.bottom) / 2f, (power.top + power.bottom) / 2f, 1f)
            assertEquals(root.width - root.paddingRight, lock.right)
            listOf(power, lock).forEach { button ->
                assertEquals(48f, button.width / root.resources.displayMetrics.density, 1f)
                assertEquals(48f, button.height / root.resources.displayMetrics.density, 1f)
                assertEquals(28f, button.iconSize / root.resources.displayMetrics.density, 1f)
                assertTrue(button.isClickable)
            }
        }
    }

    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
        }
    }
}
