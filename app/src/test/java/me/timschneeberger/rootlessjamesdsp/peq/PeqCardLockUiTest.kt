package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
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
import org.robolectric.shadows.ShadowToast
import java.time.Duration

/** Lock gestures and label shortcuts change protection only, including after holder reuse. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqCardLockUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val soundPrefs get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val lockPrefs get() = app.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE)
    private val locks get() = PeqBandLockStore(app)
    private val repository get() = PeqRepository(app)
    private var controller: ActivityController<HostActivity>? = null
    private var original = initial()
    private var soundBefore: Map<String, *> = emptyMap<String, Any>()
    private var downTime = 0L

    @Before fun clear() {
        soundPrefs.edit().clear().commit()
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
    private fun awaitConfirmation() = advance(ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
    private fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("peq") as PeqFragment
    private fun <T : View> view(id: Int): T = fragment().requireView().findViewById(id)
    private fun list() = view<RecyclerView>(R.id.peq_band_list)
    private fun adapter() = list().adapter as PeqBandAdapter

    private fun initial() = PeqState(listOf(
        PeqBand("a", type = PeqFilterType.PEAK, frequency = 137.5, gain = 2.25, q = 1.5),
        PeqBand("b", type = PeqFilterType.PEAK, frequency = 2750.75, gain = -3.75, q = 2.25,
            enabled = false, qualityDisplay = PeqQualityDisplay.BW)),
        showQSlider = true, showFrequencySlider = true)

    private fun launch(masterLocked: Boolean = false, fieldLocks: Boolean = false) {
        original = initial()
        assertTrue(repository.save(original))
        soundPrefs.edit().putBoolean(app.getString(R.string.key_geq_enable), true).commit()
        original.bands.forEach { band ->
            locks.markNew(band.id)
            locks.setBandLocked(band.id, masterLocked)
            PeqEditField.entries.forEach { locks.setFieldLocked(band.id, it, fieldLocks) }
        }
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout()
        soundBefore = soundPrefs.all.toMap()
    }

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

    private fun row(index: Int = 0): View {
        list().scrollToPosition(index)
        layout()
        return requireNotNull(list().findViewHolderForAdapterPosition(index)).itemView
    }

    private fun holder(index: Int = 0): PeqBandAdapter.Holder {
        row(index)
        return requireNotNull(list().findViewHolderForAdapterPosition(index)) as PeqBandAdapter.Holder
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

    private fun doubleTap(button: View) { tap(button); advance(80); tap(button) }

    private fun assertAudioAndParametersUnchanged() {
        assertEquals(original, fragment().editorState)
        assertEquals(original, repository.load())
        assertEquals(soundBefore, soundPrefs.all)
        assertTrue(soundPrefs.getBoolean(app.getString(R.string.key_geq_enable), false))
        assertNull(PeqPreviewSession.current())
    }

    private fun fieldState(id: String) = PeqEditField.entries.associateWith { locks.isFieldLocked(id, it) }

    @Test fun physicalSingleTapChangesOnlyTheMasterAfterItsConfirmationWindow() {
        launch(fieldLocks = true)
        val before = lockPrefs.all.toMap()
        val card = row()
        tap(card.findViewById(R.id.peq_band_lock))
        assertEquals(before, lockPrefs.all)
        awaitConfirmation()
        assertTrue(locks.isBandLocked("a"))
        assertEquals(before.toMutableMap().apply { put("a.band", true) }, lockPrefs.all)
        assertTrue(locks.isNew("a"))
        assertFalse(card.findViewById<View>(R.id.peq_band_slider).isEnabled)
        assertAudioAndParametersUnchanged()
        awaitConfirmation()
        assertTrue(locks.isBandLocked("a"))
    }

    @Test fun physicalDoubleTapUnlocksTheMasterAndAllFieldsByDefaultWithoutTouchingOtherCards() {
        launch(masterLocked = true, fieldLocks = true)
        val otherBefore = lockPrefs.all.filterKeys { it.startsWith("b.") }
        val card = row()
        doubleTap(card.findViewById(R.id.peq_band_lock))
        awaitConfirmation()
        assertFalse(locks.isBandLocked("a"))
        assertTrue(locks.isNew("a"))
        assertTrue(fieldState("a").values.none { it })
        assertEquals(otherBefore, lockPrefs.all.filterKeys { it.startsWith("b.") })
        assertTrue(card.findViewById<View>(R.id.peq_band_slider).isEnabled)
        assertTrue(card.findViewById<View>(R.id.peq_band_title).isEnabled)
        assertEquals(Color.GRAY, card.findViewById<MaterialButton>(R.id.peq_band_lock).iconTint!!.defaultColor)
        assertEquals(app.getString(R.string.peq_all_band_locks_unlocked), ShadowToast.getTextOfLatestToast())
        assertAudioAndParametersUnchanged()
    }

    @Test fun disablingTheMasterUnlockPreferenceMakesDoubleTapClearOnlyIndependentLocks() {
        assertTrue(PeqEditorPreferences(app).save(PeqEditorSettings(doubleTapUnlocksBand = false)))
        launch(masterLocked = true, fieldLocks = true)
        val before = lockPrefs.all.toMap()
        val card = row()
        doubleTap(card.findViewById(R.id.peq_band_lock))
        awaitConfirmation()
        assertTrue(locks.isBandLocked("a"))
        assertTrue(locks.isNew("a"))
        val expected = before.toMutableMap().apply {
            PeqEditField.entries.forEach { put("a.${it.name}", false) }
        }
        assertEquals(expected, lockPrefs.all)
        assertFalse(card.findViewById<View>(R.id.peq_band_slider).isEnabled)
        assertFalse(card.findViewById<View>(R.id.peq_band_title).isEnabled)
        assertEquals(Color.rgb(211, 47, 47), card.findViewById<MaterialButton>(R.id.peq_band_lock).iconTint!!.defaultColor)
        assertEquals(app.getString(R.string.peq_all_field_locks_unlocked_band_locked), ShadowToast.getTextOfLatestToast())
        assertAudioAndParametersUnchanged()
    }

    @Test fun physicalDoubleTapOnAnUnlockedMasterRestoresFieldEditingWithoutChangingItsPowerOrValues() {
        launch(fieldLocks = true)
        val before = lockPrefs.all.toMap()
        val card = row(1)
        doubleTap(card.findViewById(R.id.peq_band_lock))
        awaitConfirmation()
        val expected = before.toMutableMap().apply {
            PeqEditField.entries.forEach { put("b.${it.name}", false) }
        }
        assertEquals(expected, lockPrefs.all)
        assertFalse(locks.isBandLocked("b"))
        assertTrue(locks.isNew("b"))
        listOf(R.id.peq_band_slider, R.id.peq_q_slider, R.id.peq_frequency_slider).forEach {
            assertTrue(card.findViewById<View>(it).isEnabled)
        }
        assertEquals(Color.GRAY, card.findViewById<MaterialButton>(R.id.peq_band_lock).iconTint!!.defaultColor)
        assertEquals(Color.GRAY, card.findViewById<MaterialButton>(R.id.peq_band_power).iconTint!!.defaultColor)
        assertEquals(app.getString(R.string.peq_all_band_locks_unlocked), ShadowToast.getTextOfLatestToast())
        assertAudioAndParametersUnchanged()
    }

    @Test fun LabelsAndSmallLocksToggleTheSameFieldForBothQAndBandwidthCards() {
        launch()
        val mappings = listOf(
            Triple(R.id.peq_gain_label, R.id.peq_gain_lock, PeqEditField.GAIN),
            Triple(R.id.peq_q_label, R.id.peq_q_lock, PeqEditField.QUALITY),
            Triple(R.id.peq_frequency_label, R.id.peq_frequency_lock, PeqEditField.FREQUENCY))
        original.bands.forEachIndexed { index, band ->
            val card = row(index)
            assertEquals(app.getString(if (band.qualityDisplay == PeqQualityDisplay.BW)
                R.string.peq_card_bandwidth_label else R.string.peq_quality),
                card.findViewById<TextView>(R.id.peq_q_label).text.toString())
            mappings.forEach { (labelId, lockId, field) ->
                val before = lockPrefs.all.toMap()
                val label = card.findViewById<TextView>(labelId)
                val smallLock = card.findViewById<MaterialButton>(lockId)
                assertTrue(label.isEnabled)
                assertTrue(label.performClick())
                assertTrue(locks.isFieldLocked(band.id, field))
                assertEquals(before.toMutableMap().apply { put("${band.id}.${field.name}", true) }, lockPrefs.all)
                assertEquals(Color.rgb(211, 47, 47), smallLock.iconTint!!.defaultColor)
                assertTrue(label.isEnabled)
                smallLock.performClick()
                assertFalse(locks.isFieldLocked(band.id, field))
                assertEquals(before, lockPrefs.all)
                assertEquals(Color.GRAY, smallLock.iconTint!!.defaultColor)
                assertAudioAndParametersUnchanged()
            }
        }
    }

    @Test fun MasterAndSelectionProtectionDisableLabelsAndSmallLocksWithoutChangingProtection() {
        launch(masterLocked = true, fieldLocks = true)
        var card = row()
        val labelIds = listOf(R.id.peq_gain_label, R.id.peq_q_label, R.id.peq_frequency_label)
        val smallIds = listOf(R.id.peq_gain_lock, R.id.peq_q_lock, R.id.peq_frequency_lock)
        val lockedBefore = lockPrefs.all.toMap()
        labelIds.forEach {
            val label = card.findViewById<TextView>(it)
            assertFalse(label.isEnabled)
            assertEquals(Color.GRAY, label.currentTextColor)
            label.performClick()
        }
        smallIds.forEach { assertFalse(card.findViewById<View>(it).isEnabled); card.findViewById<View>(it).performClick() }
        assertEquals(lockedBefore, lockPrefs.all)

        card.findViewById<View>(R.id.peq_band_lock).performClick()
        assertFalse(locks.isBandLocked("a"))
        view<View>(R.id.peq_edit_selection).performClick()
        layout()
        card = row()
        val selectionBefore = lockPrefs.all.toMap()
        (labelIds + smallIds + R.id.peq_band_lock).forEach {
            assertFalse(card.findViewById<View>(it).isEnabled)
            card.findViewById<View>(it).performClick()
        }
        awaitConfirmation()
        assertEquals(selectionBefore, lockPrefs.all)
        assertAudioAndParametersUnchanged()
    }

    @Test fun EnteringSelectionAndLeavingTheScreenCancelPendingMasterTaps() {
        launch(fieldLocks = true)
        val before = lockPrefs.all.toMap()
        tap(row().findViewById(R.id.peq_band_lock))
        view<View>(R.id.peq_edit_selection).performClick()
        layout()
        awaitConfirmation()
        assertEquals(before, lockPrefs.all)
        view<View>(R.id.peq_selection_done).performClick()
        layout()
        tap(row().findViewById(R.id.peq_band_lock))
        controller!!.pause()
        awaitConfirmation()
        assertEquals(before, lockPrefs.all)
        assertAudioAndParametersUnchanged()
        controller!!.resume()
        layout()
        row().findViewById<View>(R.id.peq_band_lock).performClick()
        assertTrue(locks.isBandLocked("a"))
        assertAudioAndParametersUnchanged()
    }

    @Test fun BindingTheHolderToAnotherBandCancelsTheOldTapInsteadOfLockingEitherBand() {
        launch(fieldLocks = true)
        val before = lockPrefs.all.toMap()
        val holder = holder()
        tap(holder.itemView.findViewById(R.id.peq_band_lock))
        adapter().onBindViewHolder(holder, 1)
        awaitConfirmation()
        assertEquals(before, lockPrefs.all)
        assertAudioAndParametersUnchanged()
        // A fresh gesture on the rebound row must target its new stable identity only.
        holder.itemView.findViewById<View>(R.id.peq_band_lock).performClick()
        assertFalse(locks.isBandLocked("a"))
        assertTrue(locks.isBandLocked("b"))
        assertAudioAndParametersUnchanged()
    }

    @Test fun DetachedAndRecycledHoldersDiscardPendingTapsBeforeReuse() {
        launch(fieldLocks = true)
        val before = lockPrefs.all.toMap()
        val holder = holder()
        tap(holder.itemView.findViewById(R.id.peq_band_lock))
        adapter().onViewDetachedFromWindow(holder)
        awaitConfirmation()
        assertEquals(before, lockPrefs.all)
        adapter().onViewAttachedToWindow(holder)
        tap(holder.itemView.findViewById(R.id.peq_band_lock))
        adapter().onViewRecycled(holder)
        adapter().onBindViewHolder(holder, 1)
        adapter().onViewAttachedToWindow(holder)
        awaitConfirmation()
        assertEquals(before, lockPrefs.all)
        assertAudioAndParametersUnchanged()
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
