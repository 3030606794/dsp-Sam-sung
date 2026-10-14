package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class BassProfessionalUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<Host>? = null
    private var width = 320
    private val viperPrefs get() = BassController.preferences(app, true)
    private val dynamicPrefs get() = BassController.preferences(app, false)
    private fun viper() = controller!!.get().supportFragmentManager.findFragmentByTag("viper") as ViperEffectPreferenceFragment
    private fun dynamic() = controller!!.get().supportFragmentManager.findFragmentByTag("dynamic") as DynamicBassPreferenceFragment

    @Before fun prepare() {
        RuntimeEnvironment.setFontScale(1f)
        viperPrefs.edit().clear().commit()
        dynamicPrefs.edit().clear().commit()
        BassController.publishCapabilities(app, 63)
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        idle()
        RuntimeEnvironment.setFontScale(1f)
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun launch() {
        controller = Robolectric.buildActivity(Host::class.java).setup().visible()
        layout()
    }
    private fun layout() {
        repeat(2) {
            idle()
            controller!!.get().findViewById<View>(android.R.id.content).apply {
                measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(10000, View.MeasureSpec.EXACTLY))
                layout(0, 0, width, 10000)
            }
        }
        idle()
    }
    @Suppress("RestrictedApi")
    private fun row(fragment: PreferenceFragmentCompat, key: String): View {
        val adapter = fragment.listView.adapter as PreferenceGroupAdapter
        val position = adapter.getPreferenceAdapterPosition(key)
        assertTrue("Visible row $key", position >= 0)
        fragment.listView.scrollToPosition(position)
        layout()
        return requireNotNull(fragment.listView.findViewHolderForAdapterPosition(position)).itemView
    }

    @Test fun fineButtonsInputAndResetPersistRealStepsWithoutTurningEitherEffectOn() {
        launch()
        viper().effectHeader.setExpanded(true)
        dynamic().effectHeader.setExpanded(true)
        layout()
        val amount = viper().findPreference<BassNumericPreference>(BassParameters.AMOUNT)!!
        row(viper(), BassParameters.AMOUNT).findViewById<View>(R.id.bass_increment).performClick()
        layout()
        assertEquals(51, amount.value)
        assertEquals(51, viperPrefs.getInt(BassParameters.AMOUNT, -1))
        assertTrue(viperPrefs.all[BassParameters.AMOUNT] is Int)
        assertEquals("0.51", row(viper(), BassParameters.AMOUNT).findViewById<TextView>(R.id.seekbar_value).text.toString())
        assertEquals(1f, row(viper(), BassParameters.AMOUNT).findViewById<Slider>(R.id.seekbar).stepSize, 0f)
        assertFalse(amount.requestValue(49))
        assertFalse(amount.requestValue(601))
        assertTrue(amount.requestValue(BassParameters.amount.parse("1.73")!!))
        row(viper(), BassParameters.AMOUNT).findViewById<View>(R.id.bass_reset).performClick()
        assertEquals(50, amount.value)
        val gain = dynamic().findPreference<BassNumericPreference>(BassParameters.MAX_GAIN)!!
        row(dynamic(), BassParameters.MAX_GAIN).findViewById<View>(R.id.bass_increment).performClick()
        assertEquals(51, gain.value)
        assertTrue(gain.resetToDefault())
        assertEquals(50, dynamicPrefs.getInt(BassParameters.MAX_GAIN, -1))
        assertFalse(viper().effectHeader.isChecked)
        assertFalse(dynamic().effectHeader.isChecked)
    }

    @Test fun modeSpecificControlsStayAbsentAfterCollapseExpandAndViewRecreation() {
        launch()
        val mode = viper().findPreference<ViperSliderPreference>("v_bass_mode")!!
        assertNotNull(viper().findPreference<Preference>(BassParameters.frequency.key))
        assertNull(viper().findPreference<Preference>(BassParameters.deepFrequency.key))
        mode.value = 2
        idle()
        assertNull(viper().findPreference<Preference>(BassParameters.frequency.key))
        assertNull(viper().findPreference<Preference>(BassParameters.naturalQ.key))
        assertNotNull(viper().findPreference<Preference>(BassParameters.deepFrequency.key))
        repeat(2) { viper().effectHeader.setExpanded(true); layout(); viper().effectHeader.setExpanded(false); layout() }
        viper().effectHeader.setExpanded(true)
        controller!!.recreate()
        layout()
        assertEquals(2, controller!!.get().supportFragmentManager.fragments.size)
        assertTrue(viper().effectHeader.isExpanded)
        assertNull(viper().findPreference<Preference>(BassParameters.frequency.key))
        assertTrue(viper().findPreference<BassNumericPreference>(BassParameters.deepWidth.key)!!.isEnabled)
        viper().findPreference<ViperSliderPreference>("v_bass_mode")!!.value = 0
        idle()
        assertNotNull(viper().findPreference<Preference>(BassParameters.frequency.key))
        assertNull(viper().findPreference<Preference>(BassParameters.deepFrequency.key))
    }

    @Test fun clickingTheNumberAcceptsExactDecimalInputAndKeepsInvalidPrecisionInTheDialog() {
        launch()
        viper().effectHeader.setExpanded(true)
        layout()
        row(viper(), BassParameters.AMOUNT).findViewById<View>(R.id.seekbar_value).performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val input = dialog.findViewById<EditText>(android.R.id.text1)!!
        input.setText("1.731")
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertEquals(50, viper().findPreference<BassNumericPreference>(BassParameters.AMOUNT)!!.value)
        input.setText("1.73")
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(dialog.isShowing)
        assertEquals(173, viperPrefs.getInt(BassParameters.AMOUNT, -1))
        assertFalse(viper().effectHeader.isChecked)
    }

    @Test fun switchesAndFoldStateAreIndependentAndEditingWhileOffNeverEnablesSound() {
        launch()
        row(viper(), "v_bass").performClick()
        row(dynamic(), "bass_enable").performClick()
        layout()
        assertTrue(viper().effectHeader.isExpanded)
        assertTrue(dynamic().effectHeader.isExpanded)
        assertFalse(viper().effectHeader.isChecked)
        assertFalse(dynamic().effectHeader.isChecked)
        row(viper(), "v_bass").findViewById<MaterialSwitch>(R.id.switchWidget).performClick()
        layout()
        assertTrue(viper().effectHeader.isChecked)
        assertFalse(dynamic().effectHeader.isChecked)
        row(viper(), "v_bass").performClick()
        layout()
        assertFalse(viper().effectHeader.isExpanded)
        assertTrue(viper().effectHeader.isChecked)
        assertTrue(dynamic().findPreference<BassNumericPreference>(BassParameters.rise.key)!!.requestValue(250))
        assertFalse(dynamic().effectHeader.isChecked)
    }

    @Test fun capabilitiesKeepLegacyBasicsUsableAndNeverPretendAdvancedFieldsAreActive() {
        BassController.publishCapabilities(app, 31)
        launch()
        assertTrue(viper().findPreference<BassNumericPreference>(BassParameters.AMOUNT)!!.requestValue(51))
        val q = viper().findPreference<BassNumericPreference>(BassParameters.naturalQ.key)!!
        assertFalse(q.isEnabled)
        assertFalse(q.requestValue(100))
        assertNull(dynamic().findPreference<Preference>(BassParameters.MAX_GAIN))
        val legacy = dynamic().findPreference<BassNumericPreference>("bass_legacy_gain_ui")!!
        assertTrue(legacy.requestValue(6))
        assertEquals(6f, dynamicPrefs.getFloat(BassParameters.LEGACY_MAX_GAIN, 0f), 0f)
        assertEquals(60, dynamicPrefs.getInt(BassParameters.MAX_GAIN, -1))
        assertFalse(dynamic().findPreference<BassNumericPreference>(BassParameters.rise.key)!!.isEnabled)
        BassController.publishCapabilities(app, 63)
        idle()
        assertTrue(q.isEnabled)
        assertNotNull(dynamic().findPreference<Preference>(BassParameters.MAX_GAIN))
        assertNull(dynamic().findPreference<Preference>("bass_legacy_gain_ui"))
    }

    @Test fun invalidImportedFrequencyBoundsAreVisibleExactlyAsSentAndOldPresetsMigrateAgain() {
        dynamicPrefs.edit().putInt(BassParameters.trackLow.key, 200).putInt(BassParameters.trackHigh.key, 80).commit()
        launch()
        assertEquals(0, dynamic().findPreference<BassNumericPreference>(BassParameters.trackLow.key)!!.value)
        assertEquals(250, dynamic().findPreference<BassNumericPreference>(BassParameters.trackHigh.key)!!.value)
        assertFalse(dynamic().findPreference<BassNumericPreference>(BassParameters.trackHigh.key)!!.requestValue(0))
        dynamicPrefs.edit().clear().putFloat(BassParameters.LEGACY_MAX_GAIN, 7.7f).commit()
        app.sendLocalBroadcast(Intent(Constants.ACTION_PRESET_LOADED))
        idle()
        assertEquals(80, dynamic().findPreference<BassNumericPreference>(BassParameters.MAX_GAIN)!!.value)
        assertFalse(dynamic().effectHeader.isChecked)
        assertFalse(dynamic().effectHeader.isExpanded)
    }

    @Test fun malformedViperStoredTypesNormalizeBeforeThePreferenceReadsItsPersistedInt() {
        viperPrefs.edit().putFloat(BassParameters.AMOUNT, 53.9f).putString(BassParameters.naturalQ.key, "invalid")
            .putLong(BassParameters.frequency.key, 80L).commit()
        launch()
        assertEquals(50, viper().findPreference<BassNumericPreference>(BassParameters.AMOUNT)!!.value)
        assertEquals(53, viper().findPreference<BassNumericPreference>(BassParameters.naturalQ.key)!!.value)
        assertEquals(80, viper().findPreference<BassNumericPreference>(BassParameters.frequency.key)!!.value)
        listOf(BassParameters.AMOUNT, BassParameters.naturalQ.key, BassParameters.frequency.key)
            .forEach { assertTrue(viperPrefs.all[it] is Int) }
    }

    @Test fun compactNativeRenderingAtNormalAndLargeFonts() { renderCards(320, 1f); renderCards(320, 1.5f) }
    @Test @Config(qualifiers = "zh-rCN-w411dp-h891dp-port-mdpi")
    fun regularNativeRenderingAtNormalAndLargeFonts() { renderCards(411, 1f); renderCards(411, 1.5f) }

    private fun renderCards(width: Int, fontScale: Float) {
        controller?.pause()?.stop()?.destroy()
        controller = null
        idle()
        this.width = width
        RuntimeEnvironment.setFontScale(fontScale)
        viperPrefs.edit().putInt("v_bass_mode", 0).commit()
        launch()
        viper().effectHeader.setExpanded(true)
        dynamic().effectHeader.setExpanded(true)
        layout()
        listOf(viper(), dynamic()).forEach { fragment ->
            val keys = if (fragment is ViperEffectPreferenceFragment)
                listOf(BassParameters.frequency.key, BassParameters.AMOUNT, BassParameters.naturalQ.key)
            else BassParameters.dynamic.map { it.key }
            keys.forEach { key -> verifyNumericRow(row(fragment, key)) }
        }
        capture(viper().requireView(), "viper-natural-$width-${(fontScale * 100).toInt()}")
        capture(dynamic().requireView(), "dynamic-$width-${(fontScale * 100).toInt()}")
        viper().findPreference<ViperSliderPreference>("v_bass_mode")!!.value = 2
        layout()
        listOf(BassParameters.deepFrequency, BassParameters.deepGain, BassParameters.deepWidth,
            BassParameters.punchFrequency, BassParameters.punchGain, BassParameters.punchWidth).forEach {
            verifyNumericRow(row(viper(), it.key))
        }
        capture(viper().requireView(), "viper-subwoofer-$width-${(fontScale * 100).toInt()}")
    }

    private fun verifyNumericRow(row: View) {
        val number = row.findViewById<TextView>(R.id.seekbar_value)
        val minus = row.findViewById<View>(R.id.bass_decrement)
        val plus = row.findViewById<View>(R.id.bass_increment)
        val reset = row.findViewById<TextView>(R.id.bass_reset)
        assertTrue(minus.right <= number.left)
        assertTrue(number.right <= plus.left)
        assertTrue(plus.right <= reset.left)
        assertTrue(row.findViewById<Slider>(R.id.seekbar).width >= 240)
        listOf(row.findViewById<TextView>(android.R.id.title), number, reset).forEach { text ->
            assertTrue(text.isShown)
            val textLayout = requireNotNull(text.layout)
            repeat(textLayout.lineCount) { line ->
                assertEquals(0, textLayout.getEllipsisCount(line))
                assertTrue("${text.text} fits its native text width", textLayout.getLineWidth(line) <=
                    text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 1f)
            }
            assertTrue(textLayout.height <= text.height - text.totalPaddingTop - text.totalPaddingBottom)
        }
    }
    private fun capture(view: View, name: String) {
        assertTrue(view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface))
            view.draw(Canvas(bitmap))
            val folder = File("build/reports/bass-professional").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    class Host : FragmentActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(state)
            setContentView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                listOf(VIPER_ID, DYNAMIC_ID).forEach { childId ->
                    addView(MaterialCardView(context).apply {
                        addView(FragmentContainerView(context).apply { id = childId }, ViewGroup.LayoutParams(-1, -2))
                    }, LinearLayout.LayoutParams(-1, -2))
                }
            })
            if (state == null) supportFragmentManager.beginTransaction()
                .add(VIPER_ID, ViperEffectPreferenceFragment.newInstance("v_bass"), "viper")
                .add(DYNAMIC_ID, DynamicBassPreferenceFragment(), "dynamic").commitNow()
        }
        companion object { const val VIPER_ID = 0x745111; const val DYNAMIC_ID = 0x745112 }
    }
}
