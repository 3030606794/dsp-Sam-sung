package me.timschneeberger.rootlessjamesdsp.liveprog

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import java.io.File
import java.util.Locale
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.LiveprogParamsActivity
import me.timschneeberger.rootlessjamesdsp.fragment.LiveprogParamsFragment
import me.timschneeberger.rootlessjamesdsp.preference.DropDownPreference
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSeekbarPreference
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class LiveprogParamsUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<LiveprogParamsActivity>? = null
    private lateinit var previousLocale: Locale
    private lateinit var script: File

    @Before fun prepare() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        stopKoin()
        val preferences = Preferences(app)
        startKoin { modules(module {
            single { preferences.App() }
            single { preferences.Var() }
        }) }
        script = File(app.getExternalFilesDir(null), "Liveprog/viper_dynamicbass_preset.eel")
        script.parentFile!!.mkdirs()
        script.writeText(app.assets.open("Liveprog/viper_dynamicbass_preset.eel").bufferedReader().use { it.readText() })
        app.getSharedPreferences(Constants.PREF_LIVEPROG, Context.MODE_PRIVATE).edit()
            .putString(app.getString(R.string.key_liveprog_file), "Liveprog/${script.name}").commit()
    }

    @After fun cleanUp() {
        controller?.pause()?.stop()?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        script.delete()
        stopKoin()
        Locale.setDefault(previousLocale)
    }

    private fun layout() {
        shadowOf(Looper.getMainLooper()).idle()
        val root = controller!!.get().findViewById<View>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 1920)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun launch(): LiveprogParamsFragment {
        controller = Robolectric.buildActivity(LiveprogParamsActivity::class.java,
            Intent(app, LiveprogParamsActivity::class.java).putExtra(LiveprogParamsActivity.EXTRA_TARGET_FILE, script.absolutePath))
            .setup().visible()
        layout()
        return fragment()
    }

    private fun fragment() = controller!!.get().supportFragmentManager.findFragmentById(R.id.params) as LiveprogParamsFragment
    private fun gain() = fragment().findPreference<MaterialSeekbarPreference>("dBassGain")!!

    @Test fun firstBindingShowsFiveAndChinesePresetWithoutRewritingScript() {
        val original = script.readText()
        launch()
        assertEquals("低音强度（%）", gain().title.toString())
        assertEquals(5f, gain().getValue(), 0f)
        assertEquals(5f, gain().mSeekBar.value, 0f)
        val preset = fragment().findPreference<DropDownPreference>("dPreset")!!
        assertEquals("预设", preset.title.toString())
        assertEquals("极致头戴式耳机（v2）", preset.entries[0].toString())
        assertEquals("18", preset.value)
        assertEquals(original, script.readText())
    }

    @Test fun savedManualValueSurvivesBindingRecreationAndOpeningAgain() {
        launch()
        gain().setValue(27f) // The same path used by the control's manual-input confirmation.
        layout()
        val saved = script.readText()
        assertEquals(27f, EelNumberRangeProperty.findVariable("dBassGain", saved)!!, 0f)
        fragment().listView.adapter!!.notifyDataSetChanged()
        layout()
        assertEquals(saved, script.readText())
        controller!!.recreate()
        layout()
        assertEquals(27f, gain().getValue(), 0f)
        assertEquals(27f, gain().mSeekBar.value, 0f)
        assertEquals(saved, script.readText())
        controller!!.pause().stop().destroy()
        controller = null
        launch()
        assertEquals(27f, gain().getValue(), 0f)
        fragment().restoreDefaults()
        layout()
        assertEquals(5f, gain().getValue(), 0f)
        assertEquals(5f, EelNumberRangeProperty.findVariable("dBassGain", script.readText())!!, 0f)
    }

    @Test fun scriptLibraryUsesChineseTitleAndKeepsTheOriginalFilePath() {
        val library = FileLibraryPreference(app, null).apply { type = "Liveprog" }
        val index = library.entryValues.indexOf("Liveprog/${script.name}")
        assertTrue(index >= 0)
        assertEquals("动态低音（基于 ViperFX，预设）", library.entries[index].toString())
        assertTrue(script.exists())
    }
}
