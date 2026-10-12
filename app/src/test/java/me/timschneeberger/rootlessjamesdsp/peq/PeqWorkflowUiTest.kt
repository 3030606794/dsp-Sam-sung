package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.time.Duration
import kotlin.math.pow

/** Real menu, nested-scroll, touch and dialog interactions for the alpha9 editor workflow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqWorkflowUiTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = application.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val repository get() = PeqRepository(application)
    private var controller: ActivityController<HostActivity>? = null

    @Before fun clear() { prefs.edit().clear().commit() }
    @After fun close() { controller?.pause()?.stop()?.destroy(); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(milliseconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
    private fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("peq") as PeqFragment
    private fun <T : View> view(id: Int): T = fragment().requireView().findViewById(id)
    private fun layout() {
        idle()
        val content = controller!!.get().findViewById<View>(android.R.id.content)
        val density = application.resources.displayMetrics.density
        content.measure(View.MeasureSpec.makeMeasureSpec((411 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((820 * density).toInt(), View.MeasureSpec.EXACTLY))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        idle()
    }
    private fun launch(state: PeqState) {
        assertTrue(repository.save(state))
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout()
    }
    private fun row(index: Int = 0): View {
        val list = view<RecyclerView>(R.id.peq_band_list)
        list.scrollToPosition(index)
        layout()
        return requireNotNull(list.findViewHolderForAdapterPosition(index)).itemView
    }
    private fun dialog(): AlertDialog { idle(); return ShadowDialog.getLatestDialog() as AlertDialog }
    private fun settings(): AlertDialog { view<MaterialButton>(R.id.peq_settings).performClick(); return dialog() }
    private fun edit(): AlertDialog { row().findViewById<MaterialButton>(R.id.peq_band_edit).performClick(); return dialog() }
    private fun menu() = controller!!.get().let { activity ->
        val menu = PopupMenu(activity, activity.findViewById<MaterialToolbar>(R.id.toolbar)).menu
        assertTrue(activity.onCreatePanelMenu(Window.FEATURE_OPTIONS_PANEL, menu))
        activity.onPreparePanel(Window.FEATURE_OPTIONS_PANEL, null, menu)
        menu
    }
    private fun togglePin() {
        assertTrue(controller!!.get().onMenuItemSelected(Window.FEATURE_OPTIONS_PANEL,
            menu().findItem(R.id.peq_pin_response)))
        layout()
    }
    private fun nestedScrollUp() {
        val list = view<RecyclerView>(R.id.peq_band_list)
        val distance = view<AppBarLayout>(R.id.peq_response_appbar).height * 2
        list.startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH)
        val consumed = IntArray(2)
        list.dispatchNestedPreScroll(0, distance, consumed, null, ViewCompat.TYPE_TOUCH)
        list.scrollBy(0, distance - consumed[1])
        list.stopNestedScroll(ViewCompat.TYPE_TOUCH)
        idle()
    }
    private fun marker(): PeqResponseView.BandMarker? {
        val curve = view<PeqResponseView>(R.id.peq_curve)
        val bitmap = Bitmap.createBitmap(curve.width, curve.height, Bitmap.Config.ARGB_8888)
        try { curve.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
        return curve.renderedMarker
    }

    @Test fun toolbarPinSwitchPersistsAndControlsNestedScrollingWith170RecycledBands() {
        val original = PeqModel.defaultState(170)
        launch(original)
        assertTrue(menu().findItem(R.id.peq_pin_response).isChecked)
        val appbar = view<AppBarLayout>(R.id.peq_response_appbar)
        val footer = view<MaterialButton>(R.id.peq_reset)
        val footerBefore = IntArray(2).also { footer.getLocationInWindow(it) }
        nestedScrollUp()
        assertEquals(0, appbar.top)
        assertEquals(0, appbar.totalScrollRange)
        togglePin()
        assertFalse(repository.load().pinResponse)
        assertFalse(menu().findItem(R.id.peq_pin_response).isChecked)
        assertTrue(appbar.totalScrollRange > 0)
        nestedScrollUp()
        assertTrue("The unpinned plot must actually move out of view", appbar.top < 0)
        assertTrue(appbar.bottom <= 0)
        row(169)
        val list = view<RecyclerView>(R.id.peq_band_list)
        assertEquals(170, list.adapter!!.itemCount)
        assertTrue("Scrolling must retain RecyclerView's bounded visible row count", list.childCount in 1..25)
        assertArrayEquals(footerBefore, IntArray(2).also { footer.getLocationInWindow(it) })
        assertEquals(original.bands, repository.load().bands)
        controller!!.recreate()
        layout()
        val restoredMenu = menu()
        assertFalse(restoredMenu.findItem(R.id.peq_pin_response).isChecked)
        assertEquals(1, (0 until restoredMenu.size()).count { restoredMenu.getItem(it).itemId == R.id.peq_pin_response })
        assertFalse(fragment().editorState.pinResponse)
        togglePin()
        val restoredAppbar = view<AppBarLayout>(R.id.peq_response_appbar)
        assertEquals(0, restoredAppbar.top)
        assertEquals(0, restoredAppbar.totalScrollRange)
        nestedScrollUp()
        assertEquals(0, restoredAppbar.top)
        assertEquals(original, repository.load())
    }

    @Test fun locateExpandsHiddenPlotAndShowsOnlyTheRequestedBandForTwoSeconds() {
        val original = PeqModel.defaultState(170).copy(pinResponse = false)
        launch(original)
        assertNull(marker())
        nestedScrollUp()
        assertTrue(view<AppBarLayout>(R.id.peq_response_appbar).top < 0)
        row(169).findViewById<MaterialButton>(R.id.peq_band_locate).performClick()
        layout()
        assertEquals(0, view<AppBarLayout>(R.id.peq_response_appbar).top)
        val shown = requireNotNull(marker())
        assertEquals(original.bands.last().id, shown.bandId)
        assertEquals(original.bands.last().frequency, shown.frequency, 0.0)
        assertEquals(shown.y, shown.horizontalCrosshair.y1, 0f)
        assertEquals(shown.y, shown.horizontalCrosshair.y2, 0f)
        assertEquals(shown.x, shown.verticalCrosshair.x1, 0f)
        assertEquals(shown.x, shown.verticalCrosshair.x2, 0f)
        assertEquals(original, repository.load())
        advance(1999)
        assertNotNull(marker())
        advance(1)
        assertNull(marker())
        assertFalse(view<PeqResponseView>(R.id.peq_curve).isMarkerVisible)
        assertEquals(original, repository.load())
    }

    @Test fun destroyingAndRecreatingOnlyTheFragmentViewRemovesTheOldMenuProvider() {
        launch(PeqModel.defaultState(3).copy(pinResponse = false))
        val activity = controller!!.get()
        val editor = fragment()
        assertNotNull(menu().findItem(R.id.peq_pin_response))
        activity.supportFragmentManager.beginTransaction().detach(editor).commitNow()
        idle()
        assertNull(menu().findItem(R.id.peq_pin_response))
        activity.supportFragmentManager.beginTransaction().attach(editor).commitNow()
        layout()
        val recreated = menu()
        assertEquals(1, (0 until recreated.size()).count { recreated.getItem(it).itemId == R.id.peq_pin_response })
        assertFalse(recreated.findItem(R.id.peq_pin_response).isChecked)
        togglePin()
        assertTrue(repository.load().pinResponse)
        assertTrue(menu().findItem(R.id.peq_pin_response).isChecked)
    }

    @Test fun oldSchemaDefaultsToPinnedWithoutRewritingAndWrongPinTypesAreRejected() {
        val original = PeqState(listOf(PeqBand("old", frequency = 777.25, gain = 17.125, q = 3.5,
            qualityDisplay = PeqQualityDisplay.BW)), qStep = 0.125, frequencyStep = 37.5, pinResponse = false)
        val valid = repository.exportJson(original)
        val old = valid.replace(",\"pinResponse\":false", "").replace("\"schema\":2", "\"schema\":1")
        assertFalse(old.contains("pinResponse"))
        prefs.edit().putString(PeqRepository.KEY_STATE, old).commit()
        val loaded = repository.loadResult()
        assertFalse(loaded.corrupt)
        assertEquals(original.copy(pinResponse = true), loaded.state)
        assertEquals(old, prefs.getString(PeqRepository.KEY_STATE, null))
        listOf("0", "1", "null", "\"false\"", "{}", "[]").forEach { invalid ->
            val json = valid.replace("\"pinResponse\":false", "\"pinResponse\":$invalid")
            assertNotEquals(valid, json)
            assertThrows(IllegalArgumentException::class.java) { repository.importJson(json) }
            assertEquals(loaded.state, repository.load())
            assertEquals(old, prefs.getString(PeqRepository.KEY_STATE, null))
        }
    }

    @Test fun holdingARealSliderDragKeepsMarkerVisibleUntilTwoSecondsAfterRelease() {
        launch(PeqState(listOf(PeqBand(frequency = 1000.0))))
        val slider = row().findViewById<Slider>(R.id.peq_band_slider)
        assertTrue(slider.trackWidth > 0)
        val startX = slider.trackSidePadding + slider.trackWidth * 0.5f
        val targetX = slider.trackSidePadding + slider.trackWidth * 0.6f
        val downTime = SystemClock.uptimeMillis()
        fun dispatch(action: Int, time: Long, x: Float) {
            val event = MotionEvent.obtain(downTime, time, action, x, slider.height / 2f, 0)
            try { slider.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        dispatch(MotionEvent.ACTION_DOWN, downTime, startX)
        dispatch(MotionEvent.ACTION_MOVE, downTime + 20, targetX)
        assertNotNull(marker())
        advance(3000)
        assertTrue(view<PeqResponseView>(R.id.peq_curve).isMarkerVisible)
        assertNotNull(marker())
        dispatch(MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), targetX)
        assertTrue(repository.load().bands.single().gain > 0.0)
        assertEquals(fragment().editorState, repository.load())
        advance(1999)
        assertNotNull(marker())
        advance(1)
        assertNull(marker())
    }

    @Test fun eachResetDialogChoiceChangesOnlySelectedParametersAndNeverEnablesTheEffect() {
        val original = PeqState(listOf(
            PeqBand("a", frequency = 123.0, gain = 12.5, q = 3.0, qualityDisplay = PeqQualityDisplay.BW),
            PeqBand("b", type = PeqFilterType.HIGH_SHELF, enabled = false, frequency = 4321.0, gain = -27.0, q = 8.0),
            PeqBand("c", type = PeqFilterType.NOTCH, frequency = 18000.0, gain = 7.0, q = 29.0)),
            gainRange = 50.0, gainStep = 1.5, showQSlider = true, showFrequencySlider = true,
            qStep = 0.25, frequencyStep = 100.0, pinResponse = false)
        launch(original)
        view<MaterialButton>(R.id.peq_reset).performClick()
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(original, repository.load())
        PeqResetTarget.entries.forEach { target ->
            assertTrue(repository.save(original))
            controller!!.recreate()
            layout()
            view<MaterialButton>(R.id.peq_reset).performClick()
            val reset = dialog()
            assertEquals(5, reset.listView.adapter.count)
            reset.listView.performItemClick(null, target.ordinal, target.ordinal.toLong())
            idle()
            val saved = repository.load()
            assertEquals(original.copy(bands = saved.bands), saved)
            original.bands.forEachIndexed { index, band ->
                val expected = band.copy(
                    type = if (target == PeqResetTarget.FILTER_TYPE || target == PeqResetTarget.ALL)
                        PeqFilterType.PEAK else band.type,
                    frequency = if (target == PeqResetTarget.FREQUENCY || target == PeqResetTarget.ALL)
                        20.0 * 1000.0.pow(index / 2.0) else band.frequency,
                    q = if (target == PeqResetTarget.Q || target == PeqResetTarget.ALL) 0.7071067811865476 else band.q,
                    gain = if (target == PeqResetTarget.GAIN || target == PeqResetTarget.ALL) 0.0 else band.gain)
                assertEquals(expected, saved.bands[index])
            }
            assertFalse(prefs.getBoolean(application.getString(R.string.key_geq_enable), false))
            assertEquals(saved, fragment().editorState)
        }
    }

    @Test fun preciseGainInputUsesCurrentRangeAndAllowsOneHundredOnlyAfterRangeChange() {
        val original = PeqState(listOf(PeqBand(frequency = 1234.0, q = 2.0)), gainRange = 50.0)
        launch(original)
        val first = edit()
        assertTrue(first.findViewById<TextInputLayout>(R.id.peq_gain_box)!!.hint.toString().contains("50"))
        listOf("50.01", "-50.01", "100", "-100").forEach { value ->
            first.findViewById<EditText>(R.id.peq_gain_input)!!.setText(value)
            first.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(first.isShowing)
            assertNotNull(first.findViewById<TextInputLayout>(R.id.peq_gain_box)!!.error)
            assertEquals(original, repository.load())
        }
        first.findViewById<EditText>(R.id.peq_gain_input)!!.setText("50")
        first.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(first.isShowing)
        assertEquals(50.0, repository.load().bands.single().gain, 0.0)
        val second = edit()
        second.findViewById<EditText>(R.id.peq_gain_input)!!.setText("-50")
        second.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(second.isShowing)
        assertEquals(-50.0, repository.load().bands.single().gain, 0.0)
        val settings = settings()
        settings.findViewById<EditText>(R.id.peq_gain_range)!!.setText("100")
        settings.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(100.0, repository.load().gainRange, 0.0)
        val third = edit()
        third.findViewById<EditText>(R.id.peq_gain_input)!!.setText("100")
        third.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(third.isShowing)
        assertEquals(100.0, repository.load().bands.single().gain, 0.0)
        assertEquals(original.bands.single().frequency, repository.load().bands.single().frequency, 0.0)
        assertEquals(original.bands.single().q, repository.load().bands.single().q, 0.0)
    }

    @Test fun footerHasThreeEqualButtonsAndShortcutsAddBlankBandsAtEitherEdge() {
        val original = PeqModel.defaultState(3)
        launch(original)
        val footer = listOf(R.id.peq_settings, R.id.peq_reset, R.id.peq_presets).map { view<View>(it) }
        assertTrue(footer.all { it.parent === footer.first().parent })
        assertTrue(footer.maxOf { it.width } - footer.minOf { it.width } <= 1)
        view<View>(R.id.peq_add_front).performClick()
        assertEquals(original.bands, repository.load().bands.drop(1))
        val first = repository.load().bands.first()
        assertEquals(0.0, first.frequency, 0.0)
        assertEquals(0.0, first.gain, 0.0)
        assertEquals(0.5, first.q, 0.0)
        assertEquals(PeqFilterType.PEAK, first.type)
        view<View>(R.id.peq_add_back).performLongClick()
        val menu = dialog()
        assertEquals(5, menu.listView.adapter.count)
        menu.listView.performItemClick(null, 1, 1) // Ten at the back.
        idle()
        assertEquals(14, repository.load().bands.size)
        assertEquals(original.bands, repository.load().bands.subList(1, 4))
        assertTrue(repository.load().bands.takeLast(10).all { it.frequency == 0.0 && it.q == 0.5 })
        assertEquals(14, repository.load().bands.map { it.id }.toSet().size)
    }

    @Test fun customBatchRejectsOverflowAndStopsExactlyAt170() {
        launch(PeqModel.defaultState(160))
        view<View>(R.id.peq_add_front).performLongClick()
        dialog().listView.performItemClick(null, 4, 4)
        val custom = dialog()
        custom.findViewById<EditText>(R.id.peq_add_count)!!.setText("11")
        custom.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(custom.isShowing)
        assertNotNull(custom.findViewById<TextInputLayout>(R.id.peq_add_count_box)!!.error)
        assertEquals(160, repository.load().bands.size)
        custom.findViewById<EditText>(R.id.peq_add_count)!!.setText("10")
        custom.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(custom.isShowing)
        assertEquals(170, repository.load().bands.size)
        assertFalse(view<View>(R.id.peq_add_front).isEnabled)
        assertFalse(view<View>(R.id.peq_add_back).isEnabled)
    }

    @Test fun everyFilterCanSwitchBandwidthAndPreciseInputObeysSameQRange() {
        val original = PeqState(listOf(PeqBand(frequency = 1000.0, q = 3.0)), qRange = 3.0)
        launch(original)
        PeqFilterType.entries.forEach { type ->
            val edit = edit()
            val dropdown = edit.findViewById<MaterialAutoCompleteTextView>(R.id.peq_band_type)!!
            dropdown.onItemClickListener!!.onItemClick(null, null, type.ordinal, type.ordinal.toLong())
            val bw = edit.findViewById<MaterialButton>(R.id.peq_quality_bw)!!
            assertTrue(bw.isShown)
            bw.performClick()
            val value = edit.findViewById<EditText>(R.id.peq_quality_input)!!
            value.setText(PeqResponse.bandwidthOctaves(3.01, 1000.0).toString())
            edit.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(edit.isShowing)
            assertNotNull(edit.findViewById<TextInputLayout>(R.id.peq_quality_box)!!.error)
            value.setText(PeqResponse.bandwidthOctaves(3.0, 1000.0).toString())
            edit.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            assertFalse(edit.isShowing)
            assertEquals(3.0, repository.load().bands.single().q, 1e-9)
            assertEquals(type, repository.load().bands.single().type)
            assertEquals(PeqQualityDisplay.BW, repository.load().bands.single().qualityDisplay)
            val qEdit = edit()
            qEdit.findViewById<MaterialButton>(R.id.peq_quality_q)!!.performClick()
            qEdit.findViewById<EditText>(R.id.peq_quality_input)!!.setText("3.01")
            qEdit.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(qEdit.isShowing)
            qEdit.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            idle()
        }
    }

    @Test fun settingsQRangePersistsWithoutChangingBandsAndValueTapsFocusCorrectInput() {
        val original = PeqState(listOf(PeqBand(frequency = 1234.0, q = 8.0)),
            showQSlider = true, showFrequencySlider = true)
        launch(original)
        val settings = settings()
        settings.findViewById<EditText>(R.id.peq_q_range)!!.setText("2.5")
        settings.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertEquals(2.5, repository.load().qRange, 0.0)
        assertEquals(original.bands, repository.load().bands)
        listOf(R.id.peq_band_gain to R.id.peq_gain_input,
            R.id.peq_q_value to R.id.peq_quality_input,
            R.id.peq_frequency_value to R.id.peq_frequency_input).forEach { (label, input) ->
            row().findViewById<View>(label).performClick()
            val editor = dialog()
            val field = editor.findViewById<EditText>(input)!!
            assertTrue(field.hasFocus())
            assertEquals(0, field.selectionStart)
            assertEquals(field.text.length, field.selectionEnd)
            editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            idle()
        }
    }

    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_graphic_eq)
            setSupportActionBar(findViewById(R.id.toolbar))
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .replace(R.id.params, PeqFragment(), "peq").commitNow()
        }
    }
}
