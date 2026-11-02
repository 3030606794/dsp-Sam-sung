package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
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
class HighFrequencyProfessionalUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val preferences get() = HighFrequencyController.preferences(app)
    private val p = HighFrequencyParameters
    private var controller: ActivityController<Host>? = null
    private var width = 320
    private fun clarity() = controller!!.get().supportFragmentManager.findFragmentByTag("clarity") as ViperEffectPreferenceFragment
    private fun spectrum() = controller!!.get().supportFragmentManager.findFragmentByTag("spectrum") as ViperEffectPreferenceFragment
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun runtime(rate: Int = 44100, audition: Int = 0) {
        val values = preferences.all
        fun effective(control: BassParameters.Control) = minOf(p.value(values, control), rate / 2 - 100)
        val lowpass = if (p.automaticLowpass(values)) rate / 2 - 2000 else effective(p.lowpassFrequency)
        HighFrequencyController.publishRuntime(app, listOf(HighFrequencyController.Reading("stream", true, rate,
            listOf(effective(p.shelfFrequency),effective(p.lowFrequency),effective(p.highFrequency),
                p.delaySamples(p.value(values,p.lowDelay),rate),p.delaySamples(p.value(values,p.midDelay),rate),
                effective(p.spectrumFrequency),lowpass), audition)))
        idle()
    }
    @Before fun prepare() {
        RuntimeEnvironment.setFontScale(1f)
        preferences.edit().clear().commit()
        HighFrequencyController.stopAudition(app)
        HighFrequencyController.publishCapabilities(app, 127)
        runtime()
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        idle()
        HighFrequencyController.stopAudition(app)
        HighFrequencyController.publishRuntime(app, emptyList())
        RuntimeEnvironment.setFontScale(1f)
    }
    private fun launch() {
        controller = Robolectric.buildActivity(Host::class.java).setup().visible()
        clarity().effectHeader.setExpanded(true)
        spectrum().effectHeader.setExpanded(true)
        layout()
    }
    private fun layout() {
        repeat(2) {
            idle()
            controller!!.get().findViewById<View>(android.R.id.content).apply {
                measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
                layout(0,0,width,2400)
            }
        }
        idle()
    }
    @Suppress("RestrictedApi")
    private fun row(fragment: ViperEffectPreferenceFragment, key: String): View {
        controller!!.get().show(fragment.effectHeader.key)
        val adapter = fragment.listView.adapter as PreferenceGroupAdapter
        val position = adapter.getPreferenceAdapterPosition(key)
        assertTrue("Visible row $key", position >= 0)
        fragment.listView.scrollToPosition(position)
        layout()
        return requireNotNull(fragment.listView.findViewHolderForAdapterPosition(position)).itemView
    }
    private fun number(fragment: ViperEffectPreferenceFragment, control: BassParameters.Control) =
        fragment.findPreference<BassNumericPreference>(control.key)!!
    private fun mode(value: Int) {
        controller!!.get().show("v_clarity")
        clarity().findPreference<ViperSliderPreference>("v_clarity_mode")!!.value = value
        layout()
    }

    @Test fun exactNumbersButtonsResetAndInvalidInputNeverEnableEitherEffect() {
        launch()
        assertEquals("清晰度增强", clarity().effectHeader.title)
        assertEquals("高频谐波激励", spectrum().effectHeader.title)
        row(clarity(), p.CLARITY_AMOUNT).findViewById<View>(R.id.bass_increment).performClick()
        assertEquals(1, preferences.getInt(p.CLARITY_AMOUNT,-1))
        row(spectrum(), p.SPECTRUM_AMOUNT).findViewById<View>(R.id.bass_increment).performClick()
        assertEquals(561, preferences.getInt(p.SPECTRUM_AMOUNT,-1))
        assertTrue(number(spectrum(),p.spectrumAmount).resetToDefault())
        assertEquals(560,preferences.getInt(p.SPECTRUM_AMOUNT,-1))
        row(clarity(),p.CLARITY_AMOUNT).findViewById<View>(R.id.seekbar_value).performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val input = dialog.findViewById<EditText>(android.R.id.text1)!!
        listOf("NaN","1.731","4.51","-0.01").forEach {
            input.setText(it); dialog.getButton(-1).performClick(); assertTrue(dialog.isShowing)
        }
        input.setText("1.73"); dialog.getButton(-1).performClick()
        assertFalse(dialog.isShowing)
        assertEquals(173,preferences.getInt(p.CLARITY_AMOUNT,-1))
        assertFalse(clarity().effectHeader.isChecked)
        assertFalse(spectrum().effectHeader.isChecked)
    }

    @Test fun eachModeAndAutomaticLowpassKeepOnlyRelevantRowsAcrossRebindAndRecreation() {
        launch()
        assertNull(clarity().findPreference<Preference>(p.shelfFrequency.key))
        mode(1)
        assertNotNull(clarity().findPreference<Preference>(p.shelfFrequency.key))
        assertNull(clarity().findPreference<Preference>(p.lowFrequency.key))
        mode(2)
        assertNull(clarity().findPreference<Preference>(p.shelfFrequency.key))
        assertNotNull(clarity().findPreference<Preference>(p.lowDelay.key))
        repeat(2) { clarity().effectHeader.setExpanded(false); layout(); clarity().effectHeader.setExpanded(true); layout() }
        assertNull(clarity().findPreference<Preference>(p.shelfFrequency.key))
        assertNull(spectrum().findPreference<Preference>(p.lowpassFrequency.key))
        spectrum().findPreference<SwitchPreferenceCompat>(p.LP_AUTO)!!.isChecked = false
        layout()
        assertNotNull(spectrum().findPreference<Preference>(p.lowpassFrequency.key))
        controller!!.recreate(); layout()
        assertEquals(2, controller!!.get().supportFragmentManager.fragments.size)
        assertTrue(clarity().effectHeader.isExpanded)
        assertNull(clarity().findPreference<Preference>(p.shelfFrequency.key))
        assertNotNull(spectrum().findPreference<Preference>(p.lowpassFrequency.key))
    }

    @Test fun delayEditsAreIntegerSamplesAndResetKeepsTheOriginalMicrosecondsAcrossRates() {
        launch(); mode(2)
        val low = number(clarity(),p.lowDelay)
        assertEquals(220,low.value)
        assertTrue(low.summary.toString().contains("4.989 ms"))
        assertTrue(low.step(1))
        assertEquals(221,p.delaySamples(preferences.getInt(p.lowDelay.key,-1),44100))
        assertTrue(low.resetToDefault())
        assertEquals(5000,preferences.getInt(p.lowDelay.key,-1))
        runtime(48000); layout()
        assertEquals(240,number(clarity(),p.lowDelay).value)
        assertTrue(number(clarity(),p.lowDelay).summary.toString().contains("5.000 ms"))
        HighFrequencyController.publishRuntime(app,emptyList()); layout()
        assertFalse(number(clarity(),p.lowDelay).isEnabled)
        assertEquals("等待采样率",number(clarity(),p.lowDelay).summary)
    }

    @Test fun oldDriverShowsOnlyRealIntegerPercentAndKeepsNewPreciseValueUntilEdited() {
        HighFrequencyController.publishCapabilities(app,63)
        preferences.edit().putInt(p.SPECTRUM_AMOUNT,437).putInt(p.CLARITY_AMOUNT,173).commit()
        launch(); mode(1)
        assertEquals(44,number(spectrum(),p.spectrumAmount).value)
        assertEquals("44 %",number(spectrum(),p.spectrumAmount).summary)
        assertEquals(437,preferences.getInt(p.SPECTRUM_AMOUNT,-1))
        assertEquals(173,number(clarity(),p.clarityAmount).value)
        assertFalse(number(clarity(),p.shelfFrequency).isEnabled)
        assertFalse(number(spectrum(),p.highpassQ).isEnabled)
        assertFalse(spectrum().findPreference<SwitchPreferenceCompat>("high_frequency_audition")!!.isEnabled)
        number(spectrum(),p.spectrumAmount).step(1)
        assertEquals(450,preferences.getInt(p.SPECTRUM_AMOUNT,-1))
        HighFrequencyController.publishCapabilities(app,127); layout()
        assertEquals(450,number(spectrum(),p.spectrumAmount).value)
        assertTrue(number(spectrum(),p.highpassQ).isEnabled)
    }

    @Test fun auditionIsTemporaryAndClearsOnFoldEffectDisableRecreationAndPause() {
        launch()
        controller!!.get().show("v_spectrum"); layout()
        val audition = spectrum().findPreference<SwitchPreferenceCompat>("high_frequency_audition")!!
        assertFalse(audition.isEnabled)
        row(spectrum(),"v_spectrum").findViewById<MaterialSwitch>(R.id.switchWidget).isChecked = true
        layout()
        fun start() {
            controller!!.get().show("v_spectrum"); layout()
            val pref = spectrum().findPreference<SwitchPreferenceCompat>("high_frequency_audition")!!
            assertTrue(pref.isEnabled)
            assertTrue(pref.callChangeListener(true))
            idle()
            assertTrue(HighFrequencyController.audition)
            assertTrue(preferences.all.keys.none { it.contains("audition") })
        }
        start(); spectrum().effectHeader.setExpanded(false); idle(); assertFalse(HighFrequencyController.audition)
        spectrum().effectHeader.setExpanded(true); layout(); start()
        preferences.edit().putBoolean("v_spectrum",false).commit(); idle(); assertFalse(HighFrequencyController.audition)
        preferences.edit().putBoolean("v_spectrum",true).commit(); idle(); start()
        controller!!.recreate(); layout(); assertFalse(HighFrequencyController.audition)
        start(); controller!!.pause(); idle(); assertFalse(HighFrequencyController.audition)
        controller!!.resume()
    }

    @Test fun malformedStoredTypesCannotCrashEitherNumericEditor() {
        preferences.edit().putFloat(p.CLARITY_AMOUNT,53.9f).putString(p.highpassQ.key,"invalid")
            .putLong(p.spectrumFrequency.key,8000L).commit()
        launch()
        assertEquals(0,number(clarity(),p.clarityAmount).value)
        assertEquals(717,number(spectrum(),p.highpassQ).value)
        assertEquals(8000,number(spectrum(),p.spectrumFrequency).value)
        listOf(p.CLARITY_AMOUNT,p.highpassQ.key,p.spectrumFrequency.key).forEach { assertTrue(preferences.all[it] is Int) }
    }

    @Test fun reopeningAfterOldPresetReplacementMigratesAgainWithoutRetainingProfessionalValues() {
        preferences.edit().putInt(p.CLARITY_AMOUNT,173).putInt(p.highpassQ.key,1100).putInt(p.SPECTRUM_AMOUNT,437).commit()
        launch()
        preferences.edit().clear().putInt("v_clarity_amount",4).putInt("v_spectrum_amount",56).commit()
        controller!!.recreate(); layout()
        assertEquals(200,number(clarity(),p.clarityAmount).value)
        assertEquals(560,number(spectrum(),p.spectrumAmount).value)
        assertEquals(717,number(spectrum(),p.highpassQ).value)
        assertFalse(clarity().effectHeader.isChecked)
        assertFalse(spectrum().effectHeader.isChecked)
    }

    private fun touch(slider: Slider, action: Int, time: Long, fraction: Float) {
        val event = MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,
            slider.trackSidePadding + slider.trackWidth * fraction,slider.height / 2f,0)
        try { assertTrue(slider.dispatchTouchEvent(event)) } finally { event.recycle() }
    }
    @Test fun bothSlidersCommitDuringTwoMovesWithoutRebindingOrDuplicateWrites() {
        launch()
        listOf(clarity() to p.clarityAmount,spectrum() to p.spectrumAmount).forEach { (fragment,control) ->
            val bound = row(fragment,control.key)
            val slider = bound.findViewById<Slider>(R.id.seekbar)
            var writes = 0
            var rebound = 0
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _,key -> if (key == control.key) writes++ }
            val observer = object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeChanged(positionStart: Int,itemCount: Int) { rebound += itemCount }
            }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            fragment.listView.adapter!!.registerAdapterDataObserver(observer)
            try {
                val time = SystemClock.uptimeMillis()
                touch(slider,MotionEvent.ACTION_DOWN,time,(slider.value-slider.valueFrom)/(slider.valueTo-slider.valueFrom))
                touch(slider,MotionEvent.ACTION_MOVE,time,.55f); idle()
                val first = slider.value.toInt()
                assertEquals(first,preferences.getInt(control.key,-1))
                touch(slider,MotionEvent.ACTION_MOVE,time,.85f); idle()
                assertTrue(slider.value.toInt() > first)
                assertEquals(slider.value.toInt(),preferences.getInt(control.key,-1))
                val count = writes
                repeat(3) { touch(slider,MotionEvent.ACTION_MOVE,time,.85f); idle() }
                touch(slider,MotionEvent.ACTION_UP,time,.85f); idle()
                assertEquals(count,writes)
                assertEquals(0,rebound)
                assertFalse(fragment.effectHeader.isChecked)
            } finally {
                preferences.unregisterOnSharedPreferenceChangeListener(listener)
                fragment.listView.adapter!!.unregisterAdapterDataObserver(observer)
            }
        }
    }

    @Test fun nativeRenderingAt320dpNormalAndLargeFonts() { renderCards(320,1f); renderCards(320,1.5f) }
    @Test @Config(qualifiers = "zh-rCN-w411dp-h891dp-port-mdpi")
    fun nativeRenderingAt411dpNormalAndLargeFonts() { renderCards(411,1f); renderCards(411,1.5f) }
    private fun renderCards(width: Int,font: Float) {
        controller?.pause()?.stop()?.destroy(); controller = null; idle()
        this.width = width; RuntimeEnvironment.setFontScale(font)
        preferences.edit().putInt("v_clarity_mode",0).putBoolean(p.LP_AUTO,false).commit()
        try {
            runtime(); launch()
            verifyText(row(clarity(),"v_clarity").findViewById(android.R.id.title))
            verifyText(row(spectrum(),"v_spectrum").findViewById(android.R.id.title))
            p.spectrum.forEach { verifyNumeric(row(spectrum(),it.key)) }
            capture(spectrum(),p.spectrumOutput.key,"spectrum-$width-${(font*100).toInt()}")
            for (selectedMode in 0..2) {
                mode(selectedMode)
                p.clarity.filter { clarity().findPreference<Preference>(it.key) != null }.forEach { verifyNumeric(row(clarity(),it.key)) }
                capture(clarity(),p.clarityOutput.key,"clarity-$selectedMode-$width-${(font*100).toInt()}")
            }
        } finally {
            controller?.pause()?.stop()?.destroy(); controller = null; idle()
        }
    }
    private fun verifyNumeric(row: View) {
        val number = row.findViewById<TextView>(R.id.seekbar_value)
        val minus = row.findViewById<View>(R.id.bass_decrement)
        val plus = row.findViewById<View>(R.id.bass_increment)
        val reset = row.findViewById<TextView>(R.id.bass_reset)
        assertTrue(minus.right <= number.left && number.right <= plus.left && plus.right <= reset.left)
        listOf(row.findViewById<TextView>(android.R.id.title),number,reset).forEach(::verifyText)
    }
    private fun verifyText(text: TextView) {
        val layout = requireNotNull(text.layout)
        repeat(layout.lineCount) {
            assertEquals(0,layout.getEllipsisCount(it))
            assertTrue("${text.text} fits its row",layout.getLineWidth(it) <= text.width-text.compoundPaddingLeft-text.compoundPaddingRight+1f)
        }
        assertTrue(layout.height <= text.height-text.totalPaddingTop-text.totalPaddingBottom)
    }
    @Suppress("RestrictedApi")
    private fun capture(fragment: ViperEffectPreferenceFragment,lastKey: String,name: String) {
        controller!!.get().show(fragment.effectHeader.key)
        (fragment.listView.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).scrollToPositionWithOffset(0,0)
        layout()
        val view = fragment.requireView() as ViewGroup
        val adapter = fragment.listView.adapter as PreferenceGroupAdapter
        val last = requireNotNull(fragment.listView.findViewHolderForAdapterPosition(adapter.getPreferenceAdapterPosition(lastKey))).itemView
        val bounds = android.graphics.Rect(0,0,last.width,last.height).apply { view.offsetDescendantRectToMyCoords(last,this) }
        assertTrue("First-generation rows fit the bounded viewport",bounds.bottom <= view.height)
        val bitmap = Bitmap.createBitmap(view.width,bounds.bottom,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(MaterialColors.getColor(view,com.google.android.material.R.attr.colorSurface))
            view.draw(Canvas(bitmap))
            val folder = File("build/reports/high-frequency-professional").apply { mkdirs() }
            File(folder,"$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
    }
    class Host : FragmentActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP); super.onCreate(state)
            setContentView(FrameLayout(this).apply {
                listOf(CLARITY_ID,SPECTRUM_ID).forEach { childId ->
                    addView(MaterialCardView(context).apply {
                        visibility = if (childId == CLARITY_ID) View.VISIBLE else View.GONE
                        addView(FragmentContainerView(context).apply { id = childId },ViewGroup.LayoutParams(-1,-1))
                    },FrameLayout.LayoutParams(-1,-1))
                }
            })
            if (state == null) supportFragmentManager.beginTransaction()
                .add(CLARITY_ID,ViperEffectPreferenceFragment.newInstance("v_clarity"),"clarity")
                .add(SPECTRUM_ID,ViperEffectPreferenceFragment.newInstance("v_spectrum"),"spectrum").commitNow()
        }
        fun show(key: String) {
            (findViewById<View>(CLARITY_ID).parent as View).visibility = if (key == "v_clarity") View.VISIBLE else View.GONE
            (findViewById<View>(SPECTRUM_ID).parent as View).visibility = if (key == "v_spectrum") View.VISIBLE else View.GONE
        }
        companion object { const val CLARITY_ID = 0x745121; const val SPECTRUM_ID = 0x745122 }
    }
}
