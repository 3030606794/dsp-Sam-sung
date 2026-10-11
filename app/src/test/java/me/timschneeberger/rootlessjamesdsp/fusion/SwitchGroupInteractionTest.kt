package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.materialswitch.MaterialSwitch
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SwitchGroupInteractionTest {
    private var controller: ActivityController<Host>? = null
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    private val enableKey get() = app.getString(R.string.key_bass_enable)

    @Before fun clear() { prefs.edit().clear().commit() }
    @After fun destroy() { controller?.pause()?.stop()?.destroy(); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun layout() {
        idle()
        val root = controller!!.get().findViewById<View>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 1920)
        idle()
    }
    private fun launch() {
        controller = Robolectric.buildActivity(Host::class.java).setup().visible()
        layout()
    }
    private fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("native") as NativeGroup
    private fun group() = fragment().findPreference<SwitchPreferenceGroup>(enableKey)!!
    private fun header(): View = fragment().listView.findViewHolderForAdapterPosition(0)!!.itemView

    @Test fun realHeaderExpandsWithoutEnablingAndSwitchNeverChangesExpansion() {
        launch()
        assertFalse(group().isExpanded)
        header().performClick()
        layout()
        assertTrue(group().isExpanded)
        assertTrue(group().getPreference(0).isVisible)
        assertTrue(group().getPreference(0).isEnabled)
        assertFalse(prefs.getBoolean(enableKey, false))

        header().findViewById<MaterialSwitch>(R.id.switchWidget).performClick()
        layout()
        assertTrue(prefs.getBoolean(enableKey, false))
        assertTrue(group().isExpanded)

        header().performClick()
        layout()
        assertFalse(group().isExpanded)
        assertFalse(group().getPreference(0).isVisible)
        assertTrue(prefs.getBoolean(enableKey, false))

        header().findViewById<MaterialSwitch>(R.id.switchWidget).performClick()
        layout()
        assertFalse(prefs.getBoolean(enableKey, true))
        assertFalse(group().isExpanded)
    }

    @Test fun enabledEffectStartsCollapsedAndRotationPreservesPresentationSeparately() {
        prefs.edit().putBoolean(enableKey, true).commit()
        launch()
        assertTrue(group().isChecked)
        assertFalse(group().isExpanded)
        header().performClick()
        layout()
        controller!!.recreate()
        layout()
        assertTrue(group().isChecked)
        assertTrue(group().isExpanded)
        assertTrue(group().getPreference(0).isVisible)
        assertTrue(prefs.getBoolean(enableKey, false))
    }

    class NativeGroup : PreferenceFragmentCompat() {
        override fun onCreatePreferences(state: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = STORE
            addPreferencesFromResource(R.xml.dsp_bass_preferences)
        }
    }
    class Host : FragmentActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(state)
            setContentView(FrameLayout(this).apply { id = 0x3040 })
            if (state == null) supportFragmentManager.beginTransaction()
                .add(0x3040, NativeGroup(), "native").commitNow()
        }
    }
    companion object { const val STORE = "native_group_interaction" }
}
