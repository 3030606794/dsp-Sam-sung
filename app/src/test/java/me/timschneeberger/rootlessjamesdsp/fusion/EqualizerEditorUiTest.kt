package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.EqualizerActivity
import me.timschneeberger.rootlessjamesdsp.eq.EqPreset
import me.timschneeberger.rootlessjamesdsp.eq.EqPresetStore
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import me.timschneeberger.rootlessjamesdsp.view.EqPreview
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w411dp-h820dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class EqualizerEditorUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_EQ, Context.MODE_PRIVATE)
    private val bandKey get() = app.getString(R.string.key_eq_bands)
    private var controller: ActivityController<EqualizerActivity>? = null
    private var updates = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { updates++ }
    }

    @Before fun setup() {
        stopKoin()
        listOf(Constants.PREF_APP, Constants.PREF_VAR, Constants.PREF_EQ, Constants.PREF_GEQ,
            EqPresetStore.PREFERENCES).forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        val preferences = Preferences(app)
        startKoin { modules(module { single { preferences.App() }; single { preferences.Var() } }) }
        app.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PREFERENCES_UPDATED))
    }

    @After fun cleanup() {
        ShadowDialog.getLatestDialog()?.dismiss()
        controller?.pause()?.stop()?.destroy()
        idle()
        app.unregisterLocalReceiver(receiver)
        stopKoin()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun launch(): EqualizerActivity {
        controller = Robolectric.buildActivity(EqualizerActivity::class.java).setup().visible()
        return controller!!.get().also { layout(it) }
    }
    private fun layout(activity: EqualizerActivity) {
        idle()
        val width = app.resources.configuration.screenWidthDp
        val root = activity.findViewById<View>(android.R.id.content)
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(820, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, 820)
            idle()
        }
    }
    private fun row(activity: EqualizerActivity, index: Int): LinearLayout =
        activity.window.decorView.findViewWithTag("eq_gain_row_$index")
    private fun levels() = EqPreview.parse(prefs.getString(bandKey, EqPreview.defaultValue)!!)
    private fun latestDialog(): AlertDialog {
        idle() // Deliver Dialog.OnShow before clicking its validation-aware positive button.
        return ShadowDialog.getLatestDialog() as AlertDialog
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    @Test fun allFifteenRowsHaveMinusSliderPlusAndEditableValueAndUseOneDbSteps() {
        val activity = launch()
        val peq = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
        peq.edit().putString("preserve", "PEQ").commit()
        val beforePeq = peq.all.toMap()
        repeat(15) { index ->
            val row = row(activity, index)
            assertEquals(4, row.childCount)
            assertEquals(R.id.eq_gain_minus, row.getChildAt(0).id)
            assertEquals(R.id.eq_gain_slider, row.getChildAt(1).id)
            assertEquals(R.id.eq_gain_plus, row.getChildAt(2).id)
            assertEquals(R.id.eq_gain_value, row.getChildAt(3).id)
            val slider = row.findViewById<Slider>(R.id.eq_gain_slider)
            assertEquals(-12f, slider.valueFrom, 0f)
            assertEquals(12f, slider.valueTo, 0f)
            assertEquals(0.1f, slider.stepSize, 0f)
            val beforeUpdates = updates
            row.findViewById<View>(R.id.eq_gain_plus).performClick()
            idle()
            assertEquals(1.0, levels()[index], 0.0)
            assertEquals(beforeUpdates + 1, updates)
            assertEquals("+1.0 dB", row.findViewById<MaterialButton>(R.id.eq_gain_value).text.toString())
            row.findViewById<View>(R.id.eq_gain_minus).performClick()
            idle()
            assertEquals(0.0, levels()[index], 0.0)
        }
        activity.setBandGain(0, 12.0)
        row(activity, 0).findViewById<View>(R.id.eq_gain_plus).performClick()
        assertEquals(12.0, levels()[0], 0.0)
        activity.setBandGain(14, -12.0)
        row(activity, 14).findViewById<View>(R.id.eq_gain_minus).performClick()
        assertEquals(-12.0, levels()[14], 0.0)
        assertFalse(prefs.getBoolean(app.getString(R.string.key_eq_enable), false))
        assertEquals(beforePeq, peq.all)
    }

    @Test fun numericInputAcceptsTenthsRejectsOutOfRangeAndCancelKeepsTheGain() {
        val activity = launch()
        val value = row(activity, 14).findViewById<MaterialButton>(R.id.eq_gain_value)
        value.performClick()
        val dialog = latestDialog()
        val input = dialog.findViewById<EditText>(R.id.eq_gain_input)!!
        input.setText("12.1")
        val beforeUpdates = updates
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertNotNull(input.error)
        assertEquals(0.0, levels()[14], 0.0)
        assertEquals(beforeUpdates, updates)
        input.setText("-3.7")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertEquals(-3.7, levels()[14], 0.0)
        assertEquals("-3.7 dB", value.text.toString())
        assertEquals(beforeUpdates + 1, updates)
        value.performClick()
        latestDialog().findViewById<EditText>(R.id.eq_gain_input)!!.setText("8.4")
        latestDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(-3.7, levels()[14], 0.0)
        controller!!.recreate()
        layout(controller!!.get())
        assertEquals("-3.7 dB", row(controller!!.get(), 14).findViewById<MaterialButton>(R.id.eq_gain_value).text.toString())
    }

    private fun touch(slider: Slider, action: Int, down: Long, time: Long, fraction: Float) {
        val event = MotionEvent.obtain(down, time, action,
            slider.trackSidePadding + slider.trackWidth * fraction, slider.height / 2f, 0)
        try { assertTrue(slider.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    @Test fun doubleTapOnTheRealSliderResetsOnlyItsBandAndSubsequentDraggingStillWorks() {
        val activity = launch()
        activity.setBandGain(1, 4.3)
        activity.setBandGain(0, -2.0)
        val slider = row(activity, 0).findViewById<Slider>(R.id.eq_gain_slider)
        val first = SystemClock.uptimeMillis()
        touch(slider, MotionEvent.ACTION_DOWN, first, first, 0.8f)
        touch(slider, MotionEvent.ACTION_UP, first, first + 20, 0.8f)
        touch(slider, MotionEvent.ACTION_DOWN, first + 110, first + 110, 0.8f)
        touch(slider, MotionEvent.ACTION_UP, first + 110, first + 130, 0.8f)
        idle()
        assertEquals(0.0, levels()[0], 0.0)
        assertEquals(0f, slider.value, 0f)
        assertEquals(4.3, levels()[1], 0.0)
        touch(slider, MotionEvent.ACTION_DOWN, first + 600, first + 600, 0.5f)
        touch(slider, MotionEvent.ACTION_MOVE, first + 600, first + 650, 0.25f)
        touch(slider, MotionEvent.ACTION_UP, first + 600, first + 680, 0.25f)
        idle()
        assertEquals(-6.0, levels()[0], 0.11)
        assertEquals(4.3, levels()[1], 0.0)
    }

    @Test fun everyExistingPresetRemainsInTheMenuAndUpdatesOnlyTheFifteenGains() {
        val activity = launch()
        val labels = app.resources.getStringArray(R.array.equalizer_preset_modes)
        val presetValues = app.resources.getStringArray(R.array.equalizer_preset_values)
        val peq = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
        peq.edit().putString("preserve", "PEQ").commit()
        val beforePeq = peq.all.toMap()
        assertEquals(18, labels.size)
        assertEquals(labels.size, presetValues.size)
        labels.forEachIndexed { index, label ->
            activity.findViewById<View>(R.id.eq_presets).performClick()
            val dialog = latestDialog()
            val list = dialog.listView
            assertEquals(labels.size, list.adapter.count)
            assertEquals(label, (list.adapter.getItem(index) as EqPreset).name)
            val beforeUpdates = updates
            assertTrue(list.performItemClick(list.adapter.getView(index, null, list), index, list.adapter.getItemId(index)))
            idle()
            assertFalse(dialog.isShowing)
            assertEquals(beforeUpdates + 1, updates)
            val expected = presetValues[index].split(';').map { it.toDouble().coerceIn(-12.0, 12.0) }.toDoubleArray()
            assertArrayEquals(expected, levels(), 0.0)
            assertEquals(beforePeq, peq.all)
            assertFalse(prefs.getBoolean(app.getString(R.string.key_eq_enable), false))
        }
    }

    @Test fun regularPhoneKeepsFourEqualActionsVisibleWhileScrollingAndPinning() = checkFooter()

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h820dp-port-mdpi")
    fun compactPhoneKeepsFourEqualActionsVisibleWhileScrollingAndPinning() = checkFooter()

    private fun checkFooter() {
        val activity = launch()
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val footer = activity.findViewById<LinearLayout>(R.id.eq_fixed_actions)
        val scroll = activity.findViewById<ScrollView>(R.id.eq_bands_scroll)
        assertEquals(4, footer.childCount)
        assertEquals(listOf(R.id.eq_reset, R.id.eq_presets, R.id.eq_filter_type, R.id.eq_interpolation),
            (0 until footer.childCount).map { footer.getChildAt(it).id })
        val initialBounds = bounds(root, footer)
        val children = (0 until footer.childCount).map { footer.getChildAt(it) as MaterialButton }
        children.forEach { button ->
            assertTrue(button.isShown)
            assertTrue(kotlin.math.abs(button.width - footer.width / 4f) <= 1f)
            assertTrue(button.width > 0 && button.height > 0)
            val text = requireNotNull(button.layout)
            repeat(text.lineCount) { line -> assertEquals(0, text.getEllipsisCount(line)) }
            assertTrue(text.height <= button.height - button.totalPaddingTop - button.totalPaddingBottom)
        }
        assertTrue(initialBounds.bottom <= root.height)
        assertTrue(initialBounds.top >= bounds(root, scroll).bottom)
        assertTrue(descendants(root).filterIsInstance<TextView>().none {
            it.text == "15 个频段 · 实时保存" || it.text == app.getString(R.string.eq_interpolator_fir_only_hint)
        })
        saveImage(root, "top")
        val axis = activity.findViewById<me.timschneeberger.rootlessjamesdsp.peq.PeqResponseView>(R.id.layout_peq)
            .renderedGainLabels.map { it.text.toDouble() }
        assertEquals("The multimodal graph matches the slider's ±12 dB range", 12.0, axis.maxOrNull()!!, 0.0)
        assertEquals(-12.0, axis.minOrNull()!!, 0.0)
        scroll.fullScroll(View.FOCUS_DOWN)
        layout(activity)
        assertTrue(scroll.scrollY > 0)
        assertEquals(initialBounds, bounds(root, footer))
        children.forEach { assertTrue(it.getGlobalVisibleRect(Rect())) }
        saveImage(root, "bottom")
        activity.setPinned(true)
        layout(activity)
        scroll.fullScroll(View.FOCUS_DOWN)
        layout(activity)
        assertEquals(initialBounds, bounds(root, footer))
        children.forEach { assertTrue(it.getGlobalVisibleRect(Rect())) }
        activity.setBandGain(14, 5.0)
        activity.findViewById<View>(R.id.eq_reset).performClick()
        idle()
        assertArrayEquals(DoubleArray(15), levels(), 0.0)
    }

    private fun bounds(parent: ViewGroup, child: View) = Rect(0, 0, child.width, child.height).also {
        parent.offsetDescendantRectToMyCoords(child, it)
    }

    private fun saveImage(root: View, suffix: String) {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            // The content view is transparent; include the real themed window behind it.
            bitmap.eraseColor(MaterialColors.getColor(root, android.R.attr.colorBackground))
            val attributes = root.context.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
            val background = try { attributes.getDrawable(0) } finally { attributes.recycle() }
            background?.let {
                val drawable = it.constantState?.newDrawable(root.resources)?.mutate() ?: it
                val previousBounds = Rect(drawable.bounds)
                drawable.setBounds(0, 0, root.width, root.height)
                drawable.draw(canvas)
                drawable.bounds = previousBounds
            }
            root.draw(canvas)
            assertEquals("The preview includes an opaque themed window", 255,
                Color.alpha(bitmap.getPixel(0, root.height - 1)))
            val folder = File("build/reports/equalizer-editor").apply { mkdirs() }
            File(folder, "eq-${root.width}-$suffix.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
