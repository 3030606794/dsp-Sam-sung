package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.ui.SliderPreferenceUi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactSliderLayoutTest {
    private fun context(fontScale: Float): ContextThemeWrapper {
        val app = RuntimeEnvironment.getApplication()
        val configuration = Configuration(app.resources.configuration).apply { this.fontScale = fontScale }
        return ContextThemeWrapper(app.createConfigurationContext(configuration), R.style.Theme_RootlessJamesDSP)
    }

    private fun layout(view: View, widthDp: Int) {
        val width = (widthDp * view.resources.displayMetrics.density).roundToInt()
        // TextView autosizing invalidates its first text layout after it learns
        // the final card width. Settle the following frame as a real view root
        // would, rather than inspecting/drawing a half-completed first pass.
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, width, view.measuredHeight)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun outputControlsStayOnOneLineAtSmallWidthAndLargeFont() {
        for (width in listOf(411, 320)) for (font in listOf(1f, 1.5f)) {
            val context = context(font)
            val root = LayoutInflater.from(context).inflate(R.layout.fragment_output_control, FrameLayout(context), false)
            val tiles = listOf(R.id.output_threshold, R.id.output_release, R.id.output_postgain, R.id.output_balance)
            val titles = listOf("阈值", "释放", "增益", "平衡")
            val values = listOf("-60.00 dB", "500.00 ms", "+15.00 dB", "右 100%")
            tiles.forEachIndexed { index, id ->
                root.findViewById<View>(id).apply {
                    findViewById<TextView>(R.id.output_label).text = titles[index]
                    findViewById<TextView>(R.id.output_value).text = values[index]
                }
            }
            layout(root, width)
            if (width == 411) capture(root, "output-rows-${(font * 100).roundToInt()}")
            val density = context.resources.displayMetrics.density
            tiles.forEach { id ->
                val tile = root.findViewById<View>(id)
                val label = tile.findViewById<TextView>(R.id.output_label)
                val minus = tile.findViewById<View>(R.id.output_decrease)
                val slider = tile.findViewById<View>(R.id.output_slider)
                val plus = tile.findViewById<View>(R.id.output_increase)
                val value = tile.findViewById<TextView>(R.id.output_value)
                val box = tile.findViewById<View>(R.id.output_value_row)
                val center = label.top + label.height / 2f
                for (control in listOf(minus, slider, plus, box))
                    assertTrue("$width dp/$font scale: output row stays aligned", abs(control.top + control.height / 2f - center) <= density)
                assertTrue(label.right <= minus.left && minus.right <= slider.left && slider.right <= plus.left && plus.right <= box.left)
                assertTrue("Slider retains a usable track width", slider.width >= 60 * density)
                assertTrue("$width dp/$font scale: ${value.text} fits (text=${value.paint.measureText(value.text.toString())}, size=${value.textSize}, width=${value.width}, padding=${value.paddingLeft + value.paddingRight})",
                    value.paint.measureText(value.text.toString()) <= value.width - value.paddingLeft - value.paddingRight + 1)
                assertTrue("Title is not clipped", label.layout.getEllipsisCount(0) == 0)
                assertTrue("Steps remain touchable", minus.height >= 30 * density && plus.height >= 30 * density)
            }
            tiles.zipWithNext().forEach { (first, second) ->
                val a = root.findViewById<View>(first); val b = root.findViewById<View>(second)
                val gap = b.top - a.bottom
                assertTrue("Output row gap is compact", gap >= 4 * density - 1 && gap <= 6 * density + 1)
            }
        }
    }

    private fun capture(root: View, name: String) {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(com.google.android.material.color.MaterialColors.getColor(root,
                com.google.android.material.R.attr.colorSurface))
            root.draw(canvas)
            val file = File("diagnostics/UI54-power-motion-20261017/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    @Suppress("RestrictedApi")
    @Test fun secondarySliderRowsHaveCompactGapsAndReadableFrequencyValues() {
        val context = context(1.5f)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(R.layout.preference_materialslider, R.layout.preference_bass_numeric, R.layout.preference_fusion_tone_slider).map { resource ->
            val row = LayoutInflater.from(context).inflate(resource, column, false)
            val tone = resource == R.layout.preference_fusion_tone_slider
            val titleId = if (tone) R.id.fusion_tone_slider_title else android.R.id.title
            val valueId = if (tone) R.id.fusion_tone_value else R.id.seekbar_value
            row.findViewById<TextView>(titleId).text = "输出高频截止"
            row.findViewById<TextView>(valueId).text = "192000 Hz"
            SliderPreferenceUi.bind(PreferenceViewHolder.createInstanceForTests(row), "输出高频截止", "", "1–192000 Hz", titleId)
            column.addView(row)
            row
        }
        layout(column, 320)
        val density = context.resources.displayMetrics.density
        rows.zipWithNext().forEach { (a, b) ->
            assertTrue("Secondary row gap stays at 4–6dp", b.top - a.bottom >= 4 * density - 1 && b.top - a.bottom <= 6 * density + 1)
        }
        rows.forEach { row ->
            val value = row.findViewById<TextView>(R.id.seekbar_value) ?: row.findViewById(R.id.fusion_tone_value)
            assertTrue("Long frequency/unit stays readable at 150%", value.paint.measureText(value.text.toString()) <= value.width - value.paddingLeft - value.paddingRight + 1)
            assertTrue(value.height >= value.paint.fontMetricsInt.let { it.descent - it.ascent })
        }
    }
}
