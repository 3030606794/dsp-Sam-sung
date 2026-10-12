package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import me.timschneeberger.rootlessjamesdsp.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqResizeDialogUiTest {
    private lateinit var controller: ActivityController<HostActivity>
    private val dialogs = mutableListOf<AlertDialog>()

    @Before fun prepare() {
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
    }

    @After fun close() {
        dialogs.forEach { it.dismiss() }
        controller.pause().stop().destroy()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun show(current: Int, target: Int, confirm: (PeqResizeEdge, Boolean) -> Unit): AlertDialog {
        val context = controller.get()
        return PeqResizeDialog.create(context, LayoutInflater.from(context), current, target, confirm).also {
            dialogs += it
            it.show()
            idle()
        }
    }

    private fun AlertDialog.selectFront() {
        findViewById<MaterialRadioButton>(R.id.peq_resize_front)!!.performClick()
        idle()
    }

    private fun AlertDialog.redistribute() = findViewById<MaterialSwitch>(R.id.peq_resize_redistribute)!!

    private fun AlertDialog.confirm() {
        getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
    }

    @Test fun increaseSummaryShowsActualDeltaAndDefaultsToBackWithoutRedistribution() {
        val selections = mutableListOf<Pair<PeqResizeEdge, Boolean>>()
        val dialog = show(11, 47) { edge, redistribute -> selections += edge to redistribute }
        assertTrue(selections.isEmpty())
        assertEquals(controller.get().getString(R.string.peq_resize_increase_summary, 11, 47, 36),
            dialog.findViewById<TextView>(R.id.peq_resize_summary)!!.text.toString())
        assertEquals(R.id.peq_resize_back, dialog.findViewById<RadioGroup>(R.id.peq_resize_edges)!!.checkedRadioButtonId)
        assertFalse(dialog.redistribute().isChecked)
        assertEquals(controller.get().getString(R.string.peq_resize_preserve_add_summary),
            dialog.findViewById<TextView>(R.id.peq_resize_frequency_summary)!!.text.toString())
        dialog.confirm()
        assertEquals(listOf(PeqResizeEdge.BACK to false), selections)
    }

    @Test fun choosingFrontChangesSummaryAndPrependsBlankBandsWithoutMovingExistingFrequencies() {
        val original = PeqModel.defaultState(3).let { state -> state.copy(
            bands = state.bands.mapIndexed { index, band -> band.copy(frequency = 517.0 + index * 137.0, gain = index.toDouble()) }) }
        var current = original
        val dialog = show(3, 5) { edge, redistribute -> current = PeqModel.resize(current, 5, edge, redistribute) }
        dialog.selectFront()
        assertEquals(controller.get().getString(R.string.peq_resize_add_front_summary, 2),
            dialog.findViewById<TextView>(R.id.peq_resize_position_summary)!!.text.toString())
        assertEquals(original, current)
        dialog.confirm()
        assertEquals(original.bands, current.bands.drop(2))
        current.bands.take(2).forEach {
            assertEquals(0.0, it.frequency, 0.0)
            assertEquals(0.0, it.gain, 0.0)
            assertEquals(0.5, it.q, 0.0)
            assertEquals(PeqFilterType.PEAK, it.type)
        }
    }

    @Test fun reducingUsesDeletionChoicesAndRetainsTheRequestedSide() {
        val original = PeqModel.defaultState(5)
        var fromBack = original
        val backDialog = show(5, 2) { edge, redistribute -> fromBack = PeqModel.resize(original, 2, edge, redistribute) }
        assertEquals(controller.get().getString(R.string.peq_resize_decrease_summary, 5, 2, 3),
            backDialog.findViewById<TextView>(R.id.peq_resize_summary)!!.text.toString())
        assertEquals(controller.get().getString(R.string.peq_resize_remove_back),
            backDialog.findViewById<MaterialRadioButton>(R.id.peq_resize_back)!!.text.toString())
        backDialog.confirm()
        assertEquals(original.bands.take(2), fromBack.bands)

        var fromFront = original
        val frontDialog = show(5, 2) { edge, redistribute -> fromFront = PeqModel.resize(original, 2, edge, redistribute) }
        frontDialog.selectFront()
        assertEquals(controller.get().getString(R.string.peq_resize_remove_front_summary, 3),
            frontDialog.findViewById<TextView>(R.id.peq_resize_position_summary)!!.text.toString())
        frontDialog.confirm()
        assertEquals(original.bands.takeLast(2), fromFront.bands)
    }

    @Test fun redistributionIsExplicitAndSwitchingItOffRestoresThePreservationChoice() {
        val original = PeqModel.defaultState(2).let { state -> state.copy(
            bands = state.bands.mapIndexed { index, band -> band.copy(frequency = 777.0 + index * 111.0,
                q = 2.125, gain = -3.25) }) }
        var current = original
        val dialog = show(2, 4) { edge, redistribute -> current = PeqModel.resize(current, 4, edge, redistribute) }
        dialog.redistribute().performClick()
        assertEquals(controller.get().getString(R.string.peq_resize_redistribute_summary, 4),
            dialog.findViewById<TextView>(R.id.peq_resize_frequency_summary)!!.text.toString())
        dialog.redistribute().performClick()
        assertEquals(controller.get().getString(R.string.peq_resize_preserve_add_summary),
            dialog.findViewById<TextView>(R.id.peq_resize_frequency_summary)!!.text.toString())
        dialog.redistribute().performClick()
        assertEquals(original, current)
        dialog.confirm()
        assertEquals(20.0, current.bands.first().frequency, 0.0)
        assertEquals(20000.0, current.bands.last().frequency, 0.001)
        assertEquals(original.bands.first().q, current.bands.first().q, 0.0)
        assertEquals(original.bands.first().gain, current.bands.first().gain, 0.0)
        assertEquals(0.5, current.bands.last().q, 0.0)
    }

    @Test fun cancelAndOutsideDismissDoNotConfirmAndEachOpeningStartsWithSafeDefaults() {
        var calls = 0
        val first = show(10, 6) { _, _ -> calls++ }
        first.selectFront()
        first.redistribute().performClick()
        first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(0, calls)
        val second = show(10, 6) { _, _ -> calls++ }
        assertEquals(R.id.peq_resize_back, second.findViewById<RadioGroup>(R.id.peq_resize_edges)!!.checkedRadioButtonId)
        assertFalse(second.redistribute().isChecked)
        second.cancel()
        idle()
        assertEquals(0, calls)
    }

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this))
        }
    }
}
