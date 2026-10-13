package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ActivityGraphicEqBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Uses the real activity layout and AppCompat action-bar sizing, including both action icons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    qualifiers = "zh-rCN-w320dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PeqSystemPresetToolbarTest {
    @Test fun compactPhoneKeepsSystemPresetsAndLongSummaryOnOneCenteredLineBelowPeq() = checkToolbar(320)

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-port-mdpi")
    fun regularPhoneKeepsTheSameCenteredTitleAndIndependentTouchTargets() = checkToolbar(411)

    private fun checkToolbar(width: Int) {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        try {
            val host = controller.get()
            val binding = host.binding
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1f, host.resources.configuration.fontScale, 0f)
            binding.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            binding.root.layout(0, 0, width, 720)

            val toolbar = binding.toolbar
            val header = toolbar.parent as ViewGroup
            val appBar = header.parent as ViewGroup
            val title = children(toolbar).filterIsInstance<TextView>().single { it.text == toolbar.title }
            val summary = binding.peqToolbarSummary
            val summaryRow = binding.peqToolbarSummaryRow
            val back = children(toolbar).filterIsInstance<ImageButton>().single { it.drawable != null }
            val pin = toolbar.findViewById<View>(R.id.peq_pin_response)
            assertNotNull("The pin action must be measured, not omitted from the test toolbar", pin)

            // Save the native drawing before assertions so a layout regression leaves visible evidence.
            val bitmap = Bitmap.createBitmap(appBar.width, appBar.height, Bitmap.Config.ARGB_8888)
            try {
                appBar.draw(Canvas(bitmap))
                val directory = File("build/reports/peq-system-presets-toolbar").apply { mkdirs() }
                File(directory, "toolbar-$width.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }

            assertEquals(64, header.height)
            assertEquals(width / 2f, bounds(header, title).exactCenterX(), 1f)
            assertEquals(width / 2f, bounds(header, summaryRow).exactCenterX(), 1f)
            assertEquals(13f, summary.textSize, 0.1f)
            assertEquals(13f, binding.peqSystemPresets.textSize, 0.1f)
            assertTrue("The native subtitle must not duplicate the shared row", toolbar.subtitle.isNullOrEmpty())
            assertReadable(title)
            assertReadable(summary)
            assertReadable(binding.peqSystemPresets)

            val controls = linkedMapOf(
                "back" to back,
                "system presets" to binding.peqSystemPresets,
                "title" to title,
                "summary" to summary,
                "pin" to pin!!
            ).mapValues { (_, view) -> bounds(header, view) }
            controls.forEach { (name, rect) ->
                assertTrue("$name stays inside the toolbar: $rect",
                    rect.left >= 0 && rect.top >= 0 && rect.right <= header.width && rect.bottom <= header.height)
            }
            val entries = controls.entries.toList()
            for (first in entries.indices) for (second in first + 1 until entries.size) {
                val a = entries[first]
                val b = entries[second]
                assertFalse("$width dp: ${a.key} ${a.value} overlaps ${b.key} ${b.value}",
                    Rect.intersects(a.value, b.value))
            }
            assertTrue("The shared row is below PEQ",
                controls.getValue("title").bottom <= bounds(header, summaryRow).top)
            assertEquals("Preset and summary text share the same baseline",
                controls.getValue("system presets").top + binding.peqSystemPresets.baseline,
                controls.getValue("summary").top + summary.baseline)
            val separator = bounds(header, binding.peqToolbarSummarySeparator)
            assertEquals("-", binding.peqToolbarSummarySeparator.text.toString().trim())
            assertTrue(controls.getValue("system presets").right <= separator.left)
            assertTrue(separator.right <= controls.getValue("summary").left)

            // Only the preset entry owns the action, rather than the whole summary line.
            assertTrue(binding.peqSystemPresets.performClick())
            assertEquals(1, host.presetClicks)
            assertFalse(summary.performClick())
            assertFalse(summaryRow.performClick())
            assertEquals(1, host.presetClicks)

            summary.text = host.getString(R.string.peq_summary, 4, "0.5", "18")
            binding.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            binding.root.layout(0, 0, width, 720)
            assertEquals(width / 2f, bounds(header, summaryRow).exactCenterX(), 1f)
            assertReadable(summary)
        } finally {
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun assertReadable(text: TextView) {
        assertEquals("${text.text} stays on one line", 1, text.lineCount)
        assertEquals("${text.text} must not be ellipsized", 0, text.layout.getEllipsisCount(0))
        assertTrue("${text.text} has room for its entire line",
            text.layout.getLineWidth(0) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 1f)
        assertTrue("${text.text} layout ${text.layout.height} fits height ${text.height} " +
            "minus vertical padding ${text.compoundPaddingTop + text.compoundPaddingBottom}",
            text.layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
    }

    private fun bounds(root: ViewGroup, child: View) = Rect().also {
        child.getDrawingRect(it)
        root.offsetDescendantRectToMyCoords(child, it)
    }

    private fun children(root: ViewGroup): List<View> = (0 until root.childCount).map(root::getChildAt)

    class HostActivity : AppCompatActivity() {
        lateinit var binding: ActivityGraphicEqBinding
        var presetClicks = 0
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            binding = ActivityGraphicEqBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            supportActionBar!!.setTitle(R.string.fusion_peq_title)
            supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            binding.peqToolbarSummary.text = getString(R.string.peq_summary, 130, "0.5", "12")
            binding.peqSystemPresets.setOnClickListener { presetClicks++ }
        }

        override fun onCreateOptionsMenu(menu: Menu): Boolean {
            menuInflater.inflate(R.menu.peq_editor, menu)
            return true
        }
    }
}
