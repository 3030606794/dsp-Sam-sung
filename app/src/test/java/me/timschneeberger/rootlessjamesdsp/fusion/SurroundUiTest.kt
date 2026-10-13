package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.slider.Slider
import com.google.android.material.slider.LabelFormatter
import org.robolectric.shadows.ShadowDialog
import java.time.Duration
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.SurroundActivity
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class SurroundUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        stopKoin()
        java.io.File(app.filesDir, SurroundPresetStore.FILE_NAME).delete()
        listOf(SurroundController.PREFS, SurroundController.STATUS_PREFS, Constants.PREF_APP,
            Constants.PREF_EQ, ViperParameters.PREFS).forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        val prefs = Preferences(app)
        startKoin { modules(module { single { prefs.App() }; single { prefs.Var() } }) }
    }

    @After fun cleanup() { idle(); stopKoin() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun payloadIsVersionedBoundedAndDoesNotTouchOtherEffects() {
        val eq = app.getSharedPreferences(Constants.PREF_EQ, Context.MODE_PRIVATE)
        val viper = app.getSharedPreferences(ViperParameters.PREFS, Context.MODE_PRIVATE)
        eq.edit().putString("marker", "eq-original").commit()
        viper.edit().putBoolean("v_bass", true).commit()
        val beforeEq = eq.all.toMap()
        val beforeViper = viper.all.toMap()
        assertArrayEquals(intArrayOf(1, 0, 200, 9000, 5500, 35), SurroundController.payload(app))
        SurroundController.preferences(app).edit().putInt(SurroundController.SELECTED, -900)
            .putInt("slot_0_low", Int.MAX_VALUE).putInt("slot_0_high", -1)
            .putInt("slot_0_attenuation", 800000).putInt("slot_0_delay", 0).commit()
        assertArrayEquals(intArrayOf(1, 0, 800, 4000, 10000, 20), SurroundController.payload(app))
        var sent = false
        assertTrue(SurroundController.sync(app, 0) { _, _ -> sent = true; true })
        assertFalse(sent)
        assertFalse(SurroundController.setEnabled(app, true))
        SurroundController.publishCapabilities(app, SurroundController.CAPABILITY)
        assertTrue(SurroundController.setEnabled(app, true))
        assertTrue(SurroundController.sync(app, SurroundController.CAPABILITY) { id, payload ->
            assertEquals(SurroundController.PARAMETER, id)
            assertArrayEquals(intArrayOf(1, 1, 800, 4000, 10000, 20), payload)
            sent = true
            true
        })
        assertTrue(sent)
        assertFalse(SurroundController.sync(app, 0) { _, _ -> fail("Unsupported parameter must not be sent"); true })
        assertFalse(SurroundController.supported(app))
        assertTrue(SurroundController.setEnabled(app, false))
        assertEquals(beforeEq, eq.all)
        assertEquals(beforeViper, viper.all)
    }

    @Test fun eightSlotsRetainEditsAndResetOnlyTheSelectedSlot() {
        val modified = SurroundController.Settings(310, 12000, 8750, 91)
        SurroundController.save(app, modified)
        for (slot in 1..7) {
            SurroundController.select(app, slot)
            assertEquals(SurroundController.defaults[slot], SurroundController.settings(app))
            SurroundController.save(app, SurroundController.settings(app).copy(delayUs = 50 + slot))
            assertFalse(SurroundController.enabled(app))
        }
        SurroundController.select(app, 0)
        assertEquals(modified, SurroundController.settings(app))
        SurroundController.publishCapabilities(app, 16)
        SurroundController.setEnabled(app, true)
        SurroundController.reset(app)
        assertEquals(SurroundController.defaults[0], SurroundController.settings(app))
        assertTrue(SurroundController.enabled(app))
        for (slot in 1..7) assertEquals(50 + slot, SurroundController.settings(app, slot).delayUs)
        // A persisted capability from another process is not sufficient to show an active switch.
        SurroundController.statusPreferences(app).edit().putString("session", "old-process").commit()
        assertFalse(SurroundController.supported(app))
    }

    @Test fun homepageRequiresCapabilitiesAllowsDisableAndOpensFullScreenEditor() {
        val controller = Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host = controller.get()
            idle()
            val power = host.findViewById<View>(R.id.surround_power)
            power.performClick()
            idle()
            assertFalse(SurroundController.enabled(app))
            assertFalse(power.isSelected)
            SurroundController.publishCapabilities(app, 16)
            idle()
            power.performClick()
            idle()
            assertTrue(power.isSelected)
            SurroundController.publishCapabilities(app, 0)
            idle()
            assertFalse(power.isSelected)
            power.performClick()
            assertFalse(SurroundController.enabled(app))
            SurroundController.select(app, 5)
            app.sendLocalBroadcast(Intent(Constants.ACTION_PRESET_LOADED))
            idle()
            assertTrue(host.findViewById<TextView>(R.id.surround_editor_entry).text.contains("6"))
            host.findViewById<View>(R.id.surround_editor_entry).performClick()
            assertEquals(SurroundActivity::class.java.name, shadowOf(host).nextStartedActivity.component?.className)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun editorRetainsParametersAndRejectsNonFiniteInput() {
        val controller = Robolectric.buildActivity(SurroundActivity::class.java).setup().visible()
        try {
            val editor = controller.get()
            idle()
            assertNotNull(editor.findViewById<View>(R.id.surround_chart))
            assertFalse(editor.updateValue(SurroundActivity.Parameter.ATTENUATION, Double.NaN))
            assertFalse(editor.updateValue(SurroundActivity.Parameter.HIGH, Double.POSITIVE_INFINITY))
            assertFalse(editor.updateValue(SurroundActivity.Parameter.DELAY, 211.0))
            assertTrue(editor.updateValue(SurroundActivity.Parameter.ATTENUATION, 8.75))
            assertTrue(editor.updateValue(SurroundActivity.Parameter.LOW, 300.0))
            editor.setPinned(true)
            idle()
            controller.recreate()
            idle()
            assertEquals(8750, SurroundController.settings(app).attenuationMilliDb)
            assertEquals(300, SurroundController.settings(app).lowHz)
            assertFalse(SurroundController.enabled(app))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun pinnedGraphAndEqualFooterStayVisibleWhileParametersScroll() {
        val controller = Robolectric.buildActivity(SurroundActivity::class.java).setup().visible()
        try {
            val editor = controller.get()
            layout(editor)
            val chart = editor.findViewById<View>(R.id.surround_chart)
            val pinned = editor.findViewById<LinearLayout>(R.id.surround_pinned_area)
            assertSame(pinned, (chart.parent as View).parent)
            val footer = editor.findViewById<LinearLayout>(R.id.surround_footer)
            assertEquals(3, footer.childCount)
            assertEquals(R.id.surround_reset, footer.getChildAt(0).id)
            assertEquals(R.id.surround_power, footer.getChildAt(1).id)
            assertEquals(R.id.surround_user_presets, footer.getChildAt(2).id)
            for (index in 0..2) {
                val child = footer.getChildAt(index)
                assertEquals(1f, (child.layoutParams as LinearLayout.LayoutParams).weight, 0f)
                assertTrue(kotlin.math.abs(child.width - footer.width / 3) <= 1)
                assertEquals(footer.height, child.height)
            }
            assertNull(footer.getChildAt(1).background)
            val root = editor.window.decorView
            SurroundActivity.Parameter.values().forEach { parameter ->
                val slider = root.findViewWithTag<Slider>("surround_slider_${parameter.name}")
                val value = root.findViewWithTag<View>("surround_value_${parameter.name}")
                assertSame(slider.parent, value.parent)
                assertTrue(value.left >= slider.right)
                assertEquals(LabelFormatter.LABEL_GONE, slider.labelBehavior)
            }
            val before = IntArray(2).also { footer.getLocationOnScreen(it) }
            val scroll = editor.findViewById<ScrollView>(R.id.surround_scroll)
            scroll.scrollTo(0, 10000)
            idle()
            assertTrue(scroll.scrollY > 0)
            assertArrayEquals(before, IntArray(2).also { footer.getLocationOnScreen(it) })
            editor.setPinned(false)
            controller.recreate()
            layout(controller.get())
            assertEquals(0, controller.get().findViewById<LinearLayout>(R.id.surround_pinned_area).childCount)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun doubleTapResetsOnlyOneParameterToSelectedFactoryValueWithoutOpeningInput() {
        SurroundController.select(app, 7)
        SurroundController.save(app, SurroundController.Settings(340, 10000, 7650, 130))
        val controller = Robolectric.buildActivity(SurroundActivity::class.java).setup().visible()
        try {
            val editor = controller.get()
            layout(editor)
            val old = SurroundController.settings(app)
            val root = editor.window.decorView
            val value = root.findViewWithTag<View>("surround_value_DELAY")
            val previousDialog = ShadowDialog.getLatestDialog()
            doubleTap(value)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            assertEquals(old.copy(delayUs = 30), SurroundController.settings(app))
            assertSame(previousDialog, ShadowDialog.getLatestDialog())
            val slider = root.findViewWithTag<Slider>("surround_slider_ATTENUATION")
            doubleTap(slider)
            idle()
            assertEquals(old.copy(delayUs = 30, attenuationMilliDb = 2000), SurroundController.settings(app))
            assertEquals(2f, slider.value, 0.001f)
            assertEquals(7, SurroundController.selected(app))
            assertFalse(SurroundController.enabled(app))
            assertEquals(SurroundController.defaults[0], SurroundController.settings(app, 0))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun numericInputPreservesImportedMilliDbPrecisionAndRejectsInvalidValues() {
        SurroundController.save(app, SurroundController.settings(app).copy(attenuationMilliDb = 8753))
        val controller = Robolectric.buildActivity(SurroundActivity::class.java).setup().visible()
        try {
            val editor = controller.get()
            val value = editor.window.decorView.findViewWithTag<TextView>("surround_value_ATTENUATION")
            assertEquals("8.753 dB", value.text.toString())
            value.performClick()
            idle()
            val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val input = dialog.findViewById<android.widget.EditText>(android.R.id.edit)!!
            assertEquals("8.753", input.text.toString())
            input.setText("11")
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing)
            assertNotNull(input.error)
            input.setText("8.753")
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            assertFalse(dialog.isShowing)
            assertEquals(8753, SurroundController.settings(app).attenuationMilliDb)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun presetDialogTracksTheActiveSlotWhenDeviceConfigurationChanges() {
        val controller = Robolectric.buildActivity(SurroundActivity::class.java).setup().visible()
        try {
            val editor = controller.get()
            editor.findViewById<View>(R.id.surround_user_presets).performClick()
            idle()
            val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val scope = dialog.findViewById<TextView>(R.id.surround_presets_scope)!!
            assertEquals(editor.getString(R.string.surround_presets_scope, 1), scope.text.toString())
            SurroundController.select(app, 5)
            app.sendLocalBroadcast(Intent(Constants.ACTION_PRESET_LOADED))
            idle()
            assertEquals(editor.getString(R.string.surround_presets_scope, 6), scope.text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun exportSnapshotSurvivesRotationAndCancellationLeavesPresetsUntouched() {
        val store = SurroundPresetStore(app)
        val saved = store.save("扬声器", SurroundController.defaults[0])
        val controller = Robolectric.buildActivity(SurroundActivity::class.java).setup().visible()
        try {
            val editor = controller.get()
            editor.findViewById<View>(R.id.surround_user_presets).performClick()
            idle()
            val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            dialog.findViewById<View>(R.id.surround_presets_export_all)!!.performClick()
            idle()
            val request = shadowOf(editor).nextStartedActivityForResult
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
            assertEquals("application/json", request.intent.type)
            val state = Bundle()
            controller.saveInstanceState(state)
            val snapshotName = state.getString("surround_export_snapshot")!!
            val snapshot = java.io.File(app.cacheDir, snapshotName)
            assertEquals(saved.settings, SurroundPresetCodec.decode(snapshot.readText()).single().settings)
            controller.recreate()
            idle()
            assertTrue(snapshot.isFile)
            assertTrue(controller.get().activityResultRegistry.dispatchResult(request.requestCode,
                android.app.Activity.RESULT_CANCELED, null))
            idle()
            assertFalse(snapshot.exists())
            assertEquals(listOf(saved), store.load())
            assertFalse(SurroundController.enabled(app))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun footerPowerAndResetPreserveOtherSlots() {
        SurroundController.save(app, SurroundController.Settings(330, 12000, 8750, 80))
        SurroundController.select(app, 2)
        SurroundController.save(app, SurroundController.Settings(250, 11000, 6750, 70))
        SurroundController.publishCapabilities(app, SurroundController.CAPABILITY)
        val controller = Robolectric.buildActivity(SurroundActivity::class.java).setup().visible()
        try {
            val editor = controller.get()
            val power = editor.findViewById<View>(R.id.surround_power)
            power.performClick()
            idle()
            assertTrue(power.isSelected)
            editor.findViewById<View>(R.id.surround_reset).performClick()
            idle()
            assertEquals(SurroundController.defaults[2], SurroundController.settings(app))
            assertEquals(80, SurroundController.settings(app, 0).delayUs)
            assertTrue(SurroundController.enabled(app))
            power.performClick()
            idle()
            assertFalse(power.isSelected)
        } finally { controller.pause().stop().destroy() }
    }

    private fun layout(editor: SurroundActivity) {
        val root = editor.window.decorView
        val density = editor.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((411 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((820 * density).toInt(), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        idle()
    }

    private fun doubleTap(view: View) {
        val start = SystemClock.uptimeMillis() + 10
        fun touch(down: Long, time: Long, action: Int) {
            MotionEvent.obtain(down, time, action, view.width * .75f, view.height * .5f, 0).also {
                view.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        touch(start, start, MotionEvent.ACTION_DOWN)
        touch(start, start + 20, MotionEvent.ACTION_UP)
        touch(start + 100, start + 100, MotionEvent.ACTION_DOWN)
        touch(start + 100, start + 120, MotionEvent.ACTION_UP)
    }

    @Test fun frequencyPreviewMatchesBandEdgesAndExcludesAttenuationAndDelay() {
        val settings = SurroundController.Settings(300, 9000, 10000, 20)
        val response = SurroundResponse.calculate(settings)
        assertTrue(response.decibels.all { it.isFinite() && it <= 0.001 })
        assertTrue(response.decibels.first() < -40.0)
        assertTrue(response.decibels.last() < -20.0)
        assertTrue(response.decibels.maxOrNull()!! > -0.1)
        val second = SurroundResponse.calculate(settings.copy(attenuationMilliDb = 500, delayUs = 210))
        assertArrayEquals(response.decibels, second.decibels, 0.0)
    }

    class Host : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = android.R.id.content })
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .add(android.R.id.content, SurroundPreferenceFragment()).commitNow()
        }
    }
}
