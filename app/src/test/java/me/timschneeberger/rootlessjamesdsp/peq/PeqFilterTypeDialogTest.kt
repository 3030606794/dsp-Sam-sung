package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
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
        dialog.findViewById<MaterialButton>(PeqFilterTypeDialog.buttonId(type))!!

    @Test fun narrowScreenKeepsSevenTypesInOneScrollableCapsuleAndFooterInOneRowAtBottom() {
        val picker = create(hint = "已锁定的频段不会修改")
        val types = picker.findViewById<MaterialButtonToggleGroup>(R.id.peq_filter_types)!!
        val scroll = picker.findViewById<HorizontalScrollView>(R.id.peq_filter_scroll)!!
        assertEquals(7, types.childCount)
        assertEquals(1, types.checkedButtonIds.size)
        assertTrue(types.width > scroll.width)
        PeqFilterType.entries.map { button(picker, it) }.zipWithNext().forEach { (left, right) ->
            assertEquals(left.top, right.top)
            assertTrue(right.left > left.left)
        }
        val footer = listOf(R.id.peq_filter_cancel, R.id.peq_filter_monitor, R.id.peq_filter_confirm)
            .map { picker.findViewById<View>(it)!! }
        assertTrue(footer.all { it.top == footer.first().top && it.bottom == footer.first().bottom })
        assertTrue(footer.maxOf { it.width } - footer.minOf { it.width } <= 1)
        val root = picker.findViewById<View>(R.id.peq_filter_dialog_root)!!
        assertTrue(root.height / root.resources.displayMetrics.density < 260f)
        assertEquals(Gravity.BOTTOM, picker.window!!.attributes.gravity)
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
        val offColor = peak.backgroundTintList!!.defaultColor
        assertTrue(selected.isEmpty())
        assertTrue(monitoring.isEmpty())
        val monitor = picker.findViewById<MaterialButton>(R.id.peq_filter_monitor)!!
        assertFalse(monitor.isSelected)
        monitor.performClick()
        assertTrue(monitor.isSelected)
        assertNotEquals(offColor, peak.backgroundTintList!!.defaultColor)
        assertTrue(ColorUtils.calculateContrast(peak.currentTextColor, peak.backgroundTintList!!.defaultColor) >= 4.5)
        button(picker, PeqFilterType.HIGH_PASS).performClick()
        assertEquals(listOf(PeqFilterType.HIGH_PASS), selected)
        assertEquals(listOf(true), monitoring)
        assertTrue(picker.isShowing)
        assertFalse(confirmed)
        val active = button(picker, PeqFilterType.HIGH_PASS)
        val green = active.backgroundTintList!!.defaultColor
        PeqFilterTypeDialog.setMonitoring(picker, false)
        assertFalse(monitor.isSelected)
        assertNotEquals(green, active.backgroundTintList!!.defaultColor)
        assertEquals(listOf(true), monitoring)
        assertEquals(offColor, active.backgroundTintList!!.defaultColor)
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

    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
        }
    }
}
