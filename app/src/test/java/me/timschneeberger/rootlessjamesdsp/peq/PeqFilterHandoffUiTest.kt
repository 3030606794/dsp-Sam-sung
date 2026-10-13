package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
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

/** All three filter editors share type and audition; fine numeric drafts commit only in fine editing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqFilterHandoffUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val repository get() = PeqRepository(app)
    private val locks get() = PeqBandLockStore(app)
    private var controller: ActivityController<HostActivity>? = null
    private val graph get() = view<PeqResponseView>(R.id.peq_curve)

    @Before fun clear() {
        prefs.edit().clear().commit()
        app.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(PeqEditorPreferences.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        PeqPreviewSession.end(PeqPreviewSession.begin(PeqModel.defaultState()))
    }

    @After fun close() {
        controller?.pause()?.stop()?.destroy()
        idle()
        PeqPreviewSession.end(PeqPreviewSession.begin(PeqModel.defaultState()))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("peq") as PeqFragment
    private fun <T : View> view(id: Int): T = fragment().requireView().findViewById(id)
    private fun list() = view<RecyclerView>(R.id.peq_band_list)

    private fun layout() {
        idle()
        val content = controller!!.get().findViewById<View>(android.R.id.content)
        val density = app.resources.displayMetrics.density
        val width = (411 * density).toInt()
        val height = (820 * density).toInt()
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, width, height)
        idle()
    }

    private fun original() = PeqState(listOf(
        PeqBand("first", frequency = 1000.0, gain = 3.0, q = 2.0),
        PeqBand("second", type = PeqFilterType.HIGH_SHELF, frequency = 5000.0,
            gain = -2.0, q = 3.0, enabled = false)),
        showQSlider = false, showFrequencySlider = false)

    private fun launch(inline: Boolean = true): PeqState {
        val state = original()
        assertTrue(repository.save(state))
        assertTrue(PeqEditorPreferences(app).save(PeqEditorSettings(showInlineFilters = inline)))
        state.bands.forEach { locks.setBandLocked(it.id, false) }
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout()
        return state
    }

    private fun row(index: Int = 0): View {
        list().scrollToPosition(index)
        layout()
        return requireNotNull(list().findViewHolderForAdapterPosition(index)).itemView
    }

    private fun popup(index: Int = 0): AlertDialog {
        row(index).findViewById<View>(R.id.peq_band_title).performClick()
        idle()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also { assertTrue(it.isShowing) }
    }

    private fun precise(index: Int = 0): AlertDialog {
        row(index).findViewById<View>(R.id.peq_band_number).performClick()
        idle()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also { assertTrue(it.isShowing) }
    }

    private fun click(popup: AlertDialog, id: Int) {
        popup.findViewById<View>(id)!!.performClick()
        idle()
    }
    private fun choose(popup: AlertDialog, type: PeqFilterType) = click(popup, PeqFilterTypeDialog.buttonId(type))
    private fun inlineType(type: PeqFilterType): View {
        val card = row()
        card.findViewById<View>(PeqFilterTypeDialog.buttonId(type)).performClick()
        idle()
        return card
    }
    private fun clickCard(card: View, id: Int) { card.findViewById<View>(id).performClick(); idle() }
    private fun withType(state: PeqState, type: PeqFilterType) = state.copy(bands = state.bands.map {
        if (it.id == "first") it.copy(type = type) else it
    })
    private fun assertSaved(expected: PeqState) {
        assertEquals(expected, repository.load())
        assertEquals(expected, fragment().editorState)
        assertFalse(prefs.getBoolean(app.getString(R.string.key_geq_enable), false))
    }
    private fun assertPreview(expected: PeqState, listening: Boolean) {
        assertEquals(expected, graph.requestedState)
        assertTrue(graph.isMarkerVisible)
        val active = app.getString(if (listening) R.string.peq_marker_listening else R.string.peq_marker_preview)
        val inactive = app.getString(if (listening) R.string.peq_marker_preview else R.string.peq_marker_listening)
        assertTrue(graph.contentDescription.toString().contains(active))
        assertFalse(graph.contentDescription.toString().contains(inactive))
        assertEquals(if (listening) expected else null, PeqPreviewSession.current())
    }
    private fun assertCardDraft(card: View, type: PeqFilterType, listening: Boolean) {
        assertTrue(card.findViewById<View>(PeqFilterTypeDialog.buttonId(type)).isSelected)
        assertEquals(listening, card.findViewById<View>(R.id.peq_inline_monitor).isSelected)
    }
    private fun assertCleared(expected: PeqState) {
        assertNull(PeqPreviewSession.current())
        assertEquals(expected, graph.requestedState)
        assertFalse(graph.isMarkerVisible)
        assertSaved(expected)
    }

    // Final audio state alone cannot detect an unwanted end/begin interruption during handoff.
    private fun sessionOwner(): Any? {
        val preview = PeqPreviewSession::class.java.getDeclaredField("preview").apply { isAccessible = true }
            .get(PeqPreviewSession) ?: return null
        return preview.javaClass.getDeclaredField("owner").apply { isAccessible = true }.get(preview)
    }

    @Test fun nonListeningCardDraftOpensWithTheSameSelectionCurveMarkerAndCommittedType() {
        val original = launch()
        val before = prefs.all.toMap()
        val card = inlineType(PeqFilterType.LOW_PASS)
        val draft = withType(original, PeqFilterType.LOW_PASS)
        assertPreview(draft, listening = false)
        val picker = popup()
        val selector = picker.findViewById<PeqFilterSelector>(R.id.peq_filter_types)!!
        assertEquals(PeqFilterType.LOW_PASS, selector.selectedType)
        assertEquals(original.bands.first().type, selector.committedType)
        assertFalse(picker.findViewById<View>(R.id.peq_filter_monitor)!!.isSelected)
        assertPreview(draft, listening = false)
        choose(picker, PeqFilterType.HIGH_PASS)
        assertCardDraft(card, PeqFilterType.HIGH_PASS, listening = false)
        assertPreview(withType(original, PeqFilterType.HIGH_PASS), listening = false)
        assertSaved(original)
        assertEquals(before, prefs.all)
        click(picker, R.id.peq_filter_cancel)
        assertCleared(original)
        assertCardDraft(card, original.bands.first().type, listening = false)
        assertFalse(card.findViewById<View>(R.id.peq_inline_confirm).isEnabled)
        assertEquals(before, prefs.all)
    }

    @Test fun listeningHandoffAndPopupConfirmationKeepTheSameAudioOwnerUntilMonitoringStops() {
        val original = launch()
        val card = inlineType(PeqFilterType.LOW_PASS)
        clickCard(card, R.id.peq_inline_monitor)
        val owner = requireNotNull(sessionOwner())
        val picker = popup()
        assertSame(owner, sessionOwner())
        assertTrue(picker.findViewById<View>(R.id.peq_filter_monitor)!!.isSelected)
        assertEquals(PeqFilterType.LOW_PASS, picker.findViewById<PeqFilterSelector>(R.id.peq_filter_types)!!.selectedType)
        assertPreview(withType(original, PeqFilterType.LOW_PASS), listening = true)
        choose(picker, PeqFilterType.HIGH_PASS)
        val confirmed = withType(original, PeqFilterType.HIGH_PASS)
        assertSame(owner, sessionOwner())
        assertCardDraft(card, PeqFilterType.HIGH_PASS, listening = true)
        assertPreview(confirmed, listening = true)
        assertSaved(original)
        click(picker, R.id.peq_filter_confirm)
        layout()
        assertFalse(picker.isShowing)
        assertSame(owner, sessionOwner())
        assertSaved(confirmed)
        assertPreview(confirmed, listening = true)
        assertCardDraft(row(), PeqFilterType.HIGH_PASS, listening = true)
        assertEquals(PeqBandAdapter.typeLabel(app, PeqFilterType.HIGH_PASS),
            row().findViewById<TextView>(R.id.peq_band_title).text.toString())

        val reopened = popup()
        assertSame(owner, sessionOwner())
        assertTrue(reopened.findViewById<View>(R.id.peq_filter_monitor)!!.isSelected)
        choose(reopened, PeqFilterType.NOTCH)
        assertSame(owner, sessionOwner())
        click(reopened, R.id.peq_filter_monitor)
        assertPreview(withType(confirmed, PeqFilterType.NOTCH), listening = false)
        assertCardDraft(row(), PeqFilterType.NOTCH, listening = false)
        click(reopened, R.id.peq_filter_monitor)
        assertPreview(withType(confirmed, PeqFilterType.NOTCH), listening = true)
        click(reopened, R.id.peq_filter_cancel)
        assertCleared(confirmed)
        assertCardDraft(row(), PeqFilterType.HIGH_PASS, listening = false)
    }

    @Test fun popupSelectionAndMonitorChangesSynchronizeTheVisibleCardBeforeAnySave() {
        val original = launch()
        val before = prefs.all.toMap()
        val card = row()
        val picker = popup()
        choose(picker, PeqFilterType.NOTCH)
        assertCardDraft(card, PeqFilterType.NOTCH, listening = false)
        assertPreview(withType(original, PeqFilterType.NOTCH), listening = false)
        click(picker, R.id.peq_filter_monitor)
        assertCardDraft(card, PeqFilterType.NOTCH, listening = true)
        assertPreview(withType(original, PeqFilterType.NOTCH), listening = true)
        click(picker, R.id.peq_filter_monitor)
        choose(picker, PeqFilterType.BAND_PASS)
        val draft = withType(original, PeqFilterType.BAND_PASS)
        assertCardDraft(card, PeqFilterType.BAND_PASS, listening = false)
        assertPreview(draft, listening = false)
        assertSaved(original)
        assertEquals(before, prefs.all)
        click(picker, R.id.peq_filter_confirm)
        layout()
        assertSaved(draft)
        assertPreview(draft, listening = false)
        assertCardDraft(row(), PeqFilterType.BAND_PASS, listening = false)
    }

    @Test fun cancellingOrDismissingAPopupAlsoCancelsTheInheritedCardAudition() {
        val original = launch()
        val before = prefs.all.toMap()
        val card = inlineType(PeqFilterType.LOW_PASS)
        clickCard(card, R.id.peq_inline_monitor)
        var picker = popup()
        choose(picker, PeqFilterType.NOTCH)
        click(picker, R.id.peq_filter_cancel)
        assertCleared(original)
        assertCardDraft(card, original.bands.first().type, listening = false)
        assertEquals(before, prefs.all)

        picker = popup()
        assertFalse(picker.findViewById<View>(R.id.peq_filter_monitor)!!.isSelected)
        assertEquals(original.bands.first().type, picker.findViewById<PeqFilterSelector>(R.id.peq_filter_types)!!.selectedType)
        choose(picker, PeqFilterType.HIGH_PASS)
        click(picker, R.id.peq_filter_monitor)
        picker.cancel()
        idle()
        assertCleared(original)
        assertCardDraft(row(), original.bands.first().type, listening = false)
        assertEquals(before, prefs.all)
    }

    @Test fun acquiringThePopupTypeLockRevokesBothControlsAndPreservesEveryOtherField() {
        val original = launch()
        locks.setFieldLocked("first", PeqEditField.GAIN, true)
        val before = prefs.all.toMap()
        val card = inlineType(PeqFilterType.LOW_PASS)
        clickCard(card, R.id.peq_inline_monitor)
        val picker = popup()
        click(picker, R.id.peq_filter_dialog_lock)
        assertTrue(locks.isFieldLocked("first", PeqEditField.FILTER_TYPE))
        assertTrue(locks.isFieldLocked("first", PeqEditField.GAIN))
        assertFalse(locks.isBandLocked("first"))
        assertFalse(locks.isFieldLocked("second", PeqEditField.FILTER_TYPE))
        assertCleared(original)
        assertCardDraft(row(), original.bands.first().type, listening = false)
        assertFalse(picker.findViewById<View>(R.id.peq_filter_monitor)!!.isEnabled)
        assertFalse(picker.findViewById<View>(R.id.peq_filter_monitor)!!.isSelected)
        assertFalse(picker.findViewById<View>(R.id.peq_filter_confirm)!!.isEnabled)
        assertEquals(original.bands.first().type, picker.findViewById<PeqFilterSelector>(R.id.peq_filter_types)!!.selectedType)
        choose(picker, PeqFilterType.NOTCH)
        click(picker, R.id.peq_filter_monitor)
        assertCleared(original)
        assertEquals(before, prefs.all)
        click(picker, R.id.peq_filter_dialog_lock)
        assertTrue(picker.findViewById<View>(R.id.peq_filter_monitor)!!.isEnabled)
        assertFalse(picker.findViewById<View>(R.id.peq_filter_monitor)!!.isSelected)
        click(picker, R.id.peq_filter_cancel)
        assertCleared(original)
    }

    @Test fun activityPauseStopsTheSharedAuditionAndResumeCannotRestartIt() {
        val original = launch()
        val before = prefs.all.toMap()
        val card = inlineType(PeqFilterType.HIGH_PASS)
        clickCard(card, R.id.peq_inline_monitor)
        val picker = popup()
        choose(picker, PeqFilterType.LOW_PASS)
        controller!!.pause()
        idle()
        assertCleared(original)
        assertFalse(picker.findViewById<View>(R.id.peq_filter_monitor)!!.isSelected)
        controller!!.resume()
        layout()
        assertNull(PeqPreviewSession.current())
        assertEquals(original, graph.requestedState)
        assertCardDraft(row(), original.bands.first().type, listening = false)
        assertSaved(original)
        assertEquals(before, prefs.all)
        click(picker, R.id.peq_filter_cancel)
        assertCleared(original)
    }

    @Test fun lockingTheCardAfterPopupConfirmationEndsListeningWithoutUndoingTheSavedFilter() {
        val original = launch()
        val picker = popup()
        choose(picker, PeqFilterType.LOW_PASS)
        click(picker, R.id.peq_filter_monitor)
        click(picker, R.id.peq_filter_confirm)
        val confirmed = withType(original, PeqFilterType.LOW_PASS)
        assertPreview(confirmed, listening = true)
        val card = row()
        clickCard(card, R.id.peq_band_lock)
        assertTrue(locks.isBandLocked("first"))
        assertFalse(locks.isFieldLocked("first", PeqEditField.FILTER_TYPE))
        assertFalse(locks.isBandLocked("second"))
        assertCleared(confirmed)
        assertCardDraft(card, PeqFilterType.LOW_PASS, listening = false)
        assertFalse(card.findViewById<View>(R.id.peq_inline_monitor).isEnabled)
        assertFalse(card.findViewById<View>(R.id.peq_band_title).isEnabled)
    }

    @Test fun leavingTheFragmentEndsTheSharedAudioAndDoesNotPersistItsDraft() {
        val original = launch()
        val before = prefs.all.toMap()
        val card = inlineType(PeqFilterType.NOTCH)
        clickCard(card, R.id.peq_inline_monitor)
        val picker = popup()
        val chart = graph
        controller!!.get().supportFragmentManager.beginTransaction().remove(fragment()).commitNow()
        idle()
        assertNull(PeqPreviewSession.current())
        assertFalse(picker.isShowing)
        assertEquals(original, chart.requestedState)
        assertFalse(chart.isMarkerVisible)
        assertEquals(original, repository.load())
        assertEquals(before, prefs.all)
    }

    @Test fun selectingAnotherBandAfterConfirmationClearsListeningButKeepsTheConfirmedFilter() {
        val original = launch()
        val card = inlineType(PeqFilterType.LOW_PASS)
        clickCard(card, R.id.peq_inline_monitor)
        val picker = popup()
        choose(picker, PeqFilterType.NOTCH)
        click(picker, R.id.peq_filter_confirm)
        val confirmed = withType(original, PeqFilterType.NOTCH)
        assertPreview(confirmed, listening = true)
        val other = requireNotNull(list().findViewHolderForAdapterPosition(1)).itemView
        other.findViewById<View>(R.id.peq_band_locate).performClick()
        idle()
        assertNull(PeqPreviewSession.current())
        assertEquals(confirmed, graph.requestedState)
        assertSaved(confirmed)
        assertCardDraft(row(), PeqFilterType.NOTCH, listening = false)
        assertFalse(row().findViewById<View>(R.id.peq_inline_confirm).isEnabled)
    }

    @Test fun hiddenInlineControlsCannotCancelAnOpenPopupOrItsConfirmedAuditionDuringRebind() {
        val original = launch(inline = false)
        assertEquals(View.GONE, row().findViewById<View>(R.id.peq_inline_filter).visibility)
        val picker = popup()
        choose(picker, PeqFilterType.HIGH_PASS)
        val draft = withType(original, PeqFilterType.HIGH_PASS)
        click(picker, R.id.peq_filter_monitor)
        val owner = requireNotNull(sessionOwner())
        list().adapter!!.notifyDataSetChanged()
        layout()
        assertTrue(picker.isShowing)
        assertSame(owner, sessionOwner())
        assertTrue(picker.findViewById<View>(R.id.peq_filter_monitor)!!.isSelected)
        assertPreview(draft, listening = true)
        assertSaved(original)
        click(picker, R.id.peq_filter_confirm)
        list().adapter!!.notifyDataSetChanged()
        layout()
        assertFalse(picker.isShowing)
        assertSame(owner, sessionOwner())
        assertPreview(draft, listening = true)
        assertSaved(draft)
        val reopened = popup()
        assertTrue(reopened.findViewById<View>(R.id.peq_filter_monitor)!!.isSelected)
        click(reopened, R.id.peq_filter_monitor)
        assertPreview(draft, listening = false)
        click(reopened, R.id.peq_filter_cancel)
        assertCleared(draft)
    }

    @Test fun preciseEditorInheritsTheUnconfirmedCardTypeAndItsNonListeningPreview() {
        val original = launch()
        val before = prefs.all.toMap()
        val card = inlineType(PeqFilterType.LOW_PASS)
        val editor = precise()
        val selector = editor.findViewById<PeqFilterSelector>(R.id.peq_band_type)!!
        assertEquals(PeqFilterType.LOW_PASS, selector.selectedType)
        assertEquals(original.bands.first().type, selector.committedType)
        assertFalse(editor.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        assertPreview(withType(original, PeqFilterType.LOW_PASS), listening = false)
        choose(editor, PeqFilterType.HIGH_PASS)
        assertCardDraft(card, PeqFilterType.HIGH_PASS, listening = false)
        assertPreview(withType(original, PeqFilterType.HIGH_PASS), listening = false)
        assertSaved(original)
        assertEquals(before, prefs.all)
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertCleared(original)
        assertCardDraft(row(), original.bands.first().type, listening = false)
    }

    @Test fun preciseConfirmationPreservesTheInheritedAudioOwnerAndPassesTheSavedStateToThePopup() {
        val original = launch()
        val card = inlineType(PeqFilterType.LOW_SHELF)
        clickCard(card, R.id.peq_inline_monitor)
        val owner = requireNotNull(sessionOwner())
        val editor = precise()
        assertSame(owner, sessionOwner())
        assertTrue(editor.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        assertEquals(PeqFilterType.LOW_SHELF, editor.findViewById<PeqFilterSelector>(R.id.peq_band_type)!!.selectedType)
        assertPreview(withType(original, PeqFilterType.LOW_SHELF), listening = true)
        choose(editor, PeqFilterType.HIGH_SHELF)
        editor.findViewById<EditText>(R.id.peq_gain_input)!!.setText("4")
        val draft = withType(original, PeqFilterType.HIGH_SHELF).let {
            it.copy(bands = listOf(it.bands.first().copy(gain = 4.0), it.bands[1]))
        }
        assertSame(owner, sessionOwner())
        assertPreview(draft, listening = true)
        assertCardDraft(card, PeqFilterType.HIGH_SHELF, listening = true)
        assertSaved(original)
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layout()
        assertFalse(editor.isShowing)
        assertSame(owner, sessionOwner())
        assertSaved(draft)
        assertPreview(draft, listening = true)
        assertCardDraft(row(), PeqFilterType.HIGH_SHELF, listening = true)
        val picker = popup()
        assertSame(owner, sessionOwner())
        assertEquals(PeqFilterType.HIGH_SHELF, picker.findViewById<PeqFilterSelector>(R.id.peq_filter_types)!!.selectedType)
        assertTrue(picker.findViewById<View>(R.id.peq_filter_monitor)!!.isSelected)
        click(picker, R.id.peq_filter_cancel)
        assertCleared(draft)
    }

    @Test fun preciseAndCardMonitorControlsStaySynchronizedWithoutSavingTheirSharedDraft() {
        val original = launch()
        val card = inlineType(PeqFilterType.NOTCH)
        clickCard(card, R.id.peq_inline_monitor)
        val editor = precise()
        assertTrue(editor.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        click(editor, R.id.peq_band_monitor)
        assertPreview(withType(original, PeqFilterType.NOTCH), listening = false)
        assertCardDraft(card, PeqFilterType.NOTCH, listening = false)
        clickCard(card, R.id.peq_inline_monitor)
        assertTrue(editor.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        assertPreview(withType(original, PeqFilterType.NOTCH), listening = true)
        clickCard(card, PeqFilterTypeDialog.buttonId(PeqFilterType.BAND_PASS))
        assertEquals(PeqFilterType.BAND_PASS, editor.findViewById<PeqFilterSelector>(R.id.peq_band_type)!!.selectedType)
        assertPreview(withType(original, PeqFilterType.BAND_PASS), listening = true)
        assertSaved(original)
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertCleared(original)
        assertCardDraft(row(), original.bands.first().type, listening = false)
    }

    @Test fun popupOverPreciseSharesTypeAndMonitorButCannotCommitItsNumericDraft() {
        val original = launch()
        val editor = precise()
        choose(editor, PeqFilterType.LOW_SHELF)
        editor.findViewById<EditText>(R.id.peq_gain_input)!!.setText("7")
        click(editor, R.id.peq_band_monitor)
        val owner = requireNotNull(sessionOwner())
        val picker = popup()
        assertTrue(editor.isShowing)
        assertSame(owner, sessionOwner())
        assertEquals(PeqFilterType.LOW_SHELF, picker.findViewById<PeqFilterSelector>(R.id.peq_filter_types)!!.selectedType)
        assertTrue(picker.findViewById<View>(R.id.peq_filter_monitor)!!.isSelected)
        choose(picker, PeqFilterType.HIGH_SHELF)
        assertEquals(PeqFilterType.HIGH_SHELF, editor.findViewById<PeqFilterSelector>(R.id.peq_band_type)!!.selectedType)
        assertSame(owner, sessionOwner())
        click(picker, R.id.peq_filter_monitor)
        assertFalse(editor.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        assertNull(PeqPreviewSession.current())
        click(picker, R.id.peq_filter_monitor)
        assertTrue(editor.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        click(picker, R.id.peq_filter_confirm)
        idle()
        val typeOnly = withType(original, PeqFilterType.HIGH_SHELF)
        assertSaved(typeOnly)
        assertTrue(editor.isShowing)
        assertEquals(7.0, editor.findViewById<EditText>(R.id.peq_gain_input)!!.text.toString().toDouble(), 0.0)
        assertTrue(editor.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertCleared(typeOnly)
        assertCardDraft(row(), PeqFilterType.HIGH_SHELF, listening = false)
    }

    @Test fun preciseTypeAndMasterLocksRevokeTheSharedAuditionAndProtectConfirmedValues() {
        val original = launch()
        val before = prefs.all.toMap()
        val card = inlineType(PeqFilterType.LOW_PASS)
        clickCard(card, R.id.peq_inline_monitor)
        val editor = precise()
        click(editor, R.id.peq_type_lock)
        assertTrue(locks.isFieldLocked("first", PeqEditField.FILTER_TYPE))
        assertFalse(editor.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        assertFalse(editor.findViewById<View>(R.id.peq_band_monitor)!!.isEnabled)
        assertEquals(original.bands.first().type, editor.findViewById<PeqFilterSelector>(R.id.peq_band_type)!!.selectedType)
        assertNull(PeqPreviewSession.current())
        assertEquals(original, graph.requestedState)
        assertCardDraft(card, original.bands.first().type, listening = false)
        choose(editor, PeqFilterType.NOTCH)
        click(editor, R.id.peq_band_monitor)
        assertNull(PeqPreviewSession.current())
        assertSaved(original)
        click(editor, R.id.peq_type_lock)
        assertFalse(editor.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        choose(editor, PeqFilterType.HIGH_PASS)
        click(editor, R.id.peq_band_monitor)
        assertPreview(withType(original, PeqFilterType.HIGH_PASS), listening = true)
        click(editor, R.id.peq_band_dialog_lock)
        assertTrue(locks.isBandLocked("first"))
        assertFalse(editor.findViewById<View>(R.id.peq_band_monitor)!!.isEnabled)
        assertFalse(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertCleared(original)
        assertEquals(before, prefs.all)
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertCleared(original)
    }

    @Test fun precisePauseEndsAnInheritedSharedAuditionAndResumeDoesNotRestoreIt() {
        val original = launch()
        val card = inlineType(PeqFilterType.LOW_PASS)
        clickCard(card, R.id.peq_inline_monitor)
        val editor = precise()
        choose(editor, PeqFilterType.HIGH_PASS)
        controller!!.pause()
        idle()
        assertCleared(original)
        assertFalse(editor.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        controller!!.resume()
        layout()
        assertNull(PeqPreviewSession.current())
        assertEquals(original, graph.requestedState)
        assertSaved(original)
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertCleared(original)
    }

    @Test fun externalNumericLocksRestoreTheActualAuditionInPlaceWhileTypeAndMasterLocksEndIt() {
        val original = launch()
        val before = prefs.all.toMap()
        val editor = precise()
        choose(editor, PeqFilterType.LOW_SHELF)
        editor.findViewById<EditText>(R.id.peq_gain_input)!!.setText("7")
        editor.findViewById<EditText>(R.id.peq_frequency_input)!!.setText("1200")
        editor.findViewById<EditText>(R.id.peq_quality_input)!!.setText("3")
        click(editor, R.id.peq_band_monitor)
        val owner = requireNotNull(sessionOwner())
        var current = original.copy(bands = listOf(original.bands.first().copy(
            type = PeqFilterType.LOW_SHELF, gain = 7.0, frequency = 1200.0, q = 3.0), original.bands[1]))
        assertPreview(current, listening = true)

        listOf(PeqEditField.GAIN, PeqEditField.QUALITY, PeqEditField.FREQUENCY).forEach { field ->
            locks.setFieldLocked("first", field, true)
            idle()
            val saved = original.bands.first()
            current = current.copy(bands = listOf(when (field) {
                PeqEditField.GAIN -> current.bands.first().copy(gain = saved.gain)
                PeqEditField.QUALITY -> current.bands.first().copy(q = saved.q)
                PeqEditField.FREQUENCY -> current.bands.first().copy(frequency = saved.frequency)
                PeqEditField.FILTER_TYPE -> error("Only numeric locks are part of this stage")
            }, original.bands[1]))
            val inputId = when (field) {
                PeqEditField.GAIN -> R.id.peq_gain_input
                PeqEditField.QUALITY -> R.id.peq_quality_input
                PeqEditField.FREQUENCY -> R.id.peq_frequency_input
                PeqEditField.FILTER_TYPE -> error("Only numeric locks are part of this stage")
            }
            assertFalse(editor.findViewById<View>(inputId)!!.isEnabled)
            assertSame("A numeric lock updates the live owner instead of interrupting it", owner, sessionOwner())
            assertTrue(editor.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
            assertPreview(current, listening = true)
            assertSaved(original)
            assertEquals(before, prefs.all)
            assertFalse(locks.isFieldLocked("second", field))
        }

        locks.setFieldLocked("first", PeqEditField.FILTER_TYPE, true)
        idle()
        assertNull(PeqPreviewSession.current())
        assertEquals(original, graph.requestedState)
        assertFalse(editor.findViewById<View>(R.id.peq_band_monitor)!!.isEnabled)
        assertFalse(editor.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        assertSaved(original)
        locks.setFieldLocked("first", PeqEditField.FILTER_TYPE, false)
        idle()
        choose(editor, PeqFilterType.HIGH_SHELF)
        click(editor, R.id.peq_band_monitor)
        assertNotNull(sessionOwner())
        assertNotSame(owner, sessionOwner())
        assertPreview(withType(original, PeqFilterType.HIGH_SHELF), listening = true)
        locks.setBandLocked("first", true)
        idle()
        assertCleared(original)
        assertFalse(editor.findViewById<View>(R.id.peq_band_monitor)!!.isEnabled)
        assertFalse(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertEquals(before, prefs.all)
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertCleared(original)
    }

    @Test fun detachedAndReattachedCardsRecoverTheFineEditorsSharedDraftWithoutInterruptingAudio() {
        val original = launch()
        val card = inlineType(PeqFilterType.LOW_PASS)
        clickCard(card, R.id.peq_inline_monitor)
        val editor = precise()
        choose(editor, PeqFilterType.HIGH_PASS)
        val draft = withType(original, PeqFilterType.HIGH_PASS)
        val owner = requireNotNull(sessionOwner())
        val adapter = list().adapter as PeqBandAdapter
        val holder = requireNotNull(list().findViewHolderForAdapterPosition(0)) as PeqBandAdapter.Holder
        adapter.onViewDetachedFromWindow(holder)
        idle()
        assertSame(owner, sessionOwner())
        assertPreview(draft, listening = true)
        adapter.onViewAttachedToWindow(holder)
        idle()
        assertSame(owner, sessionOwner())
        assertTrue(editor.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        assertCardDraft(holder.itemView, PeqFilterType.HIGH_PASS, listening = true)
        adapter.notifyDataSetChanged()
        layout()
        assertSame(owner, sessionOwner())
        assertPreview(draft, listening = true)
        assertCardDraft(row(), PeqFilterType.HIGH_PASS, listening = true)
        assertSaved(original)
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertCleared(original)
    }

    @Test fun hiddenInlinePopupConfirmationCanHandItsAuditionToPreciseWithoutLosingTheOwner() {
        val original = launch(inline = false)
        val picker = popup()
        choose(picker, PeqFilterType.LOW_PASS)
        click(picker, R.id.peq_filter_monitor)
        val owner = requireNotNull(sessionOwner())
        click(picker, R.id.peq_filter_confirm)
        val confirmed = withType(original, PeqFilterType.LOW_PASS)
        val editor = precise()
        assertSame(owner, sessionOwner())
        assertTrue(editor.findViewById<View>(R.id.peq_band_monitor)!!.isSelected)
        assertEquals(PeqFilterType.LOW_PASS, editor.findViewById<PeqFilterSelector>(R.id.peq_band_type)!!.selectedType)
        choose(editor, PeqFilterType.NOTCH)
        list().adapter!!.notifyDataSetChanged()
        layout()
        assertSame(owner, sessionOwner())
        assertPreview(withType(confirmed, PeqFilterType.NOTCH), listening = true)
        assertSaved(confirmed)
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertCleared(confirmed)
    }

    @Test fun oneKeySettingsDoNotInheritAuditionOrPreviewTheirDraftAfterPreciseConfirmation() {
        val original = launch()
        val editor = precise()
        choose(editor, PeqFilterType.LOW_PASS)
        click(editor, R.id.peq_band_monitor)
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val confirmed = withType(original, PeqFilterType.LOW_PASS)
        assertPreview(confirmed, listening = true)
        view<View>(R.id.peq_parameters).performClick()
        idle()
        val batch = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(batch.isShowing)
        assertNull(batch.findViewById<View>(R.id.peq_band_monitor))
        assertNull(batch.findViewById<View>(R.id.peq_filter_monitor))
        choose(batch, PeqFilterType.NOTCH)
        batch.findViewById<EditText>(R.id.peq_batch_q_input)!!.setText("4")
        assertNull(PeqPreviewSession.current())
        assertEquals(confirmed, graph.requestedState)
        assertFalse(graph.isMarkerVisible)
        assertSaved(confirmed)
        batch.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertCleared(confirmed)
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
