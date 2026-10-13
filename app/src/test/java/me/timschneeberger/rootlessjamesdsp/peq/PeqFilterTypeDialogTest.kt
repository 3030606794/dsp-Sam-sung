package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import me.timschneeberger.rootlessjamesdsp.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h720dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqFilterTypeDialogTest {
    private val host = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
    private var dialog: AlertDialog? = null

    @After fun close() { dialog?.dismiss(); host.pause().stop().destroy(); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun create(initial: PeqFilterType? = PeqFilterType.PEAK,
                       selected: (PeqFilterType) -> Unit = {}, monitor: (Boolean) -> Unit = {},
                       confirm: (PeqFilterType) -> Boolean = { true }, dismiss: () -> Unit = {},
                       hint: String? = null): AlertDialog = PeqFilterTypeDialog.create(host.get(),
        "滤波器类型", initial, selected, monitor, confirm, dismiss, hint).also {
        dialog = it
        it.show()
        idle()
        val root = it.findViewById<View>(R.id.peq_filter_dialog_root)!!
        val width = (272 * root.resources.displayMetrics.density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, width, root.measuredHeight)
        idle()
    }
    private fun button(dialog: AlertDialog, type: PeqFilterType) =
        dialog.findViewById<ViewGroup>(PeqFilterTypeDialog.buttonId(type))!!
    private fun background(view: View) = (view.background as ColorDrawable).color
    private fun label(view: ViewGroup) = view.getChildAt(0) as TextView

    @Test fun narrowScreenShowsSevenEqualChoicesWithUnscaledLabelsAndResponseIconsInCenteredDialog() {
        val picker = create(hint = "已锁定的频段不会修改")
        val types = picker.findViewById<PeqFilterSelector>(R.id.peq_filter_types)!!
        assertEquals(7, types.childCount)
        assertEquals(PeqFilterType.PEAK, types.selectedType)
        val choices = PeqFilterType.entries.map { button(picker, it) }
        assertTrue(choices.maxOf { it.width } - choices.minOf { it.width } <= 1)
        assertTrue(choices.all { it.left >= 0 && it.right <= types.width })
        choices.forEach {
            val label = label(it)
            val icon = it.getChildAt(1) as ImageView
            assertEquals(14f, label.textSize / label.resources.displayMetrics.scaledDensity, 0.01f)
            assertTrue(label.paint.measureText(label.text.toString()) <= label.width + 1)
            assertNotNull(icon.drawable)
            assertTrue(icon.top >= label.bottom)
        }
        choices.zipWithNext().forEach { (left, right) ->
            assertEquals(left.top, right.top)
            assertEquals(left.right, right.left)
        }
        val footer = listOf(R.id.peq_filter_cancel, R.id.peq_filter_monitor, R.id.peq_filter_confirm)
            .map { picker.findViewById<View>(it)!! }
        assertTrue(footer.all { it.top == footer.first().top && it.bottom == footer.first().bottom })
        assertTrue(footer.maxOf { it.width } - footer.minOf { it.width } <= 1)
        val root = picker.findViewById<View>(R.id.peq_filter_dialog_root)!!
        val footerRow = picker.findViewById<View>(R.id.peq_filter_footer)!!
        // Height follows the labels, response icons and optional wrapping protection hint.
        // A fixed cap would demand clipping content as typography or the hint grows.
        assertTrue(footerRow.bottom <= root.height - root.paddingBottom)
        assertEquals(root.paddingBottom, root.height - footerRow.bottom)
        assertEquals(Gravity.CENTER, picker.window!!.attributes.gravity)
        assertEquals(0, picker.window!!.attributes.windowAnimations)
        assertEquals(0, picker.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        assertEquals(Gravity.CENTER, picker.findViewById<TextView>(R.id.peq_filter_dialog_title)!!.gravity)
        assertEquals(View.VISIBLE, picker.findViewById<View>(R.id.peq_filter_lock_hint)!!.visibility)
    }

    @Test fun monitoringChangesSelectedCapsuleColorAndCallbacksWithoutCommittingOrClosing() {
        val selected = mutableListOf<PeqFilterType>()
        val monitoring = mutableListOf<Boolean>()
        var confirmed = false
        val picker = create(selected = selected::add, monitor = monitoring::add,
            confirm = { confirmed = true; true })
        val peak = button(picker, PeqFilterType.PEAK)
        val offColor = background(peak)
        assertTrue(selected.isEmpty())
        assertTrue(monitoring.isEmpty())
        val monitor = picker.findViewById<MaterialButton>(R.id.peq_filter_monitor)!!
        assertFalse(monitor.isSelected)
        monitor.performClick()
        assertTrue(monitor.isSelected)
        assertEquals("Committed filter stays purple while listening", offColor, background(peak))
        assertEquals(android.graphics.Color.rgb(46, 125, 50), monitor.backgroundTintList!!.defaultColor)
        button(picker, PeqFilterType.HIGH_PASS).performClick()
        assertEquals(listOf(PeqFilterType.HIGH_PASS), selected)
        assertEquals(listOf(true), monitoring)
        assertTrue(picker.isShowing)
        assertFalse(confirmed)
        val active = button(picker, PeqFilterType.HIGH_PASS)
        val green = background(active)
        assertEquals(android.graphics.Color.rgb(46, 125, 50), green)
        assertTrue(ColorUtils.calculateContrast(label(active).currentTextColor, green) >= 4.5)
        assertEquals(offColor, background(peak))
        PeqFilterTypeDialog.setMonitoring(picker, false)
        assertFalse(monitor.isSelected)
        assertTrue(active.background is GradientDrawable)
        assertEquals(android.graphics.Color.TRANSPARENT, monitor.backgroundTintList!!.defaultColor)
        assertEquals(listOf(true), monitoring)
        assertEquals(offColor, background(peak))
        val types = picker.findViewById<PeqFilterSelector>(R.id.peq_filter_types)!!
        assertEquals(PeqFilterType.PEAK, types.committedType)
        types.committedType = PeqFilterType.HIGH_PASS
        assertEquals(offColor, background(active))
        assertEquals(android.graphics.Color.TRANSPARENT, background(peak))
    }

    @Test fun mixedTypeWaitsForSelectionAndConfirmationFailureKeepsDraftVisible() {
        var accept = false
        var saved: PeqFilterType? = null
        var closed = 0
        val picker = create(initial = null, confirm = { saved = it; accept }, dismiss = { closed++ })
        val confirm = picker.findViewById<View>(R.id.peq_filter_confirm)!!
        assertFalse(confirm.isEnabled)
        assertEquals(View.GONE, picker.findViewById<View>(R.id.peq_filter_lock_hint)!!.visibility)
        button(picker, PeqFilterType.NOTCH).performClick()
        assertTrue(confirm.isEnabled)
        confirm.performClick()
        assertEquals(PeqFilterType.NOTCH, saved)
        assertTrue(picker.isShowing)
        assertEquals(0, closed)
        accept = true
        confirm.performClick()
        idle()
        assertFalse(picker.isShowing)
        assertEquals(1, closed)
    }

    @Test fun cancelRequestsHostCleanupWithoutConfirmingAndEveryNewDialogStartsMonitoringOff() {
        var closed = 0
        val picker = create(confirm = { fail("Cancel must not commit"); false }, dismiss = { closed++ })
        picker.findViewById<View>(R.id.peq_filter_monitor)!!.performClick()
        picker.findViewById<View>(R.id.peq_filter_cancel)!!.performClick()
        idle()
        assertEquals(1, closed)
        assertFalse(picker.isShowing)
        assertFalse(create().findViewById<View>(R.id.peq_filter_monitor)!!.isSelected)
    }

    @Test fun reusableSelectorBindsSilentlyAndDisablingItBlocksEveryChild() {
        val picker = create()
        val types = picker.findViewById<PeqFilterSelector>(R.id.peq_filter_types)!!
        val changes = mutableListOf<PeqFilterType>()
        types.onTypeSelected = changes::add
        types.selectedType = PeqFilterType.LOW_SHELF
        types.monitoring = true
        assertTrue(changes.isEmpty())
        assertTrue(button(picker, PeqFilterType.LOW_SHELF).isSelected)
        types.isEnabled = false
        PeqFilterType.entries.forEach { type ->
            val option = button(picker, type)
            assertFalse(option.isEnabled)
            assertFalse(label(option).isEnabled)
            assertFalse(option.getChildAt(1).isEnabled)
            option.performClick()
        }
        assertTrue(changes.isEmpty())
        assertEquals(PeqFilterType.LOW_SHELF, types.selectedType)
        types.isEnabled = true
        button(picker, PeqFilterType.BAND_PASS).performClick()
        assertEquals(listOf(PeqFilterType.BAND_PASS), changes)
        types.setSelection(PeqFilterType.NOTCH, notify = true)
        assertEquals(listOf(PeqFilterType.BAND_PASS, PeqFilterType.NOTCH), changes)
        types.setSelection(null)
        assertNull(types.selectedType)
        assertTrue(PeqFilterType.entries.none { button(picker, it).isSelected })
    }

    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
        }
    }
}
