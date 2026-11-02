package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import com.google.android.material.materialswitch.MaterialSwitch
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fragment.PreferenceGroupFragment
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.peq.PeqFragment
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.Constants
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual home and editor power controls share one value without recursive preference writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w411dp-h891dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class HomeEqualizerSynchronizationTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = PreferenceCache.getPreferences(app, Constants.PREF_GEQ)
    private val powerKey get() = app.getString(R.string.key_geq_enable)
    private var controller: ActivityController<HostActivity>? = null

    @Before fun prepare() {
        listOf(Constants.PREF_APP, Constants.PREF_GEQ).forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        stopKoin()
        val preferences = Preferences(app)
        startKoin { modules(module { single { preferences.App() } }) }
    }

    @After fun cleanUp() {
        controller?.pause()?.stop()?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        stopKoin()
    }

    private fun layout() {
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller!!.get()
        val metrics = activity.resources.displayMetrics
        val width = (411 * metrics.density).toInt()
        val height = (891 * metrics.density).toInt()
        activity.findViewById<View>(android.R.id.content).apply {
            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            layout(0, 0, width, height)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun home() = controller!!.get().supportFragmentManager.findFragmentByTag("home") as PreferenceGroupFragment
    private fun homeSwitch(): MaterialSwitch = requireNotNull(home().listView.findViewHolderForAdapterPosition(0))
        .itemView.findViewById(R.id.switchWidget)
    private fun editorPower(): View = (controller!!.get().supportFragmentManager.findFragmentByTag("editor") as PeqFragment)
        .requireView().findViewById(R.id.peq_power)

    @Test fun editorAndHomePowerStayInSyncInBothDirectionsAndAfterRecreation() {
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout()
        val homeGroup = home().findPreference<SwitchPreferenceGroup>(powerKey)!!
        assertFalse(homeGroup.isChecked)
        assertFalse(homeSwitch().isChecked)
        assertFalse(editorPower().isSelected)
        var powerWrites = 0
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == powerKey) powerWrites++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            editorPower().performClick()
            layout()
            assertTrue(prefs.getBoolean(powerKey, false))
            assertTrue(homeGroup.isChecked)
            assertTrue(homeSwitch().isChecked)
            assertTrue(editorPower().isSelected)
            assertEquals("Refreshing the home switch must not write the value again", 1, powerWrites)

            homeSwitch().performClick()
            layout()
            assertFalse(prefs.getBoolean(powerKey, true))
            assertFalse(homeGroup.isChecked)
            assertFalse(editorPower().isSelected)
            assertEquals(2, powerWrites)

            editorPower().performClick()
            layout()
            assertTrue(homeSwitch().isChecked)
            assertEquals(3, powerWrites)
            controller!!.recreate()
            layout()
            assertTrue(homeSwitch().isChecked)
            assertTrue(editorPower().isSelected)
            assertEquals("Restoring views must not toggle or rewrite power", 3, powerWrites)
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            root.addView(FrameLayout(this).apply { id = HOME_ID }, LinearLayout.LayoutParams(-1, 120))
            root.addView(FrameLayout(this).apply { id = EDITOR_ID }, LinearLayout.LayoutParams(-1, 0, 1f))
            setContentView(root)
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .add(HOME_ID, PreferenceGroupFragment.newInstance(Constants.PREF_GEQ, R.xml.dsp_graphiceq_preferences), "home")
                .add(EDITOR_ID, PeqFragment(), "editor")
                .commitNow()
        }

        companion object {
            private const val HOME_ID = 120001
            private const val EDITOR_ID = 120002
        }
    }
}
