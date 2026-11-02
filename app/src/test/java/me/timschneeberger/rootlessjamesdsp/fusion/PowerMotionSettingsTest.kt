package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.preference.ListPreference
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.appcompat.app.AlertDialog
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fragment.settings.SettingsAppearanceFragment
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSwitchPreference
import me.timschneeberger.rootlessjamesdsp.ui.power.PowerMotionConfig
import me.timschneeberger.rootlessjamesdsp.ui.power.PowerMotionSettings
import me.timschneeberger.rootlessjamesdsp.ui.power.PowerMotionStyle
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Actual appearance preferences, independent of native audio and service startup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    qualifiers = "zh-rCN-w411dp-h891dp-port-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PowerMotionSettingsTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = PowerMotionSettings.preferences(app)
    private val controllers = mutableListOf<ActivityController<AppearanceHost>>()

    @Before fun clearPreferences() {
        prefs.edit().clear().commit()
        AppearanceHost.previewFontScale = 1f
        stopKoin()
        val storage = Preferences(app)
        startKoin { modules(module { single { storage.App() }; single { storage.Var() } }) }
    }

    @After fun destroyHosts() {
        controllers.reversed().forEach { it.pause().stop().destroy() }
        shadowOf(Looper.getMainLooper()).idle()
        AppearanceHost.previewFontScale = 1f
        stopKoin()
    }

    @Test fun aFreshProfileUsesWaveAndReadingDefaultsDoesNotWriteAudioOrAppearanceSettings() {
        assertEquals(PowerMotionConfig(true, PowerMotionStyle.WAVE), PowerMotionSettings.read(app))
        assertTrue("A read must not trigger preference listeners or a driver sync", prefs.all.isEmpty())
        for (key in listOf(null, "future-style", "ignition", "neon", "fluid")) {
            assertEquals(PowerMotionStyle.WAVE, PowerMotionStyle.fromKey(key))
        }
    }

    @Test fun eachSavedStyleSurvivesReopeningAndDisablingAnimationPreservesTheStyleAndPowerState() {
        val powerKey = app.getString(R.string.key_powered_on)
        prefs.edit().putBoolean(powerKey, true).commit()
        for (style in PowerMotionStyle.entries) {
            prefs.edit().putBoolean(PowerMotionSettings.KEY_ENABLED, false)
                .putString(PowerMotionSettings.KEY_STYLE, style.key).commit()
            assertEquals(PowerMotionConfig(false, style), PowerMotionSettings.read(app))
            val host = launch()
            val fragment = appearance(host)
            assertFalse(requireNotNull(fragment.findPreference<MaterialSwitchPreference>(PowerMotionSettings.KEY_ENABLED)).isChecked)
            val selector = requireNotNull(fragment.findPreference<ListPreference>(PowerMotionSettings.KEY_STYLE))
            assertEquals(style.key, selector.value)
            assertFalse(selector.isEnabled)
            assertTrue(prefs.getBoolean(powerKey, false))
        }
    }

    @Test fun theRealAppearanceSwitchDisablesOnlyTheStyleSelectorAndKeepsItsSavedChoice() {
        prefs.edit().putString(PowerMotionSettings.KEY_STYLE, PowerMotionStyle.DOME.key).commit()
        val host = launch()
        val fragment = appearance(host)
        val toggle = requireNotNull(fragment.findPreference<MaterialSwitchPreference>(PowerMotionSettings.KEY_ENABLED))
        val selector = requireNotNull(fragment.findPreference<ListPreference>(PowerMotionSettings.KEY_STYLE))
        assertTrue(toggle.isChecked)
        assertTrue(selector.isEnabled)
        assertEquals(PowerMotionStyle.entries.map { it.key }, selector.entryValues.map { it.toString() })
        assertFalse(selector.summary.isNullOrBlank())

        clickEnabled(host)
        assertFalse(toggle.isChecked)
        assertFalse(selector.isEnabled)
        assertEquals(PowerMotionConfig(false, PowerMotionStyle.DOME), PowerMotionSettings.read(app))
        clickEnabled(host)
        assertTrue(toggle.isChecked)
        assertTrue(selector.isEnabled)
        assertEquals(PowerMotionStyle.DOME.key, selector.value)
    }

    @Test fun malformedPresentationValuesRecoverWithoutChangingUnrelatedAudioPreferences() {
        val powerKey = app.getString(R.string.key_powered_on)
        prefs.edit().putString(PowerMotionSettings.KEY_ENABLED, "corrupt")
            .putInt(PowerMotionSettings.KEY_STYLE, 42)
            .putBoolean(powerKey, false).putFloat("unrelated_audio_gain", -7.5f).commit()
        val before = prefs.all.toMap()
        assertEquals(PowerMotionConfig(), PowerMotionSettings.read(app))
        assertEquals("Safe reads must not silently mutate persisted settings", before, prefs.all)

        val host = launch()
        assertTrue(requireNotNull(appearance(host).findPreference<MaterialSwitchPreference>(PowerMotionSettings.KEY_ENABLED)).isChecked)
        assertEquals(true, prefs.all[PowerMotionSettings.KEY_ENABLED])
        assertEquals(PowerMotionStyle.WAVE.key, prefs.all[PowerMotionSettings.KEY_STYLE])
        assertEquals(false, prefs.all[powerKey])
        assertEquals(-7.5f, prefs.getFloat("unrelated_audio_gain", 0f), 0f)
    }

    @Test fun actualAppearancePageAndAllSixStyleChoicesRemainReadableAtSmallAndLargeFonts() {
        for (scale in listOf(.8f, 1.5f)) {
            AppearanceHost.previewFontScale = scale
            val host = launch()
            val fragment = appearance(host)
            val list = fragment.listView
            val adapter = list.adapter as PreferenceGroupAdapter
            val enabledPosition = adapter.getPreferenceAdapterPosition(PowerMotionSettings.KEY_ENABLED)
            val stylePosition = adapter.getPreferenceAdapterPosition(PowerMotionSettings.KEY_STYLE)
            assertTrue(enabledPosition >= 0 && stylePosition > enabledPosition)
            (list.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset((enabledPosition - 1).coerceAtLeast(0), 0)
            layout(host)
            for (position in listOf(enabledPosition, stylePosition)) {
                val row = requireNotNull(list.findViewHolderForAdapterPosition(position)) {
                    "Appearance row$position was not laid out at fontScale$scale; viewport=${list.height}"
                }.itemView
                assertReadable(requireNotNull(row.findViewById(android.R.id.title)))
            }
            savePreview(host.window.decorView, "appearance-${(scale * 100).toInt()}")

            assertTrue(requireNotNull(list.findViewHolderForAdapterPosition(stylePosition)).itemView.performClick())
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            val choices = dialog.listView
            assertEquals(6, choices.count)
            val decor = requireNotNull(dialog.window).decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(380, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.AT_MOST))
            decor.layout(0, 0, 380, decor.measuredHeight)
            for (position in 0 until choices.count) {
                val row = choices.getChildAt(position)
                    ?: choices.adapter.getView(position, null, choices)
                if (row.width == 0) {
                    row.measure(View.MeasureSpec.makeMeasureSpec(340, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    row.layout(0, 0, 340, row.measuredHeight)
                }
                assertReadable(requireNotNull(row.findViewById(android.R.id.text1)))
            }
            savePreview(decor, "appearance-style-options-${(scale * 100).toInt()}")
            dialog.dismiss()
        }
    }

    private fun assertReadable(text: android.widget.TextView) {
        assertTrue(text.text.isNotBlank())
        val lines = requireNotNull(text.layout)
        assertTrue(lines.lineCount > 0)
        for (line in 0 until lines.lineCount) assertEquals("Truncated setting text: ${text.text}",
            0, lines.getEllipsisCount(line))
        assertTrue("Text must fit inside its row: ${text.text}",
            lines.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
    }

    private fun savePreview(view: View, name: String) {
        val output = File("diagnostics/UI54-power-motion-20261017/$name.png")
        output.parentFile.mkdirs()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    private fun layout(host: AppearanceHost) {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            val content = host.window.decorView
            content.measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(891, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, 411, 891)
        }
    }

    private fun launch(): AppearanceHost = Robolectric.buildActivity(AppearanceHost::class.java)
        .setup().visible().also { controllers += it }.get()

    private fun appearance(host: AppearanceHost) =
        host.supportFragmentManager.findFragmentByTag("appearance") as SettingsAppearanceFragment

    @Suppress("RestrictedApi")
    private fun clickEnabled(host: AppearanceHost) {
        val list = appearance(host).listView
        val position = (list.adapter as PreferenceGroupAdapter)
            .getPreferenceAdapterPosition(PowerMotionSettings.KEY_ENABLED)
        assertTrue(position >= 0)
        list.scrollToPosition(position)
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            val content = host.findViewById<View>(android.R.id.content)
            content.measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(891, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, 411, 891)
        }
        assertTrue(requireNotNull(list.findViewHolderForAdapterPosition(position)).itemView.performClick())
        shadowOf(Looper.getMainLooper()).idle()
    }

    class AppearanceHost : FragmentActivity() {
        override fun attachBaseContext(base: Context) {
            val configuration = Configuration(base.resources.configuration).apply { fontScale = previewFontScale }
            super.attachBaseContext(base.createConfigurationContext(configuration))
        }
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(state)
            setContentView(FrameLayout(this).apply { id = 0x6f82 })
            if (state == null) supportFragmentManager.beginTransaction()
                .add(0x6f82, SettingsAppearanceFragment(), "appearance").commitNow()
        }

        companion object { var previewFontScale = 1f }
    }
}
