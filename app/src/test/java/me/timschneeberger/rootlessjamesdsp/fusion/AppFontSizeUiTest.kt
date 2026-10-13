package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.BaseActivity
import me.timschneeberger.rootlessjamesdsp.fragment.settings.SettingsAppearanceFragment
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSeekbarPreference
import me.timschneeberger.rootlessjamesdsp.utils.AppFontScale
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    qualifiers = "zh-rCN-w411dp-h891dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AppFontSizeUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
    private val controllers = mutableListOf<ActivityController<FontHostActivity>>()
    private var systemFontScale = 1f

    @Before fun prepare() {
        systemFontScale = RuntimeEnvironment.getFontScale()
        RuntimeEnvironment.setFontScale(1f)
        prefs.edit().clear().commit()
        stopKoin()
        val preferences = Preferences(app)
        startKoin { modules(module { single { preferences.App() }; single { preferences.Var() } }) }
    }

    @After fun cleanUp() {
        controllers.reversed().forEach { it.pause().stop().destroy() }
        idle()
        prefs.edit().remove(AppFontScale.KEY).commit()
        stopKoin()
        RuntimeEnvironment.setFontScale(systemFontScale)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(): ActivityController<FontHostActivity> =
        Robolectric.buildActivity(FontHostActivity::class.java).setup().visible().also {
            controllers.add(it)
            layout(it.get())
        }

    private fun layout(activity: FontHostActivity) {
        idle()
        activity.findViewById<View>(android.R.id.content).apply {
            measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(891, View.MeasureSpec.EXACTLY))
            layout(0, 0, 411, 891)
        }
        idle()
    }

    private fun appearance(activity: FontHostActivity) = activity.supportFragmentManager
        .findFragmentByTag("appearance") as SettingsAppearanceFragment

    @Suppress("RestrictedApi")
    private fun fontRow(activity: FontHostActivity): View {
        val fragment = appearance(activity)
        val adapter = fragment.listView.adapter as PreferenceGroupAdapter
        val position = adapter.getPreferenceAdapterPosition(AppFontScale.KEY)
        assertTrue(position >= 0)
        fragment.listView.scrollToPosition(position)
        layout(activity)
        return requireNotNull(fragment.listView.findViewHolderForAdapterPosition(position)).itemView
    }

    @Test fun defaultSizeFollowsTheSystemAndDoesNotChangeApplicationResources() {
        RuntimeEnvironment.setFontScale(1.3f)
        val activity = launch().get()
        assertEquals(100, AppFontScale.percent(app))
        assertEquals(1.3f, activity.resources.configuration.fontScale, .001f)
        assertEquals(1.3f, app.resources.configuration.fontScale, .001f)
    }

    @Test fun systemAndAppScalesCombineOnceAcrossWrappersActivitiesAndRecreation() {
        RuntimeEnvironment.setFontScale(1.2f)
        prefs.edit().putFloat(AppFontScale.KEY, 125f).commit()
        val wrapped = AppFontScale.wrap(app)
        assertEquals(1.5f, wrapped.resources.configuration.fontScale, .001f)
        assertEquals(1.5f, AppFontScale.wrap(wrapped).resources.configuration.fontScale, .001f)
        val first = launch()
        val second = launch()
        assertEquals(1.5f, first.get().resources.configuration.fontScale, .001f)
        assertEquals(1.5f, second.get().resources.configuration.fontScale, .001f)
        repeat(3) {
            first.recreate()
            layout(first.get())
            assertEquals(1.5f, first.get().resources.configuration.fontScale, .001f)
        }
        assertEquals(1.2f, app.resources.configuration.fontScale, .001f)
    }

    @Test fun aNewSystemFontScaleUsesTheNewSystemBaselineAfterRecreation() {
        prefs.edit().putFloat(AppFontScale.KEY, 120f).commit()
        val controller = launch()
        assertEquals(1.2f, controller.get().resources.configuration.fontScale, .001f)
        RuntimeEnvironment.setFontScale(1.3f)
        controller.recreate()
        layout(controller.get())
        assertEquals(1.56f, controller.get().resources.configuration.fontScale, .001f)
        assertEquals(1.3f, app.resources.configuration.fontScale, .001f)
    }

    @Test fun draggingOnlyChangesThePercentageUntilReleaseThenRequestsOneRecreationPerActivity() {
        val first = launch()
        val second = launch()
        val activity = first.get()
        val setting = appearance(activity).findPreference<MaterialSeekbarPreference>(AppFontScale.KEY)!!
        val slider = fontRow(activity).findViewById<Slider>(R.id.seekbar)
        assertEquals(80f, slider.valueFrom, 0f)
        assertEquals(150f, slider.valueTo, 0f)
        assertEquals(5f, slider.stepSize, 0f)
        assertFalse(setting.getUpdatesContinuously())
        setting.mTrackingTouch = true
        for (percent in listOf(110f, 125f, 150f)) slider.value = percent
        assertEquals(100, AppFontScale.percent(app))
        assertEquals(0, activity.fontRecreationRequests)
        assertEquals(0, second.get().fontRecreationRequests)
        assertEquals("150%", fontRow(activity).findViewById<TextView>(R.id.seekbar_value).text.toString())
        setting.mTrackingTouch = false
        setting.syncValueInternal(slider)
        idle()
        assertEquals(150, AppFontScale.percent(app))
        assertEquals(1, activity.fontRecreationRequests)
        assertEquals(1, second.get().fontRecreationRequests)
        first.recreate()
        layout(first.get())
        assertEquals(1.5f, first.get().resources.configuration.fontScale, .001f)
        assertEquals("150%", fontRow(first.get()).findViewById<TextView>(R.id.seekbar_value).text.toString())
    }

    @Test fun restoreReturnsToTheSystemBaselineAndPersistsForTheNextActivity() {
        RuntimeEnvironment.setFontScale(1.3f)
        prefs.edit().putFloat(AppFontScale.KEY, 150f).commit()
        val controller = launch()
        val activity = controller.get()
        val reset = appearance(activity).findPreference<Preference>("appearance_font_size_reset")!!
        assertTrue(reset.onPreferenceClickListener!!.onPreferenceClick(reset))
        idle()
        assertEquals(100, AppFontScale.percent(app))
        assertEquals(1, activity.fontRecreationRequests)
        controller.recreate()
        layout(controller.get())
        assertEquals(1.3f, controller.get().resources.configuration.fontScale, .001f)
        assertEquals(1.3f, launch().get().resources.configuration.fontScale, .001f)
    }

    @Test fun fontSliderAndItsPercentageRemainReadableAtEightyOneHundredAndOneHundredFiftyPercent() {
        for (percent in listOf(80, 100, 150)) {
            prefs.edit().putFloat(AppFontScale.KEY, percent.toFloat()).commit()
            val activity = launch().get()
            val row = fontRow(activity)
            val title = row.findViewById<TextView>(android.R.id.title)
            val value = row.findViewById<TextView>(R.id.seekbar_value)
            val slider = row.findViewById<Slider>(R.id.seekbar)
            assertEquals("字体大小", title.text.toString())
            assertEquals("$percent%", value.text.toString())
            for (text in listOf(title, value)) {
                assertTrue(text.isShown)
                assertEquals(1, text.layout.lineCount)
                assertEquals(0, text.layout.getEllipsisCount(0))
                assertTrue(text.layout.getLineWidth(0) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight)
                assertTrue(text.height >= text.layout.height)
            }
            assertTrue("The percentage does not overlap the slider", slider.right <= value.left)
            assertTrue("There is room to drag the font-size slider", slider.width >= 160)
            val bitmap = Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888)
            try {
                row.draw(Canvas(bitmap))
                val directory = File("build/reports/font-size").apply { mkdirs() }
                File(directory, "font-$percent.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun invalidStoredValuesUseSafeBoundsOrTheSystemDefault() {
        prefs.edit().putFloat(AppFontScale.KEY, Float.NaN).commit()
        assertEquals(100, AppFontScale.percent(app))
        prefs.edit().putFloat(AppFontScale.KEY, -1f).commit()
        assertEquals(80, AppFontScale.percent(app))
        prefs.edit().putFloat(AppFontScale.KEY, 1000f).commit()
        assertEquals(150, AppFontScale.percent(app))
    }

    class FontHostActivity : BaseActivity() {
        override val disableAppTheme = true
        var fontRecreationRequests = 0
            private set

        // Observe real BaseActivity recreation requests; the tests drive the lifecycle
        // with ActivityController.recreate so both the old and new contexts can be checked.
        override fun recreate() { fontRecreationRequests++ }

        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = HOST_ID })
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .add(HOST_ID, SettingsAppearanceFragment(), "appearance").commitNow()
        }

        companion object { const val HOST_ID = 0x7713 }
    }
}
