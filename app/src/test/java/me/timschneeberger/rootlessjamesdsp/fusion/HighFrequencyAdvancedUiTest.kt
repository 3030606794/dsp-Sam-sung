package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
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
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HighFrequencyAdvancedUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val preferences get() = HighFrequencyController.preferences(app)
    private val p = HighFrequencyAdvancedParameters
    private var controller: ActivityController<HighFrequencyProfessionalUiTest.Host>? = null
    private var width = 320
    private fun clarity() = controller!!.get().supportFragmentManager.findFragmentByTag("clarity") as ViperEffectPreferenceFragment
    private fun spectrum() = controller!!.get().supportFragmentManager.findFragmentByTag("spectrum") as ViperEffectPreferenceFragment
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    @Before fun prepare() {
        RuntimeEnvironment.setFontScale(1f)
        preferences.edit().clear().commit()
        HighFrequencyController.stopAudition(app)
        HighFrequencyController.publishRuntime(app,emptyList())
        HighFrequencyController.publishCapabilities(app,255)
        runtime()
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy(); controller = null; idle()
        HighFrequencyController.stopAudition(app)
        HighFrequencyController.publishRuntime(app,emptyList())
        RuntimeEnvironment.setFontScale(1f)
    }
    private fun runtime(rate: Int = 44100, frames: Int = 0, emptyBand: Boolean = false, audition: Int = 0) {
        val t = if (emptyBand) listOf(0,0,rate / 2 - 100,0,0,4,32,321,123)
            else listOf(3000,minOf(12000,rate/2-100),minOf(6000,rate/2-100),4500,minOf(10000,rate/2-100),4,32,321,123)
        HighFrequencyController.publishRuntime(app,listOf(HighFrequencyController.Reading("stream",true,rate,
            listOf(minOf(8250,rate/2-100),120,1200,rate/200,rate/400,minOf(7600,rate/2-100),rate/2-2000),audition,t,frames)))
        idle()
    }
    private fun launch() {
        controller = Robolectric.buildActivity(HighFrequencyProfessionalUiTest.Host::class.java).setup().visible()
        clarity().effectHeader.setExpanded(true); spectrum().effectHeader.setExpanded(true); layout()
    }
    private fun layout() {
        repeat(2) {
            idle()
            controller!!.get().findViewById<View>(android.R.id.content).apply {
                measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
                layout(0,0,width,2400)
            }
        }; idle()
    }
    @Suppress("RestrictedApi")
    private fun row(fragment: ViperEffectPreferenceFragment,key: String): View {
        controller!!.get().show(fragment.effectHeader.key)
        val adapter = fragment.listView.adapter as PreferenceGroupAdapter
        val position = adapter.getPreferenceAdapterPosition(key)
        assertTrue("Visible row $key",position >= 0)
        fragment.listView.scrollToPosition(position); layout()
        return requireNotNull(fragment.listView.findViewHolderForAdapterPosition(position)).itemView
    }
    private fun number(fragment: ViperEffectPreferenceFragment,control: BassParameters.Control) =
        fragment.findPreference<BassNumericPreference>(control.key)!!
    private fun mode(value: Int) { clarity().findPreference<ViperSliderPreference>("v_clarity_mode")!!.value = value; layout() }
    @Test fun independentNewSwitchesModeAndFoldNeverEnableTheParentEffects() {
        launch()
        p.switches.forEach { key -> clarity().findPreference<SwitchPreferenceCompat>(key)!!.isChecked = true }
        assertFalse(clarity().effectHeader.isChecked); assertFalse(spectrum().effectHeader.isChecked)
        mode(1)
        assertNull(clarity().findPreference<Preference>(p.NATURAL_ENABLED))
        assertNull(clarity().findPreference<Preference>(p.naturalLow.key))
        assertNotNull(clarity().findPreference<Preference>(p.DYNAMIC_ENABLED))
        assertNotNull(clarity().findPreference<Preference>(p.DEESS_ENABLED))
        repeat(2) { clarity().effectHeader.setExpanded(false); layout(); clarity().effectHeader.setExpanded(true); layout() }
        assertNull(clarity().findPreference<Preference>(p.naturalLow.key))
        mode(2); assertNotNull(clarity().findPreference<Preference>(p.dynamicThreshold.key))
        mode(0); assertTrue(clarity().findPreference<SwitchPreferenceCompat>(p.NATURAL_ENABLED)!!.isChecked)
        controller!!.recreate(); layout()
        p.switches.forEach { assertTrue(clarity().findPreference<SwitchPreferenceCompat>(it)!!.isChecked) }
        assertFalse(clarity().effectHeader.isChecked)
    }
    @Test fun crossingFrequencyInputIsRejectedAndEveryNewNumberHasFineStepsAndReset() {
        launch()
        row(clarity(),p.naturalLow.key).findViewById<View>(R.id.seekbar_value).performClick(); idle()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val input = dialog.findViewById<EditText>(android.R.id.text1)!!
        for (invalid in listOf("12000","3000.5","NaN","999")) {
            input.setText(invalid); dialog.getButton(-1).performClick(); assertTrue(dialog.isShowing)
        }
        input.setText("11999"); dialog.getButton(-1).performClick(); assertFalse(dialog.isShowing)
        assertEquals(11999,preferences.getInt(p.naturalLow.key,-1))
        assertFalse(number(clarity(),p.naturalHigh).requestValue(11999))
        assertTrue(number(clarity(),p.naturalLow).resetToDefault())
        (p.clarity.map { clarity() to it } + p.nonlinear.map { spectrum() to it }).forEach { (fragment,control) ->
            val pref = number(fragment,control)
            assertTrue(pref.isEnabled)
            val delta = if (control.default == control.max) -1 else 1
            assertTrue(pref.step(delta)); assertEquals(control.default + delta,preferences.getInt(control.key,-1))
            assertTrue(pref.resetToDefault()); assertEquals(control.default,preferences.getInt(control.key,-1))
        }
        assertFalse(clarity().effectHeader.isChecked); assertFalse(spectrum().effectHeader.isChecked)
        p.switches.forEach { assertFalse(preferences.getBoolean(it,true)) }
    }
    @Test fun oversamplingHasOnlyRealFactorsAndResetDoesNotEnableTheEffect() {
        launch()
        val pref = spectrum().findPreference<HighFrequencyOversamplingPreference>(p.OVERSAMPLING)!!
        assertEquals(1,pref.value)
        assertTrue(pref.step(1)); assertEquals(2,pref.value)
        assertTrue(pref.step(1)); assertEquals(4,pref.value)
        assertFalse(pref.requestValue(3)); assertEquals(4,preferences.getInt(p.OVERSAMPLING,-1))
        row(spectrum(),p.OVERSAMPLING).findViewById<View>(R.id.seekbar_value).performClick(); idle()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.getButton(-3).performClick(); idle()
        assertEquals(1,pref.value); assertFalse(spectrum().effectHeader.isChecked)
    }
    @Test fun conflictingSingleValueResetAndButtonsExplainHowToMoveTheOtherBoundary() {
        launch()
        assertTrue(number(clarity(),p.naturalLow).requestValue(1000))
        assertTrue(number(clarity(),p.naturalHigh).requestValue(2000)); layout()
        row(clarity(),p.naturalLow.key).findViewById<View>(R.id.bass_reset).performClick(); idle()
        assertEquals(1000,preferences.getInt(p.naturalLow.key,-1))
        assertEquals(2000,preferences.getInt(p.naturalHigh.key,-1))
        assertTrue(ShadowToast.getTextOfLatestToast().contains("先调高上限"))
        assertTrue(number(clarity(),p.naturalLow).requestValue(1999)); layout()
        row(clarity(),p.naturalLow.key).findViewById<View>(R.id.bass_increment).performClick(); idle()
        assertEquals(1999,preferences.getInt(p.naturalLow.key,-1))
        assertTrue(ShadowToast.getTextOfLatestToast().contains("先调高上限"))
        assertTrue(number(clarity(),p.deessHigh).requestValue(15000))
        assertTrue(number(clarity(),p.deessLow).requestValue(12000)); layout()
        row(clarity(),p.deessHigh.key).findViewById<View>(R.id.bass_reset).performClick(); idle()
        assertEquals(15000,preferences.getInt(p.deessHigh.key,-1))
        assertEquals(12000,preferences.getInt(p.deessLow.key,-1))
        assertTrue(ShadowToast.getTextOfLatestToast().contains("先调低下限"))
    }
    @Test fun malformedPresetBoundariesAreShownExactlyAsTheirNormalizedPackets() {
        preferences.edit().putInt(p.naturalLow.key,12000).putInt(p.naturalHigh.key,2000)
            .putInt(p.deessLow.key,12000).putInt(p.deessHigh.key,3000).putString(p.OVERSAMPLING,"4").commit()
        launch()
        val natural = p.naturalPacket(preferences.all)
        val deess = p.deessPacket(preferences.all)
        assertEquals(natural[1],number(clarity(),p.naturalLow).value)
        assertEquals(natural[2],number(clarity(),p.naturalHigh).value)
        assertEquals(deess[1],number(clarity(),p.deessLow).value)
        assertEquals(deess[2],number(clarity(),p.deessHigh).value)
        assertEquals(1,spectrum().findPreference<HighFrequencyOversamplingPreference>(p.OVERSAMPLING)!!.value)
        assertTrue(natural[1] < natural[2]); assertTrue(deess[1] < deess[2])
    }
    @Test fun firstRoundDriverKeepsFirstRoundControlsWhileAllNewProcessingIsDisabled() {
        HighFrequencyController.publishCapabilities(app,127)
        launch(); mode(1)
        assertTrue(number(clarity(),HighFrequencyParameters.shelfFrequency).isEnabled)
        assertTrue(number(spectrum(),HighFrequencyParameters.highpassQ).isEnabled)
        assertFalse(number(spectrum(),p.drive).isEnabled)
        assertFalse(number(clarity(),p.dynamicThreshold).isEnabled)
        assertFalse(clarity().findPreference<SwitchPreferenceCompat>(p.DEESS_ENABLED)!!.isEnabled)
        assertFalse(spectrum().findPreference<HighFrequencyOversamplingPreference>(p.OVERSAMPLING)!!.isEnabled)
        assertNotNull(clarity().findPreference<Preference>("high_frequency_advanced_driver"))
        HighFrequencyController.publishCapabilities(app,255); layout()
        assertTrue(number(spectrum(),p.drive).isEnabled)
        assertTrue(clarity().findPreference<SwitchPreferenceCompat>(p.DEESS_ENABLED)!!.isEnabled)
        assertNull(clarity().findPreference<Preference>("high_frequency_advanced_driver"))
    }
    @Test fun unavailableBandsIdleMetersAndActiveAttenuationAreExplicit() {
        preferences.edit().putBoolean("v_clarity",true).putBoolean("v_spectrum",true)
            .putBoolean(p.DYNAMIC_ENABLED,true).putBoolean(p.DEESS_ENABLED,true).putInt(p.OVERSAMPLING,4).commit()
        launch(); runtime(8000,0,true)
        fun summary(fragment: ViperEffectPreferenceFragment) = fragment.findPreference<Preference>("high_frequency_advanced_actual")!!.summary.toString()
        assertTrue(summary(clarity()).contains("无有效频带"))
        assertFalse(summary(clarity()).contains("3.21 dB"))
        runtime(44100,64); runtime(44100,128); layout()
        assertTrue(summary(clarity()).contains("3.21 dB")); assertTrue(summary(clarity()).contains("1.23 dB"))
        assertTrue(summary(spectrum()).contains("实际 4×")); assertTrue(summary(spectrum()).contains("32 样本（0.726 ms）"))
        runtime(44100,128); layout()
        assertTrue(summary(clarity()).contains("当前未处理音频")); assertFalse(summary(clarity()).contains("3.21 dB"))
        assertTrue(summary(spectrum()).contains("等待音频处理"))
    }
    @Test fun secondRoundEditsCannotReviveTemporaryAuditionAfterCollapseOrMasterOff() {
        preferences.edit().putBoolean("v_spectrum",true).commit(); launch()
        controller!!.get().show("v_spectrum"); layout()
        val audition = spectrum().findPreference<SwitchPreferenceCompat>("high_frequency_audition")!!
        assertTrue(audition.callChangeListener(true)); idle(); assertTrue(HighFrequencyController.audition)
        number(spectrum(),p.drive).step(1); idle(); assertTrue(HighFrequencyController.audition)
        spectrum().effectHeader.setExpanded(false); idle(); assertFalse(HighFrequencyController.audition)
        spectrum().effectHeader.setExpanded(true); layout()
        HighFrequencyController.sessionEnabled(app,"stream",false)
        number(spectrum(),p.drive).step(1); idle(); assertFalse(HighFrequencyController.audition)
        assertFalse(audition.callChangeListener(true))
        HighFrequencyController.sessionClosed(app,"stream")
    }
    private fun touch(slider: Slider,action: Int,time: Long,fraction: Float) {
        val event = MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,
            slider.trackSidePadding+slider.trackWidth*fraction,slider.height/2f,0)
        try { assertTrue(slider.dispatchTouchEvent(event)) } finally { event.recycle() }
    }
    @Test fun newNumericDragsCommitAtBothMovesWithoutRebindingOrDuplicateWrites() {
        launch()
        listOf(spectrum() to p.drive,clarity() to p.dynamicThreshold).forEach { (fragment,control) ->
            val slider = row(fragment,control.key).findViewById<Slider>(R.id.seekbar)
            var writes = 0; var rebound = 0
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _,key -> if (key == control.key) writes++ }
            val observer = object : RecyclerView.AdapterDataObserver() { override fun onItemRangeChanged(positionStart: Int,itemCount: Int) { rebound += itemCount } }
            preferences.registerOnSharedPreferenceChangeListener(listener); fragment.listView.adapter!!.registerAdapterDataObserver(observer)
            try {
                val time = SystemClock.uptimeMillis()
                touch(slider,MotionEvent.ACTION_DOWN,time,(slider.value-slider.valueFrom)/(slider.valueTo-slider.valueFrom))
                touch(slider,MotionEvent.ACTION_MOVE,time,.55f); idle(); val first = slider.value.toInt()
                assertEquals(first,preferences.getInt(control.key,-1))
                touch(slider,MotionEvent.ACTION_MOVE,time,.85f); idle()
                assertTrue(slider.value.toInt() > first); assertEquals(slider.value.toInt(),preferences.getInt(control.key,-1))
                val count = writes
                repeat(3) { touch(slider,MotionEvent.ACTION_MOVE,time,.85f); idle() }
                touch(slider,MotionEvent.ACTION_UP,time,.85f); idle()
                assertEquals(count,writes); assertEquals(0,rebound); assertFalse(fragment.effectHeader.isChecked)
            } finally {
                preferences.unregisterOnSharedPreferenceChangeListener(listener); fragment.listView.adapter!!.unregisterAdapterDataObserver(observer)
            }
        }
    }
    @Test fun nativeRenderingAt320dpNormalAndLargeFonts() { renderGroups(320,1f); renderGroups(320,1.5f) }
    @Test @Config(qualifiers = "zh-rCN-w411dp-h891dp-port-mdpi")
    fun nativeRenderingAt411dpNormalAndLargeFonts() { renderGroups(411,1f); renderGroups(411,1.5f) }
    private fun renderGroups(width: Int,font: Float) {
        controller?.pause()?.stop()?.destroy(); controller = null; idle()
        this.width = width; RuntimeEnvironment.setFontScale(font)
        try {
            launch()
            p.clarity.forEach { verifyNumeric(row(clarity(),it.key)) }
            p.nonlinear.forEach { verifyNumeric(row(spectrum(),it.key)) }
            verifyNumeric(row(spectrum(),p.OVERSAMPLING))
            capture(clarity(),p.NATURAL_ENABLED,p.naturalQ.key,"natural-$width-${(font*100).toInt()}")
            capture(clarity(),p.DYNAMIC_ENABLED,p.dynamicRelease.key,"dynamic-$width-${(font*100).toInt()}")
            capture(clarity(),p.DEESS_ENABLED,p.deessRelease.key,"deess-$width-${(font*100).toInt()}")
            capture(spectrum(),"high_frequency_nonlinear","high_frequency_oversampling_help","nonlinear-$width-${(font*100).toInt()}")
        } finally {
            controller?.pause()?.stop()?.destroy(); controller = null; idle()
        }
    }
    private fun verifyNumeric(row: View) {
        val number = row.findViewById<TextView>(R.id.seekbar_value)
        val minus = row.findViewById<View>(R.id.bass_decrement); val plus = row.findViewById<View>(R.id.bass_increment)
        val reset = row.findViewById<TextView>(R.id.bass_reset)
        assertTrue(minus.right <= number.left && number.right <= plus.left && plus.right <= reset.left)
        listOf(row.findViewById<TextView>(android.R.id.title),number,reset).forEach { text ->
            val layout = requireNotNull(text.layout)
            repeat(layout.lineCount) {
                assertEquals(0,layout.getEllipsisCount(it))
                assertTrue("${text.text} fits its row",layout.getLineWidth(it) <= text.width-text.compoundPaddingLeft-text.compoundPaddingRight+1f)
            }
            assertTrue(layout.height <= text.height-text.totalPaddingTop-text.totalPaddingBottom)
        }
    }
    @Suppress("RestrictedApi")
    private fun capture(fragment: ViperEffectPreferenceFragment,firstKey: String,lastKey: String,name: String) {
        controller!!.get().show(fragment.effectHeader.key)
        val adapter = fragment.listView.adapter as PreferenceGroupAdapter
        val firstPosition = adapter.getPreferenceAdapterPosition(firstKey)
        val lastPosition = adapter.getPreferenceAdapterPosition(lastKey)
        (fragment.listView.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).scrollToPositionWithOffset(firstPosition,0)
        layout()
        val first = requireNotNull(fragment.listView.findViewHolderForAdapterPosition(firstPosition)).itemView
        val last = requireNotNull(fragment.listView.findViewHolderForAdapterPosition(lastPosition)).itemView
        val root = fragment.requireView() as ViewGroup
        fun bounds(view: View) = Rect(0,0,view.width,view.height).apply { root.offsetDescendantRectToMyCoords(view,this) }
        val top = bounds(first).top; val bottom = bounds(last).bottom
        assertTrue(bottom > top && top >= 0 && bottom <= root.height)
        val bitmap = Bitmap.createBitmap(root.width,bottom-top,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(MaterialColors.getColor(root,com.google.android.material.R.attr.colorSurface))
            root.draw(Canvas(bitmap).apply { translate(0f,-top.toFloat()) })
            val folder = File("build/reports/high-frequency-advanced").apply { mkdirs() }
            File(folder,"$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
    }
}
