package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.EqualizerActivity
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class EqualizerInterpolationUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_EQ, Context.MODE_PRIVATE)
    private val typeKey get() = app.getString(R.string.key_eq_filter_type)
    private val interpolationKey get() = app.getString(R.string.key_eq_interpolation)
    private var controller: ActivityController<EqualizerActivity>? = null
    private var updates = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { updates++ }
    }

    @Before fun setup() {
        stopKoin()
        listOf(Constants.PREF_APP, Constants.PREF_VAR, Constants.PREF_EQ, Constants.PREF_GEQ).forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        val preferences = Preferences(app)
        startKoin { modules(module { single { preferences.App() }; single { preferences.Var() } }) }
        app.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PREFERENCES_UPDATED))
    }

    @After fun cleanup() {
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
        controller?.pause()?.stop()?.destroy()
        idle()
        app.unregisterLocalReceiver(receiver)
        stopKoin()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun launch(): EqualizerActivity {
        controller = Robolectric.buildActivity(EqualizerActivity::class.java).setup().visible()
        idle()
        return controller!!.get()
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun button(activity: EqualizerActivity, title: Int): MaterialButton =
        descendants(activity.window.decorView).filterIsInstance<MaterialButton>()
            .single { it.text.startsWith(app.getString(title) + "\n") }

    private fun choose(activity: EqualizerActivity, title: Int, index: Int, selected: Int? = null) {
        val target = button(activity, title)
        assertTrue("The setting must remain enabled after changing filter type", target.isEnabled)
        assertTrue(target.performClick())
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val list = dialog.listView
        selected?.let { assertEquals(it, list.checkedItemPosition) }
        assertTrue(list.performItemClick(list.adapter.getView(index, null, list), index, list.adapter.getItemId(index)))
        idle()
        assertFalse(dialog.isShowing)
    }

    private fun hint(activity: EqualizerActivity) = descendants(activity.window.decorView)
        .filterIsInstance<TextView>().single { it.text == app.getString(R.string.eq_interpolator_fir_only_hint) }

    @Test fun bothInterpolatorsCanBeChosenForEveryFilterWithoutChangingGainsOrPeq() {
        val bands = EqPreview.encode(DoubleArray(15) { (it - 7) * 0.5 })
        prefs.edit().putString(app.getString(R.string.key_eq_bands), bands).commit()
        val peq = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
        peq.edit().putString("marker", "preserve PEQ").commit()
        val beforePeq = peq.all.toMap()
        val activity = launch()
        val types = app.resources.getStringArray(R.array.eq_filter_types_values)
        val modes = app.resources.getStringArray(R.array.eq_interpolators_values)
        types.forEachIndexed { typeIndex, type ->
            choose(activity, R.string.eq_filter_type, typeIndex)
            modes.forEachIndexed { modeIndex, mode ->
                val beforeUpdates = updates
                choose(activity, R.string.eq_interpolator, modeIndex)
                assertEquals(type, prefs.getString(typeKey, ""))
                assertEquals(mode, prefs.getString(interpolationKey, ""))
                assertEquals(bands, prefs.getString(app.getString(R.string.key_eq_bands), ""))
                assertEquals(beforePeq, peq.all)
                assertEquals(beforeUpdates + 1, updates)
                assertEquals(if (typeIndex == 0) View.GONE else View.VISIBLE, hint(activity).visibility)
            }
        }
    }

    @Test fun iirSelectionIsRetainedWhenSwitchingBackToFirAndRecreating() {
        val activity = launch()
        choose(activity, R.string.eq_filter_type, 5)
        choose(activity, R.string.eq_interpolator, 1)
        choose(activity, R.string.eq_filter_type, 0, selected = 5)
        assertEquals(View.GONE, hint(activity).visibility)
        assertTrue(button(activity, R.string.eq_interpolator).text.endsWith(
            app.resources.getStringArray(R.array.eq_interpolators)[1]))
        controller!!.recreate()
        idle()
        val recreated = controller!!.get()
        assertEquals("0", prefs.getString(typeKey, ""))
        assertEquals("1", prefs.getString(interpolationKey, ""))
        choose(recreated, R.string.eq_interpolator, 0, selected = 1)
        assertEquals("0", prefs.getString(interpolationKey, ""))
    }

    @Test fun openingAndCancellingInterpolatorInIirKeepsSavedSettings() {
        prefs.edit().putString(typeKey, "3").putString(interpolationKey, "1").commit()
        val activity = launch()
        val before = prefs.all.toMap()
        val beforeUpdates = updates
        button(activity, R.string.eq_interpolator).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(1, dialog.listView.checkedItemPosition)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(before, prefs.all)
        assertEquals(beforeUpdates, updates)
        assertTrue(button(activity, R.string.eq_interpolator).isEnabled)
    }
}
