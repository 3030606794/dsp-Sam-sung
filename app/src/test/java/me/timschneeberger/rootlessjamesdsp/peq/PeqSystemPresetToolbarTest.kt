package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.appbar.MaterialToolbar
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ActivityGraphicEqBinding
import me.timschneeberger.rootlessjamesdsp.activity.EqualizerToolbarLayout
import me.timschneeberger.rootlessjamesdsp.activity.GraphicEqualizerActivity
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.AppFontScale
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
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

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h914dp-port-560dpi")
    @LooperMode(LooperMode.Mode.PAUSED)
    fun actual1440PixelEditorAtNinetyPercentKeepsCompactPitchAfterSummaryUpdatesAndRotation() {
        val app = prepareActualEditor(90)
        val controller = Robolectric.buildActivity(GraphicEqualizerActivity::class.java).setup().visible()
        try {
            var activity = controller.get()
            layoutActual(activity, 1440, 3200)
            assertEquals(.9f, activity.resources.configuration.fontScale, .001f)
            assertEquals(560, activity.resources.displayMetrics.densityDpi)
            assertEquals(130, (activity.supportFragmentManager.findFragmentById(R.id.params) as PeqFragment).editorState.bands.size)
            val original = inspectActualHeader(activity)
            saveActualEditor(activity, "peq-editor-1440-font90", 1440, 3200, original)
            activity.findViewById<TextView>(R.id.peq_toolbar_summary).text = activity.getString(R.string.peq_summary, 35, "0.5", "12")
            layoutActual(activity, 1440, 3200)
            assertEquals("Changing the band count preserves the text-center pitch", original.centerPitch,
                inspectActualHeader(activity).centerPitch, 1.5f)

            RuntimeEnvironment.setQualifiers("zh-rCN-w914dp-h411dp-land-560dpi")
            controller.recreate()
            activity = controller.get()
            layoutActual(activity, 3200, 1440)
            assertEquals(Configuration.ORIENTATION_LANDSCAPE, activity.resources.configuration.orientation)
            assertEquals(.9f, activity.resources.configuration.fontScale, .001f)
            assertEquals("Landscape rotation preserves the compact pitch", original.centerPitch,
                inspectActualHeader(activity).centerPitch, 1.5f)

            RuntimeEnvironment.setQualifiers("zh-rCN-w411dp-h914dp-port-560dpi")
            controller.recreate()
            activity = controller.get()
            layoutActual(activity, 1440, 3200)
            assertEquals(Configuration.ORIENTATION_PORTRAIT, activity.resources.configuration.orientation)
            assertEquals("Rotating back preserves the compact pitch", original.centerPitch,
                inspectActualHeader(activity).centerPitch, 1.5f)
            assertEquals(130, PeqRepository(app).load().bands.size)
            assertEquals(12.0, PeqRepository(app).load().gainRange, 0.0)
        } finally {
            controller.pause().stop().destroy()
            app.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE).edit().remove(AppFontScale.KEY).commit()
            RuntimeEnvironment.setQualifiers("zh-rCN-w411dp-h914dp-port-560dpi")
            RuntimeEnvironment.setFontScale(1f)
            stopKoin()
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h914dp-port-560dpi")
    @LooperMode(LooperMode.Mode.PAUSED)
    fun actualEditorAtOneHundredAndOneHundredFiftyPercentClampsWithoutGlyphOverlap() {
        try {
            for (percent in listOf(100, 150)) {
                val app = prepareActualEditor(percent)
                val controller = Robolectric.buildActivity(GraphicEqualizerActivity::class.java).setup().visible()
                try {
                    val activity = controller.get()
                    layoutActual(activity, 1440, 3200)
                    assertEquals(percent / 100f, activity.resources.configuration.fontScale, .001f)
                    val spacing = inspectActualHeader(activity)
                    if (percent == 150) assertTrue("Larger glyphs expand the pitch rather than overlap",
                        spacing.centerPitch > activity.resources.getDimension(R.dimen.peq_toolbar_line_pitch))
                    saveActualEditor(activity, "peq-editor-1440-font$percent", 1440, 3200, spacing)
                } finally {
                    controller.pause().stop().destroy()
                    app.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE).edit().remove(AppFontScale.KEY).commit()
                    stopKoin()
                }
            }
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    private fun prepareActualEditor(percent: Int): Application {
        val app: Application = RuntimeEnvironment.getApplication()
        stopKoin()
        RuntimeEnvironment.setFontScale(1f)
        listOf(Constants.PREF_APP, Constants.PREF_VAR, Constants.PREF_GEQ,
            PeqBandLockStore.PREFERENCES, PeqEditorPreferences.PREFERENCES).forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        val preferences = Preferences(app)
        startKoin { modules(module { single { preferences.App() }; single { preferences.Var() } }) }
        app.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE).edit()
            .putFloat(AppFontScale.KEY, percent.toFloat()).commit()
        val base = PeqModel.defaultState(130)
        val state = base.copy(gainRange = 12.0, bands = base.bands.mapIndexed { index, band ->
            if (index == 0) band.copy(frequency = 1572.0, gain = 12.0, q = 3.499) else band })
        assertTrue(PeqRepository(app).save(state))
        assertTrue(PeqEditorPreferences(app).save(PeqEditorSettings(showInlineFilters = true, showCardFilterIcons = true)))
        state.bands.forEach { PeqBandLockStore(app).setBandLocked(it.id, false) }
        PeqPreviewSession.end(PeqPreviewSession.begin(state))
        return app
    }

    private fun layoutActual(activity: GraphicEqualizerActivity, width: Int, height: Int) {
        val root = activity.findViewById<View>(android.R.id.content)
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idle()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun inspectActualHeader(activity: GraphicEqualizerActivity): InkSpacing {
        val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
        val header = toolbar.parent as ViewGroup
        val title = children(toolbar).filterIsInstance<TextView>().single { it.text == toolbar.title }
        val back = children(toolbar).filterIsInstance<ImageButton>().single { it.drawable != null }
        val pin = toolbar.findViewById<View>(R.id.peq_pin_response)
        assertEquals(bounds(header, back).exactCenterY(), bounds(header, title).exactCenterY(), 1f)
        assertEquals(bounds(header, back).exactCenterY(), bounds(header, pin).exactCenterY(), 1f)
        val summary = activity.findViewById<TextView>(R.id.peq_toolbar_summary)
        assertReadable(title)
        assertReadable(summary)
        return assertInkGap(header, title, listOf(activity.findViewById(R.id.peq_system_presets),
            activity.findViewById(R.id.peq_toolbar_summary_separator), summary))
    }

    private fun saveActualEditor(activity: GraphicEqualizerActivity, name: String, width: Int, height: Int, spacing: InkSpacing) {
        val root = activity.findViewById<View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val directory = File("build/reports/ui38-fix4").apply { mkdirs() }
        try {
            bitmap.eraseColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface))
            root.draw(Canvas(bitmap))
            bitmap.setHasAlpha(false)
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val metrics = activity.resources.displayMetrics
        File(directory, "$name.txt").writeText("Native title center pitch: ${spacing.centerPitch} px\n" +
            "Native visible blank gap: ${spacing.visibleGap} px\nClamp target: ${spacing.expectedPitch} px\n" +
            "density=${metrics.density}, densityDpi=${metrics.densityDpi}, xdpi=${metrics.xdpi}, " +
            "fontScale=${activity.resources.configuration.fontScale}\n")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-port-mdpi")
    @LooperMode(LooperMode.Mode.PAUSED)
    fun fullParametricEditorAndFilterPickerReviewImagesUseTheActualActivityAndTheme() {
        val app: Application = RuntimeEnvironment.getApplication()
        stopKoin()
        listOf(Constants.PREF_APP, Constants.PREF_VAR, Constants.PREF_GEQ,
            PeqBandLockStore.PREFERENCES, PeqEditorPreferences.PREFERENCES).forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        val preferences = Preferences(app)
        startKoin { modules(module { single { preferences.App() }; single { preferences.Var() } }) }
        PeqPreviewSession.end(PeqPreviewSession.begin(PeqModel.defaultState()))
        var controller: ActivityController<GraphicEqualizerActivity>? = null
        var picker: AlertDialog? = null
        try {
            // Screenshot-only example bank; application defaults and shipped presets stay intact.
            val base = PeqModel.defaultState(35)
            val state = base.copy(bands = base.bands.mapIndexed { index, band ->
                if (index == 0) band.copy(frequency = 311.0, gain = 0.0, q = 1.697) else band })
            assertTrue(PeqRepository(app).save(state))
            assertTrue(PeqEditorPreferences(app).save(PeqEditorSettings(
                showInlineFilters = true, showCardFilterIcons = true)))
            state.bands.forEach { PeqBandLockStore(app).setBandLocked(it.id, false) }
            app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE).edit()
                .putBoolean(app.getString(R.string.key_geq_enable), true).commit()
            controller = Robolectric.buildActivity(GraphicEqualizerActivity::class.java).setup().visible()
            val activity = controller.get()
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            repeat(3) {
                shadowOf(Looper.getMainLooper()).idle()
                root.measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(891, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 411, 891)
            }
            shadowOf(Looper.getMainLooper()).idle()
            val fragment = activity.supportFragmentManager.findFragmentById(R.id.params) as PeqFragment
            assertEquals(state, fragment.editorState)
            assertEquals(activity.getString(R.string.peq_summary, 35, "0.5", "18"),
                activity.findViewById<TextView>(R.id.peq_toolbar_summary).text.toString())
            val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
            val header = toolbar.parent as ViewGroup
            val title = children(toolbar).filterIsInstance<TextView>().single { it.text == toolbar.title }
            assertInkGap(header, title, listOf(activity.findViewById<TextView>(R.id.peq_system_presets),
                activity.findViewById<TextView>(R.id.peq_toolbar_summary_separator),
                activity.findViewById<TextView>(R.id.peq_toolbar_summary)))
            val bands = activity.findViewById<RecyclerView>(R.id.peq_band_list)
            assertEquals(35, bands.adapter!!.itemCount)
            val firstCard = requireNotNull(bands.findViewHolderForAdapterPosition(0)).itemView
            assertEquals("峰值", firstCard.findViewById<TextView>(R.id.peq_band_title).text.toString())
            assertTrue(firstCard.findViewById<View>(R.id.peq_band_title).isEnabled)
            assertTrue(activity.findViewById<View>(R.id.peq_power).isSelected)

            val directory = File("build/reports/ui38-fix3").apply { mkdirs() }
            val surface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface)
            fun saveReview(name: String, overlay: View? = null) {
                val bitmap = Bitmap.createBitmap(411, 891, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(surface)
                    root.draw(canvas)
                    overlay?.let {
                        canvas.save()
                        canvas.translate((411 - it.width) / 2f, (891 - it.height) / 2f)
                        it.draw(canvas)
                        canvas.restore()
                    }
                    assertEquals("The review image includes its opaque theme background", 255,
                        Color.alpha(bitmap.getPixel(0, 0)))
                    bitmap.setHasAlpha(false)
                    File(directory, name).outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { bitmap.recycle() }
            }
            saveReview("peq-editor-review.png")
            firstCard.findViewById<View>(R.id.peq_band_title).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            picker = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(picker.isShowing)
            assertEquals(R.style.PeqFilterDialogWindowAnimation, picker.window!!.attributes.windowAnimations)
            val dialogDecor = picker.window!!.decorView
            dialogDecor.measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(891, View.MeasureSpec.AT_MOST))
            dialogDecor.layout(0, 0, dialogDecor.measuredWidth, dialogDecor.measuredHeight)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(dialogDecor.height in 1..891)
            assertEquals(PeqFilterType.PEAK,
                picker.findViewById<PeqFilterSelector>(R.id.peq_filter_types)!!.selectedType)
            saveReview("peq-filter-dialog-review.png", dialogDecor)
        } finally {
            picker?.dismiss()
            controller?.pause()?.stop()?.destroy()
            shadowOf(Looper.getMainLooper()).idle()
            stopKoin()
        }
    }

    private fun checkToolbar(width: Int) {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        try {
            val host = controller.get()
            val binding = host.binding
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1f, host.resources.configuration.fontScale, 0f)
            repeat(3) {
                binding.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
                binding.root.layout(0, 0, width, 720)
                shadowOf(Looper.getMainLooper()).idle()
            }

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

            assertEquals("Header shares the action row instead of adding a second empty block",
                maxOf(toolbar.height, toolbar.height + summaryRow.height +
                    (summaryRow.layoutParams as ViewGroup.MarginLayoutParams).topMargin), header.height)
            assertNull("System presets has no pressed background", binding.peqSystemPresets.background)
            assertNull("System presets has no ripple overlay", binding.peqSystemPresets.foreground)
            assertEquals(width / 2f, bounds(header, title).exactCenterX(), 1f)
            assertEquals("Title shares the back button's horizontal center line",
                bounds(header, back).exactCenterY(), bounds(header, title).exactCenterY(), 1f)
            assertEquals("Pin shares the back button's horizontal center line",
                bounds(header, back).exactCenterY(), bounds(header, pin!!).exactCenterY(), 1f)
            assertEquals("Title uses the requested icon-matched size", 24f, title.textSize, 0.1f)
            assertEquals("Back icon has the same 24 dp visual size", 24, back.drawable.intrinsicHeight)
            val pinIcon = (pin as? TextView)?.compoundDrawables?.firstOrNull { it != null }
            assertNotNull("Pin has a visible action icon", pinIcon)
            assertEquals("Pin icon matches the back icon", back.drawable.intrinsicHeight, pinIcon!!.intrinsicHeight)
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
            val inkControls = linkedMapOf(
                "back" to bounds(header, back),
                "pin" to bounds(header, pin!!),
                "title" to nativeInk(header, title),
                "system presets" to nativeInk(header, binding.peqSystemPresets),
                "summary" to nativeInk(header, summary))
            val entries = inkControls.entries.toList()
            for (first in entries.indices) for (second in first + 1 until entries.size) {
                val a = entries[first]
                val b = entries[second]
                assertFalse("$width dp: ${a.key} ${a.value} overlaps ${b.key} ${b.value}",
                    Rect.intersects(a.value, b.value))
            }
            assertInkGap(header, title, listOf(binding.peqSystemPresets,
                binding.peqToolbarSummarySeparator, summary))
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
            repeat(3) {
                binding.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
                binding.root.layout(0, 0, width, 720)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertEquals(width / 2f, bounds(header, summaryRow).exactCenterX(), 1f)
            assertReadable(summary)
            assertInkGap(header, title, listOf(binding.peqSystemPresets,
                binding.peqToolbarSummarySeparator, summary))
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

    private data class InkSpacing(val centerPitch: Float, val visibleGap: Int, val expectedPitch: Float)

    /** Rasterize each actual TextView so CJK fallback and native hinting are part of the evidence. */
    private fun nativeInk(header: ViewGroup, text: TextView): Rect {
        val bitmap = Bitmap.createBitmap(text.width, text.height, Bitmap.Config.ARGB_8888)
        try {
            // ViewGroup.drawChild subtracts the child's scroll before drawing it. Single-line
            // TextViews can scroll internally even though their displayed frame is stationary.
            val canvas = Canvas(bitmap)
            canvas.translate(-text.scrollX.toFloat(), -text.scrollY.toFloat())
            text.draw(canvas)
            var left = bitmap.width
            var top = bitmap.height
            var right = -1
            var bottom = -1
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) >= 48) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
            assertTrue("Native glyphs are actually rendered for ${text.text}; scroll=${text.scrollX},${text.scrollY}",
                right >= left && bottom >= top)
            val frame = bounds(header, text)
            return Rect(left + frame.left, top + frame.top, right + frame.left + 1, bottom + frame.top + 1)
        } finally { bitmap.recycle() }
    }

    private fun assertInkGap(header: ViewGroup, title: TextView, summaryTexts: List<TextView>): InkSpacing {
        val titleInk = nativeInk(header, title)
        val summaryInk = summaryTexts.map { nativeInk(header, it) }
        val summaryUnion = Rect(summaryInk.first()).apply { summaryInk.drop(1).forEach { part -> this.union(part) } }
        val wanted = header.resources.getDimension(R.dimen.peq_toolbar_line_pitch)
        val minimumGap = 2f * header.resources.displayMetrics.density
        val expected = maxOf(wanted, (titleInk.height() + summaryUnion.height()) / 2f + minimumGap)
        val actual = summaryUnion.exactCenterY() - titleInk.exactCenterY()
        val gap = summaryUnion.top - titleInk.bottom
        val tolerance = maxOf(1.5f, header.resources.displayMetrics.density)
        assertEquals("Native text centers follow the compact 3 mm pitch or accessibility clamp", expected, actual, tolerance)
        assertTrue("Native title and subtitle glyphs do not collide: $titleInk / $summaryUnion", gap >= 0)
        (summaryInk + titleInk).forEach {
            assertTrue("The actual text stays inside the header without clipping: $it",
                it.top >= 0 && it.bottom <= header.height)
        }
        println("PEQ ${header.width}px: requested center pitch=$wanted px; native center pitch=$actual px; " +
            "native visible gap=$gap px; clamp=$expected px")
        return InkSpacing(actual, gap, expected)
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
            EqualizerToolbarLayout.keepTitleSummaryGap(binding.toolbar, binding.peqToolbarSummaryRow)
        }

        override fun onCreateOptionsMenu(menu: Menu): Boolean {
            menuInflater.inflate(R.menu.peq_editor, menu)
            return true
        }
    }
}
