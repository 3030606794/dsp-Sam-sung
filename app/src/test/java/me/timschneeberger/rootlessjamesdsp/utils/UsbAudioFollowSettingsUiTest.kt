package me.timschneeberger.rootlessjamesdsp.utils

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.appcompat.widget.PopupMenu
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSwitchPreference
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import me.timschneeberger.rootlessjamesdsp.session.root.RootProcessingPolicy
import me.timschneeberger.rootlessjamesdsp.ui.HomeQuickActionsMenu
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h891dp-port-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class UsbAudioFollowSettingsUiTest {
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val preferences get()=app.getSharedPreferences(Constants.PREF_APP,Context.MODE_PRIVATE)
    private val controllers=mutableListOf<ActivityController<HostActivity>>()
    @Before fun prepare() {
        preferences.edit().clear().commit();stopKoin()
        val prefs=Preferences(app)
        startKoin {modules(module {single {prefs.App()};single {prefs.Var()}})}
    }
    @After fun close() {
        controllers.reversed().forEach {it.pause().stop().destroy()}
        shadowOf(Looper.getMainLooper()).idle();stopKoin()
        UsbAudioFollowStatus.publish(UsbSampleRateFollow.Status())
    }
    private fun launch(): HostActivity=Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        .also {controllers+=it}.get()
    private fun fragment(host: HostActivity)=host.supportFragmentManager.findFragmentByTag("settings") as XmlSettings
    private fun toggle(host: HostActivity, key: Int=R.string.key_audioformat_usb_rate_follow)=
        requireNotNull(fragment(host).findPreference<MaterialSwitchPreference>(app.getString(key)))
    @Suppress("RestrictedApi")
    private fun click(host: HostActivity, key: Int=R.string.key_audioformat_usb_rate_follow) {
        val list=fragment(host).listView
        val adapter=list.adapter as PreferenceGroupAdapter
        val position=adapter.getPreferenceAdapterPosition(app.getString(key))
        assertTrue(position>=0);list.scrollToPosition(position)
        val root=host.findViewById<View>(android.R.id.content)
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            root.measure(View.MeasureSpec.makeMeasureSpec(411,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(891,View.MeasureSpec.EXACTLY))
            root.layout(0,0,411,891)
        }
        assertTrue(requireNotNull(list.findViewHolderForAdapterPosition(position)).itemView.performClick())
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun globalHdEnhancedAndInformationEntrypointsAreVisibleAndUsable() {
        val host=launch()
        for (key in listOf(R.string.key_audioformat_processing, R.string.key_audioformat_enhanced_processing,
            R.string.key_audioformat_usb_rate_follow)) {
            assertTrue(toggle(host,key).isVisible)
            assertTrue(toggle(host,key).isEnabled)
        }
        assertTrue(fragment(host).findPreference<androidx.preference.Preference>(
            app.getString(R.string.key_audioformat_enhanced_processing_info))!!.isVisible)
        assertNull(fragment(host).findPreference<androidx.preference.Preference>("samsung_system_global_info"))
        assertEquals("全局模式（系统默认）", toggle(host,R.string.key_audioformat_processing).title)
    }
    @Test fun existingPlayerSelectionIsPreservedWithoutForcedMigrationOrEffectChanges() {
        preferences.edit().putBoolean(app.getString(R.string.key_audioformat_processing),false)
            .putBoolean(app.getString(R.string.key_audioformat_usb_rate_follow),true)
            .putBoolean(app.getString(R.string.key_audioformat_enhanced_processing),true)
            .putBoolean(app.getString(R.string.key_powered_on),false)
            .putBoolean("samsung_global_mode_default_v1",true)
            .putBoolean("samsung_system_global_only_v1",true).commit()
        val ir = app.getSharedPreferences(Constants.PREF_CONVOLVER, Context.MODE_PRIVATE)
        ir.edit().putString("convolver_file", "Convolver/user.irs").putBoolean("convolver_enable",true)
            .putInt("conv1_mix_tenths", 750).commit()
        val before = ir.all.toMap()
        launch()
        assertFalse(preferences.getBoolean(app.getString(R.string.key_audioformat_processing),true))
        assertTrue(preferences.getBoolean(app.getString(R.string.key_audioformat_enhanced_processing),false))
        assertTrue(preferences.getBoolean(app.getString(R.string.key_audioformat_usb_rate_follow),false))
        assertFalse(preferences.getBoolean(app.getString(R.string.key_powered_on),true))
        assertEquals(before, ir.all)
        assertTrue(RootProcessingPolicy.playerSessions(app))
    }
    @Test fun hdSelectsPositiveSessionsAndGlobalSelectsOnlyZeroWithoutChangingPower() {
        preferences.edit().putBoolean(app.getString(R.string.key_powered_on),false).commit()
        val host=launch()
        click(host)
        assertTrue(toggle(host).isChecked)
        assertTrue(toggle(host,R.string.key_audioformat_enhanced_processing).isChecked)
        assertFalse(toggle(host,R.string.key_audioformat_processing).isChecked)
        assertTrue(RootProcessingPolicy.playerSessions(app))
        assertFalse(RootProcessingPolicy.acceptsSession(RootProcessingPolicy.playerSessions(app),0))
        assertTrue(RootProcessingPolicy.acceptsSession(RootProcessingPolicy.playerSessions(app),353))
        click(host,R.string.key_audioformat_processing)
        assertTrue(toggle(host,R.string.key_audioformat_processing).isChecked)
        assertFalse(toggle(host).isChecked)
        assertFalse(toggle(host,R.string.key_audioformat_enhanced_processing).isChecked)
        assertFalse(RootProcessingPolicy.playerSessions(app))
        assertTrue(RootProcessingPolicy.acceptsSession(RootProcessingPolicy.playerSessions(app),0))
        assertFalse(RootProcessingPolicy.acceptsSession(RootProcessingPolicy.playerSessions(app),353))
        assertFalse(preferences.getBoolean(app.getString(R.string.key_powered_on),true))
    }
    @Test fun globalOffAndEnhancedOnSelectPlayerModeAndTurningEnhancedOffReturnsToGlobal() {
        val host=launch()
        click(host,R.string.key_audioformat_processing)
        assertTrue(toggle(host).isChecked)
        assertTrue(RootProcessingPolicy.playerSessions(app))
        click(host,R.string.key_audioformat_enhanced_processing)
        assertTrue(toggle(host,R.string.key_audioformat_processing).isChecked)
        assertFalse(RootProcessingPolicy.playerSessions(app))
        click(host,R.string.key_audioformat_enhanced_processing)
        assertFalse(toggle(host,R.string.key_audioformat_processing).isChecked)
        assertTrue(toggle(host).isChecked)
        assertTrue(RootProcessingPolicy.playerSessions(app))
    }
    @Test fun globalSelectionWinsConflictingPlayerFlagsAndAppListsWithoutAuthorizingPositiveSid() {
        preferences.edit().putBoolean(app.getString(R.string.key_audioformat_processing),true)
            .putBoolean(app.getString(R.string.key_audioformat_usb_rate_follow),true)
            .putBoolean(app.getString(R.string.key_audioformat_enhanced_processing),true)
            .putBoolean(ProcessingAppPolicy.ENABLED,true)
            .putStringSet(ProcessingAppPolicy.PACKAGES,setOf("com.neutroncode.mp")).commit()
        assertFalse(RootProcessingPolicy.playerSessions(app))
        assertTrue(RootProcessingPolicy.acceptsSession(RootProcessingPolicy.playerSessions(app),0))
        assertFalse(RootProcessingPolicy.acceptsSession(RootProcessingPolicy.playerSessions(app),353))
        launch();launch()
        assertFalse(RootProcessingPolicy.playerSessions(app))
        assertFalse(preferences.getBoolean(app.getString(R.string.key_audioformat_usb_rate_follow),true))
    }
    @Test fun homeMenuRestoresSpecifiedAndExcludedAppEntrypoints() {
        val host=launch()
        val actions = HomeQuickActionsMenu(host.findViewById(android.R.id.content), {}, {}, {}, {})
        try {
            actions.show()
            val popup = HomeQuickActionsMenu::class.java.getDeclaredField("popup").apply { isAccessible = true }
                .get(actions) as PopupMenu
            assertTrue(popup.menu.findItem(R.id.action_blocked_apps).isVisible)
            assertTrue(popup.menu.findItem(R.id.action_selected_apps).isVisible)
        } finally { actions.dismiss() }
    }
    class XmlSettings: PreferenceFragmentCompat() {
        override fun onCreatePreferences(state: Bundle?,rootKey: String?) {
            preferenceManager.sharedPreferencesName=Constants.PREF_APP
            setPreferencesFromResource(R.xml.app_audio_format_preferences,rootKey)
            AudioProcessingModeSettings(requireContext(),requireNotNull(preferenceManager.sharedPreferences)) {}.bind(
                requireNotNull(findPreference(getString(R.string.key_audioformat_processing))),
                requireNotNull(findPreference(getString(R.string.key_audioformat_enhanced_processing))),
                requireNotNull(findPreference(getString(R.string.key_audioformat_usb_rate_follow)))
            )
        }
    }
    class HostActivity: FragmentActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP);super.onCreate(state)
            setContentView(FrameLayout(this).apply {id=0x6f71})
            if(state==null)supportFragmentManager.beginTransaction().add(0x6f71,XmlSettings(),"settings").commitNow()
        }
    }
}
