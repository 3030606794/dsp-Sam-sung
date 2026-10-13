package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
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
import org.robolectric.shadows.ShadowToast
import java.time.Duration

/** Passive dismissal preserves a temporary edit; explicit cancellation and protection revoke it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqUi8InteractionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val lockPrefs get() = app.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE)
    private val repository get() = PeqRepository(app)
    private val locks get() = PeqBandLockStore(app)
    private var controller: ActivityController<HostActivity>? = null
    private var downTime = 0L
    private val graph get() = view<PeqResponseView>(R.id.peq_curve)

    @Before fun clear() {
        prefs.edit().clear().commit()
        lockPrefs.edit().clear().commit()
        app.getSharedPreferences(PeqEditorPreferences.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        PeqPreviewSession.end(PeqPreviewSession.begin(PeqModel.defaultState()))
    }

    @After fun close() {
        controller?.pause()?.stop()?.destroy()
        idle()
        PeqPreviewSession.end(PeqPreviewSession.begin(PeqModel.defaultState()))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(milliseconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
    private fun awaitSingle() = advance(ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
    private fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("peq") as PeqFragment
    private fun <T : View> view(id: Int): T = fragment().requireView().findViewById(id)
    private fun layout(target: View = controller!!.get().findViewById(android.R.id.content), dialog: Boolean = false) {
        idle()
        val density = app.resources.displayMetrics.density
        target.measure(View.MeasureSpec.makeMeasureSpec((411 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((820 * density).toInt(), if (dialog) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY))
        target.layout(0, 0, target.measuredWidth, target.measuredHeight)
        idle()
    }

    private fun original() = PeqState(listOf(
        PeqBand("first", frequency = 1000.0, gain = 3.0, q = 2.0),
        PeqBand("second", type = PeqFilterType.HIGH_SHELF, frequency = 5000.0,
            gain = -2.0, q = 3.0, enabled = false)), showQSlider = false, showFrequencySlider = false)

    private fun launch(locked: Boolean = false, unlockMasterOnDouble: Boolean = true): PeqState {
        val original = original()
        assertTrue(repository.save(original))
        assertTrue(PeqEditorPreferences(app).save(PeqEditorSettings(showInlineFilters = true,
            doubleTapUnlocksBand = unlockMasterOnDouble)))
        original.bands.forEach { band ->
            locks.setBandLocked(band.id, locked)
            if (locked) PeqEditField.entries.forEach { locks.setFieldLocked(band.id, it, true) }
        }
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout()
        return original
    }

    private fun card(index: Int = 0): View {
        val list = view<RecyclerView>(R.id.peq_band_list)
        list.scrollToPosition(index)
        layout()
        return requireNotNull(list.findViewHolderForAdapterPosition(index)).itemView
    }

    private fun open(id: Int, index: Int = 0): AlertDialog {
        card(index).findViewById<View>(id).performClick()
        idle()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also {
            assertTrue(it.isShowing)
            layout(it.window!!.decorView, dialog = true)
        }
    }
    private fun precise(index: Int = 0) = open(R.id.peq_band_number, index)
    private fun popup() = open(R.id.peq_band_title)
    private fun click(dialog: AlertDialog, id: Int) { dialog.findViewById<View>(id)!!.performClick(); idle() }
    private fun choose(dialog: AlertDialog, type: PeqFilterType) = click(dialog, PeqFilterTypeDialog.buttonId(type))
    private fun input(dialog: AlertDialog, id: Int) = dialog.findViewById<EditText>(id)!!
    private fun withType(state: PeqState, type: PeqFilterType) = state.copy(bands = state.bands.map {
        if (it.id == "first") it.copy(type = type) else it
    })
    private fun numericalDraft(editor: AlertDialog, original: PeqState): PeqState {
        choose(editor, PeqFilterType.LOW_SHELF)
        input(editor, R.id.peq_gain_input).setText("7.25")
        input(editor, R.id.peq_frequency_input).setText("1234.5")
        input(editor, R.id.peq_quality_input).setText("3.5")
        idle()
        return original.copy(bands = listOf(original.bands.first().copy(type = PeqFilterType.LOW_SHELF,
            gain = 7.25, frequency = 1234.5, q = 3.5), original.bands[1]))
    }
    private fun assertInputs(editor: AlertDialog, expected: PeqState) {
        val band = expected.bands.first()
        assertEquals(band.type, editor.findViewById<PeqFilterSelector>(R.id.peq_band_type)!!.selectedType)
        assertEquals(band.gain, input(editor, R.id.peq_gain_input).text.toString().toDouble(), 0.0)
        assertEquals(band.frequency, input(editor, R.id.peq_frequency_input).text.toString().toDouble(), 0.0)
        assertEquals(band.q, input(editor, R.id.peq_quality_input).text.toString().toDouble(), 0.0)
    }
    private fun assertSaved(expected: PeqState) {
        assertEquals(expected, repository.load())
        assertEquals(expected, fragment().editorState)
        assertFalse(prefs.getBoolean(app.getString(R.string.key_geq_enable), false))
    }
    private fun assertPreview(expected: PeqState, listening: Boolean) {
        assertEquals(expected, graph.requestedState)
        assertTrue(graph.isMarkerVisible)
        assertEquals(if (listening) expected else null, PeqPreviewSession.current())
        assertTrue(graph.contentDescription.toString().contains(app.getString(
            if (listening) R.string.peq_marker_listening else R.string.peq_marker_preview)))
    }
    private fun assertCleared(expected: PeqState) {
        assertNull(PeqPreviewSession.current())
        assertEquals(expected, graph.requestedState)
        assertFalse(graph.isMarkerVisible)
        assertSaved(expected)
    }
    private fun owner(): Any? {
        val state = PeqPreviewSession::class.java.getDeclaredField("preview").apply { isAccessible = true }
            .get(PeqPreviewSession) ?: return null
        return state.javaClass.getDeclaredField("owner").apply { isAccessible = true }.get(state)
    }
    private fun back(dialog: AlertDialog) {
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        idle()
        assertFalse(dialog.isShowing)
    }
    private fun event(button: View, action: Int) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val event = MotionEvent.obtain(downTime, now, action, button.width / 2f, button.height / 2f, 0)
        try { assertTrue(button.dispatchTouchEvent(event)) } finally { event.recycle() }
    }
    private fun tap(button: View) {
        assertTrue(button.width > 0 && button.height > 0)
        event(button, MotionEvent.ACTION_DOWN)
        advance(20)
        event(button, MotionEvent.ACTION_UP)
    }
    private fun doubleTap(button: View) { tap(button); advance(80); tap(button); awaitSingle() }

    @Test fun outsideTapKeepsThePopupDraftAndListeningWhileItsCancelButtonRevokesBoth() {
        val original = launch()
        val before = prefs.all.toMap()
        val picker = popup()
        choose(picker, PeqFilterType.NOTCH)
        click(picker, R.id.peq_filter_monitor)
        val owner = requireNotNull(owner())
        val now = SystemClock.uptimeMillis()
        val outsideCoordinate = -ViewConfiguration.get(picker.context).scaledWindowTouchSlop - 1f
        // Android 15 closes an outside tap on UP (or ACTION_OUTSIDE), not on DOWN.
        // Dispatch through the dialog/window so Material's inset listener participates too.
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
            outsideCoordinate, outsideCoordinate, 0)
        try { picker.dispatchTouchEvent(down) } finally { down.recycle() }
        assertTrue(picker.isShowing)
        advance(20)
        val up = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
            outsideCoordinate, outsideCoordinate, 0)
        try { assertTrue(picker.dispatchTouchEvent(up)) } finally { up.recycle() }
        idle()
        assertFalse(picker.isShowing)
        assertSame(owner, owner())
        assertPreview(withType(original, PeqFilterType.NOTCH), listening = true)
        assertSaved(original)
        assertEquals(before, prefs.all)
        val reopened = popup()
        assertSame(owner, owner())
        assertTrue(reopened.findViewById<View>(R.id.peq_filter_monitor)!!.isSelected)
        click(reopened, R.id.peq_filter_cancel)
        assertCleared(original)
        assertEquals(before, prefs.all)
    }

    @Test fun backKeepsFineNumericDraftOnlyInMemoryAndReopeningRestoresTheSameAudition() {
        val original = launch()
        val before = prefs.all.toMap()
        val editor = precise()
        val draft = numericalDraft(editor, original)
        click(editor, R.id.peq_band_monitor)
        val owner = requireNotNull(owner())
        back(editor)
        assertSame(owner, owner())
        assertPreview(draft, listening = true)
        assertSaved(original)
        assertEquals(before, prefs.all)
        assertTrue(card().findViewById<View>(R.id.peq_inline_monitor).isSelected)
        val reopened = precise()
        assertInputs(reopened, draft)
        assertTrue(reopened.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        assertSame(owner, owner())
        assertPreview(draft, listening = true)
        reopened.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertCleared(original)
        assertEquals(before, prefs.all)
        val cancelledDraft = precise()
        assertInputs(cancelledDraft, original)
        assertFalse(cancelledDraft.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        cancelledDraft.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
    }

    @Test fun passiveFineDismissPreservesTheUnmonitoredCurveAndInputsWithoutSavingOrStartingAudio() {
        val original = launch()
        val before = prefs.all.toMap()
        val editor = precise()
        val draft = numericalDraft(editor, original)
        editor.dismiss()
        idle()
        assertPreview(draft, listening = false)
        assertSaved(original)
        assertEquals(before, prefs.all)
        val reopened = precise()
        assertInputs(reopened, draft)
        assertFalse(reopened.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        assertPreview(draft, listening = false)
        reopened.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertCleared(original)
    }

    @Test fun typePopupAfterFineDismissCommitsOnlyTypeAndFineConfirmationLaterCommitsTheNumericDraft() {
        val original = launch()
        val editor = precise()
        val draft = numericalDraft(editor, original)
        click(editor, R.id.peq_band_monitor)
        val owner = requireNotNull(owner())
        back(editor)
        val picker = popup()
        assertSame(owner, owner())
        choose(picker, PeqFilterType.HIGH_SHELF)
        val updatedDraft = withType(draft, PeqFilterType.HIGH_SHELF)
        assertPreview(updatedDraft, listening = true)
        click(picker, R.id.peq_filter_confirm)
        assertSaved(withType(original, PeqFilterType.HIGH_SHELF))
        assertPreview(updatedDraft, listening = true)
        assertSame(owner, owner())
        val reopened = precise()
        assertInputs(reopened, updatedDraft)
        reopened.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertSaved(updatedDraft)
        assertPreview(updatedDraft, listening = true)
        assertSame(owner, owner())
        controller!!.pause()
        idle()
        assertCleared(updatedDraft)
        controller!!.resume()
        layout()
    }

    @Test fun activityPauseStillDiscardsAPassivelyDismissedFineDraftAndCannotRestoreItOnResume() {
        val original = launch()
        val editor = precise()
        val draft = numericalDraft(editor, original)
        click(editor, R.id.peq_band_monitor)
        back(editor)
        assertPreview(draft, listening = true)
        controller!!.pause()
        idle()
        assertCleared(original)
        controller!!.resume()
        layout()
        assertNull(PeqPreviewSession.current())
        val reopened = precise()
        assertInputs(reopened, original)
        assertFalse(reopened.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        reopened.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertCleared(original)
    }

    @Test fun cardPowerKeepsRetainedNumericDraftAndOwnerButImmediatelyUpdatesSavedBypass() {
        val original = launch()
        val editor = precise()
        val draft = numericalDraft(editor, original)
        click(editor, R.id.peq_band_monitor)
        val owner = requireNotNull(owner())
        back(editor)
        card().findViewById<View>(R.id.peq_band_power).performClick()
        advance(100)
        val disabledSaved = original.copy(bands = original.bands.map {
            if (it.id == "first") it.copy(enabled = false) else it
        })
        val disabledDraft = draft.copy(bands = draft.bands.map {
            if (it.id == "first") it.copy(enabled = false) else it
        })
        assertSaved(disabledSaved)
        assertPreview(disabledDraft, listening = true)
        assertSame(owner, owner())
        val reopened = precise()
        assertInputs(reopened, disabledDraft)
        assertFalse(reopened.findViewById<View>(R.id.peq_band_dialog_power)!!.isSelected)
        assertSame(owner, owner())
        reopened.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertCleared(disabledSaved)
    }

    @Test fun numericLocksRevokeRetainedFieldDraftsWithoutRevivingThemAfterUnlockAndReopen() {
        val original = launch()
        val before = prefs.all.toMap()
        val editor = precise()
        var draft = numericalDraft(editor, original)
        click(editor, R.id.peq_band_monitor)
        val owner = requireNotNull(owner())
        editor.dismiss()
        idle()
        listOf(PeqEditField.GAIN, PeqEditField.QUALITY, PeqEditField.FREQUENCY).forEach { field ->
            locks.setFieldLocked("first", field, true)
            idle()
            draft = draft.copy(bands = draft.bands.map { band ->
                if (band.id != "first") band else when (field) {
                    PeqEditField.GAIN -> band.copy(gain = original.bands.first().gain)
                    PeqEditField.QUALITY -> band.copy(q = original.bands.first().q,
                        qualityDisplay = original.bands.first().qualityDisplay)
                    PeqEditField.FREQUENCY -> band.copy(frequency = original.bands.first().frequency)
                    else -> error("Only numeric locks are exercised here")
                }
            })
            assertPreview(draft, listening = true)
            assertSame(owner, owner())
            assertSaved(original)
        }
        listOf(PeqEditField.GAIN, PeqEditField.QUALITY, PeqEditField.FREQUENCY).forEach {
            locks.setFieldLocked("first", it, false)
        }
        idle()
        val reopened = precise()
        assertInputs(reopened, draft)
        assertPreview(draft, listening = true)
        assertSame(owner, owner())
        assertEquals(before, prefs.all)
        reopened.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertCleared(original)
    }

    @Test fun changingBandsOrLockingTheTypeStillDiscardsAPassivelyDismissedNumericDraft() {
        val original = launch()
        val editor = precise()
        numericalDraft(editor, original)
        click(editor, R.id.peq_band_monitor)
        back(editor)
        card(1).findViewById<View>(R.id.peq_band_locate).performClick()
        idle()
        assertNull(PeqPreviewSession.current())
        assertEquals(original, graph.requestedState)
        assertSaved(original)
        val afterSwitch = precise()
        assertInputs(afterSwitch, original)
        assertFalse(afterSwitch.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        numericalDraft(afterSwitch, original)
        click(afterSwitch, R.id.peq_band_monitor)
        afterSwitch.cancel()
        idle()
        assertNotNull(PeqPreviewSession.current())
        locks.setFieldLocked("first", PeqEditField.FILTER_TYPE, true)
        idle()
        assertCleared(original)
        locks.setFieldLocked("first", PeqEditField.FILTER_TYPE, false)
        idle()
        val afterLock = precise()
        assertInputs(afterLock, original)
        assertFalse(afterLock.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        afterLock.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertCleared(original)
    }

    @Test fun invalidFineInputSurvivesBackWithoutRestartingAudioUntilTheInputIsCorrected() {
        val original = launch()
        val editor = precise()
        val draft = numericalDraft(editor, original)
        click(editor, R.id.peq_band_monitor)
        input(editor, R.id.peq_gain_input).setText("")
        idle()
        assertNull(PeqPreviewSession.current())
        assertEquals(original, graph.requestedState)
        back(editor)
        assertNull(PeqPreviewSession.current())
        assertSaved(original)
        val reopened = precise()
        assertEquals("", input(reopened, R.id.peq_gain_input).text.toString())
        assertTrue(reopened.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        assertNull(PeqPreviewSession.current())
        input(reopened, R.id.peq_gain_input).setText("7.25")
        idle()
        assertPreview(draft, listening = true)
        assertSaved(original)
        reopened.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertCleared(original)
    }

    @Test fun defaultDoubleTapOnTheFineMasterUnlocksMasterAndFieldsWithoutChangingAudioParameters() {
        val original = launch(locked = true)
        val before = prefs.all.toMap()
        val otherLocks = lockPrefs.all.filterKeys { it.startsWith("second.") }
        assertTrue(PeqEditorPreferences(app).load().doubleTapUnlocksBand)
        val editor = precise()
        doubleTap(editor.findViewById(R.id.peq_band_dialog_lock)!!)
        assertFalse(locks.isBandLocked("first"))
        PeqEditField.entries.forEach { assertFalse(locks.isFieldLocked("first", it)) }
        assertEquals(otherLocks, lockPrefs.all.filterKeys { it.startsWith("second.") })
        listOf(R.id.peq_gain_input, R.id.peq_frequency_input, R.id.peq_quality_input, R.id.peq_band_type,
            R.id.peq_band_monitor).forEach { assertTrue(editor.findViewById<View>(it)!!.isEnabled) }
        assertSaved(original)
        assertEquals(before, prefs.all)
        assertNull(PeqPreviewSession.current())
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
    }

    @Test fun disablingMasterUnlockMakesFineDoubleTapClearOnlyFieldsAndLeavesReadOnlyProtection() {
        val original = launch(locked = true, unlockMasterOnDouble = false)
        val before = prefs.all.toMap()
        val otherLocks = lockPrefs.all.filterKeys { it.startsWith("second.") }
        val editor = precise()
        val master = editor.findViewById<View>(R.id.peq_band_dialog_lock)!!
        doubleTap(master)
        assertTrue(locks.isBandLocked("first"))
        PeqEditField.entries.forEach { assertFalse(locks.isFieldLocked("first", it)) }
        assertEquals(otherLocks, lockPrefs.all.filterKeys { it.startsWith("second.") })
        assertFalse(editor.findViewById<View>(R.id.peq_band_type)!!.isEnabled)
        assertFalse(editor.findViewById<View>(R.id.peq_band_monitor)!!.isEnabled)
        assertFalse(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        tap(master)
        assertFalse("A single release now unlocks immediately, without waiting for double-tap confirmation", locks.isBandLocked("first"))
        assertTrue(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        awaitSingle()
        assertFalse(locks.isBandLocked("first"))
        assertTrue(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertSaved(original)
        assertEquals(before, prefs.all)
        assertNull(PeqPreviewSession.current())
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
    }

    @Test fun plusSingleLocatesAndDoubleTapCopiesExactlyOnceWithFreshProtectionAndBypassDefaults() {
        val original = launch()
        PeqEditField.entries.forEach { locks.setFieldLocked("first", it, true) }
        val beforeLocks = lockPrefs.all.toMap()
        val plus = card().findViewById<View>(R.id.peq_band_locate)
        tap(plus)
        assertTrue("The first release locates immediately, without waiting for the double-tap window", graph.isMarkerVisible)
        assertEquals(2, repository.load().bands.size)
        awaitSingle()
        assertTrue(graph.isMarkerVisible)
        assertSaved(original)
        doubleTap(plus)
        layout()
        val copiedState = repository.load()
        assertEquals(3, copiedState.bands.size)
        val copy = copiedState.bands[1]
        assertNotEquals(original.bands.first().id, copy.id)
        assertFalse(original.bands.any { it.id == copy.id })
        assertEquals(original.bands.first().copy(id = copy.id, enabled = false), copy)
        assertEquals(original.copy(bands = listOf(original.bands.first(), copy, original.bands[1])), copiedState)
        assertEquals(copiedState, fragment().editorState)
        assertFalse(locks.isBandLocked(copy.id))
        assertTrue(locks.isNew(copy.id))
        PeqEditField.entries.forEach { assertFalse(locks.isFieldLocked(copy.id, it)) }
        assertEquals(beforeLocks, lockPrefs.all.filterKeys { !it.startsWith("${copy.id}.") })
        assertEquals(app.getString(R.string.peq_copied_band_numbers, 1, 2), ShadowToast.getTextOfLatestToast())
        assertNull(PeqPreviewSession.current())
        awaitSingle()
        assertEquals(3, repository.load().bands.size)
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
