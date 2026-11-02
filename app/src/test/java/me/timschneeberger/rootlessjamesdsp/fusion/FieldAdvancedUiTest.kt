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
import androidx.recyclerview.widget.LinearLayoutManager
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FieldAdvancedUiTest {
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=FieldController.preferences(app)
    private val p=FieldAdvancedParameters
    private var controller: ActivityController<FieldProfessionalUiTest.Host>?=null
    private var width=320
    private fun fragment()=controller!!.get().supportFragmentManager.findFragmentByTag("field") as ViperEffectPreferenceFragment
    private fun idle()=shadowOf(Looper.getMainLooper()).idle()
    private fun extra()=FieldAdvancedParameters.Runtime(10000,125,250,375,300,13230,1200,849,1697,-600,-1200,-301,10000,true)
    private fun runtime(frames: Int=0, data: FieldAdvancedParameters.Runtime=extra(), actual: Int=0,
        mid: Int=-600,side: Int=-1200,ready: Boolean=true) {
        FieldController.publishRuntime(app,listOf(FieldController.Reading("stream",true,44100,frames,listOf(150,16000,120),
            5000,mid,side,300,actual,ready,advanced=data))); idle()
    }
    @Before fun prepare() {
        RuntimeEnvironment.setFontScale(1f); prefs.edit().clear().commit(); FieldController.stopAudition(app)
        FieldController.publishRuntime(app,emptyList()); FieldController.publishCapabilities(app,1023); runtime()
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy(); controller=null; idle()
        FieldController.stopAudition(app); FieldController.publishRuntime(app,emptyList()); RuntimeEnvironment.setFontScale(1f)
    }
    private fun launch() { controller=Robolectric.buildActivity(FieldProfessionalUiTest.Host::class.java).setup().visible(); fragment().effectHeader.setExpanded(true); layout() }
    private fun layout() {
        repeat(2) { idle(); controller!!.get().findViewById<View>(android.R.id.content).apply {
            measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            layout(0,0,width,2400)
        } }; idle()
    }
    @Suppress("RestrictedApi") private fun row(key: String): View {
        val list=fragment().listView; val adapter=list.adapter as PreferenceGroupAdapter
        val index=adapter.getPreferenceAdapterPosition(key); assertTrue("Visible row $key",index>=0)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index,0); layout()
        return requireNotNull(list.findViewHolderForAdapterPosition(index)).itemView
    }
    private fun number(control: BassParameters.Control)=fragment().findPreference<BassNumericPreference>(control.key)!!
    @Test fun allNineteenNumbersStepAndResetWhileFiveSubswitchesAndParentStayOff() {
        launch()
        p.all.forEach { control ->
            val delta=if (control.default==control.max) -1 else 1
            assertTrue(number(control).step(delta))
            assertEquals(if (control==p.diffusionStages) 4 else control.default+delta,prefs.getInt(control.key,-1))
            assertTrue(number(control).resetToDefault()); assertEquals(control.default,prefs.getInt(control.key,-1))
        }
        assertFalse(fragment().effectHeader.isChecked)
        p.switches.forEach { assertFalse(prefs.getBoolean(it,true)) }
        p.switches.forEach { fragment().findPreference<SwitchPreferenceCompat>(it)!!.isChecked=true }
        assertFalse(fragment().effectHeader.isChecked)
        fragment().effectHeader.setExpanded(false); layout(); fragment().effectHeader.setExpanded(true); layout()
        p.switches.forEach { assertTrue(fragment().findPreference<SwitchPreferenceCompat>(it)!!.isChecked) }
    }
    @Test fun firstGenerationDriverDisablesNewRowsAndDifferenceButKeepsSavedValues() {
        prefs.edit().putInt(p.delayTime.key,37).putBoolean(p.DELAY_ENABLED,true).putBoolean("v_field",true).commit()
        FieldController.publishCapabilities(app,511); launch()
        p.all.forEach { assertFalse(number(it).isEnabled); assertFalse(number(it).step(1)) }
        assertTrue(number(FieldParameters.legacyWidth).isEnabled)
        assertFalse(fragment().findPreference<FieldChoicePreference>("field_audition")!!.choose(3))
        assertTrue(fragment().findPreference<FieldChoicePreference>("field_audition")!!.choose(1)); idle()
        assertEquals(1,FieldController.audition)
        assertEquals(37,prefs.getInt(p.delayTime.key,-1)); assertTrue(prefs.getBoolean(p.DELAY_ENABLED,false))
        FieldController.publishCapabilities(app,1023); layout()
        p.all.forEach { assertTrue(number(it).isEnabled) }
        assertTrue(fragment().findPreference<FieldChoicePreference>("field_audition")!!.choose(3)); idle(); assertEquals(4,FieldController.audition)
    }
    @Test fun discreteStagesRejectThreeAndSupportButtonsTypingDraggingAndExternalRepair() {
        launch(); val stages=number(p.diffusionStages)
        assertTrue(stages.step(1)); assertEquals(4,stages.value); assertTrue(stages.step(-1)); assertEquals(2,stages.value)
        assertTrue(stages.step(-1)); assertEquals(1,stages.value); assertTrue(stages.resetToDefault()); assertEquals(2,stages.value)
        row(p.diffusionStages.key).findViewById<View>(R.id.seekbar_value).performClick(); idle()
        val dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val input=dialog.findViewById<EditText>(android.R.id.text1)!!
        for (value in listOf("3","1.5","NaN","0","5")) { input.setText(value); dialog.getButton(-1).performClick(); assertTrue(dialog.isShowing) }
        input.setText("4"); dialog.getButton(-1).performClick(); assertFalse(dialog.isShowing); assertEquals(4,stages.value)
        stages.resetToDefault(); layout()
        val slider=row(p.diffusionStages.key).findViewById<Slider>(R.id.seekbar); val time=SystemClock.uptimeMillis()
        touch(slider,MotionEvent.ACTION_DOWN,time,.5f); touch(slider,MotionEvent.ACTION_MOVE,time,.95f); idle(); assertEquals(4,stages.value)
        touch(slider,MotionEvent.ACTION_MOVE,time,.05f); touch(slider,MotionEvent.ACTION_UP,time,.05f); idle(); assertEquals(1,stages.value)
        prefs.edit().putFloat(p.diffusionStages.key,3.5f).commit(); layout()
        assertEquals(2,stages.value); assertEquals(2,prefs.getInt(p.diffusionStages.key,-1))
        assertEquals(1f,row(p.diffusionStages.key).findViewById<Slider>(R.id.seekbar).value,0f)
        prefs.edit().putBoolean(p.DIFFUSION_ENABLED,true).commit(); idle()
        assertTrue(fragment().findPreference<SwitchPreferenceCompat>(p.DIFFUSION_ENABLED)!!.isChecked)
        prefs.edit().putString(p.DIFFUSION_ENABLED,"true").commit(); idle()
        assertFalse(prefs.getBoolean(p.DIFFUSION_ENABLED,true))
        assertFalse(fragment().findPreference<SwitchPreferenceCompat>(p.DIFFUSION_ENABLED)!!.isChecked)
        assertFalse(fragment().effectHeader.isChecked)
    }
    private fun touch(slider: Slider, action: Int, time: Long, fraction: Float) {
        val event=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,slider.trackSidePadding+slider.trackWidth*fraction,slider.height/2f,0)
        try { assertTrue(slider.dispatchTouchEvent(event)) } finally { event.recycle() }
    }
    @Test fun continuousDraggingWritesEachChangeWithoutRebindingOrDuplicates() {
        launch(); val slider=row(p.delayTime.key).findViewById<Slider>(R.id.seekbar)
        var writes=0; var rebound=0
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,key -> if (key==p.delayTime.key) writes++ }
        val observer=object : RecyclerView.AdapterDataObserver() { override fun onItemRangeChanged(positionStart: Int,itemCount: Int) { rebound+=itemCount } }
        prefs.registerOnSharedPreferenceChangeListener(listener); fragment().listView.adapter!!.registerAdapterDataObserver(observer)
        try {
            val time=SystemClock.uptimeMillis()
            touch(slider,MotionEvent.ACTION_DOWN,time,.1f); touch(slider,MotionEvent.ACTION_MOVE,time,.3f); idle()
            val first=number(p.delayTime).value; assertEquals(first,prefs.getInt(p.delayTime.key,-1))
            touch(slider,MotionEvent.ACTION_MOVE,time,.7f); idle(); assertTrue(number(p.delayTime).value>first)
            val count=writes; repeat(3) { touch(slider,MotionEvent.ACTION_MOVE,time,.7f); idle() }; touch(slider,MotionEvent.ACTION_UP,time,.7f); idle()
            assertEquals(count,writes); assertEquals(0,rebound); assertFalse(fragment().effectHeader.isChecked)
        } finally { prefs.unregisterOnSharedPreferenceChangeListener(listener); fragment().listView.adapter!!.unregisterAdapterDataObserver(observer) }
    }
    @Test fun actualFractionalDelayAndMetersNeverPretendInactiveOrUndefinedValuesAreZero() {
        prefs.edit().putBoolean("v_field",true).putInt(FieldParameters.MODE,1).commit(); launch(); runtime(64)
        fun status()=fragment().findPreference<Preference>("field_advanced_actual")!!.summary.toString()
        assertTrue(status().contains("微延时关闭")); assertTrue(status().contains("0.30 ms／13.230 采样"))
        assertTrue(status().contains("Side 动态衰减 关闭")); assertTrue(status().contains("单声道折叠电平差 -3.01 dB"))
        prefs.edit().putBoolean(p.DYNAMIC_ENABLED,true).putBoolean(p.GUARD_ENABLED,true).putBoolean(p.TRANSIENT_ENABLED,true).commit(); idle()
        assertTrue(status().contains("Side 动态衰减 1.25 dB")); assertTrue(status().contains("兼容回收 25.0%"))
        assertTrue(status().contains("瞬态保护 37.5%")); assertEquals(1000,number(FieldParameters.width).value)
        runtime(128,extra().copy(inputCorrelation=Int.MIN_VALUE,inputMid=Int.MIN_VALUE,monoFoldDifference=Int.MIN_VALUE),mid=Int.MIN_VALUE,side=-600)
        assertTrue(status().contains("输入相关度 未定义")); assertTrue(status().contains("−∞（合并后无中央能量）"))
        runtime(192,extra().copy(inputCorrelation=Int.MIN_VALUE,monoFoldDifference=Int.MIN_VALUE),mid=Int.MIN_VALUE,side=Int.MIN_VALUE)
        assertTrue(status().contains("未定义（静音或无有效能量）"))
        runtime(256,extra().copy(ready=false)); assertTrue(status().contains("等待完整 100 ms"))
        runtime(256); assertTrue(status().contains("当前未处理音频")); assertFalse(status().contains("衰减 1.25"))
        runtime(320,extra().copy(diffusionCenter=0,diffusionLow=0,diffusionHigh=0)); assertTrue(status().contains("频点不可用"))
    }
    @Test fun differenceChoiceIsTransientAndClearsOnFoldModulePauseAndRebuild() {
        prefs.edit().putBoolean("v_field",true).commit(); launch()
        fun start() { assertTrue(fragment().findPreference<FieldChoicePreference>("field_audition")!!.choose(3)); idle(); assertEquals(4,FieldController.audition) }
        start(); assertTrue(prefs.all.keys.none { it.contains("audition") })
        fragment().effectHeader.setExpanded(false); idle(); assertEquals(0,FieldController.audition)
        fragment().effectHeader.setExpanded(true); layout(); start()
        prefs.edit().putBoolean("v_field",false).commit(); idle(); assertEquals(0,FieldController.audition)
        prefs.edit().putBoolean("v_field",true).commit(); idle(); start()
        controller!!.recreate(); layout(); assertEquals(0,FieldController.audition)
        start(); controller!!.pause(); idle(); assertEquals(0,FieldController.audition); controller!!.resume()
    }
    @Test fun nativeRenderingAt320dpNormalAndLargeFonts() { renderGroups(320,1f); renderGroups(320,1.5f) }
    @Test @Config(qualifiers="zh-rCN-w411dp-h891dp-port-mdpi")
    fun nativeRenderingAt411dpNormalAndLargeFonts() { renderGroups(411,1f); renderGroups(411,1.5f) }
    private fun renderGroups(width: Int,font: Float) {
        this.width=width; RuntimeEnvironment.setFontScale(font)
        prefs.edit().putBoolean("v_field",true).commit()
        try {
            launch(); runtime(64); runtime(128)
            p.all.forEach { verifyNumeric(row(it.key)) }
            listOf(Triple("dynamic",p.DYNAMIC_ENABLED,p.dynamic.last().key),Triple("guard",p.GUARD_ENABLED,p.guard.last().key),
                Triple("transient",p.TRANSIENT_ENABLED,p.transient.last().key),Triple("delay",p.DELAY_ENABLED,p.delay.last().key),
                Triple("diffusion",p.DIFFUSION_ENABLED,p.diffusion.last().key)).forEach { (name,first,last) ->
                verifyText(row(first).findViewById(android.R.id.summary)); capture(first,last,"$name-$width-${(font*100).toInt()}")
            }
            verifyText(row("field_advanced_actual").findViewById(android.R.id.summary))
            capture("field_advanced_actual","field_advanced_actual","monitor-$width-${(font*100).toInt()}")
        } finally { controller?.pause()?.stop()?.destroy(); controller=null; idle() }
    }
    private fun verifyText(text: TextView) {
        val layout=requireNotNull(text.layout)
        repeat(layout.lineCount) { assertEquals(0,layout.getEllipsisCount(it)); assertTrue("${text.text} fits",layout.getLineMax(it)<=text.width-text.compoundPaddingLeft-text.compoundPaddingRight+1f) }
        assertTrue(layout.height<=text.height-text.totalPaddingTop-text.totalPaddingBottom)
        assertTrue(layout.lineCount>0 && layout.getLineEnd(layout.lineCount-1)>=text.text.length)
    }
    private fun verifyNumeric(row: View) {
        val number=row.findViewById<TextView>(R.id.seekbar_value); val minus=row.findViewById<View>(R.id.bass_decrement)
        val plus=row.findViewById<View>(R.id.bass_increment); val reset=row.findViewById<TextView>(R.id.bass_reset)
        assertTrue(minus.right<=number.left && number.right<=plus.left && plus.right<=reset.left)
        listOf(row.findViewById<TextView>(android.R.id.title),number,reset).forEach(::verifyText)
    }
    @Suppress("RestrictedApi") private fun capture(first: String,last: String,name: String) {
        val list=fragment().listView; val adapter=list.adapter as PreferenceGroupAdapter
        val start=adapter.getPreferenceAdapterPosition(first); val end=adapter.getPreferenceAdapterPosition(last)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(start,0); layout()
        val root=fragment().requireView() as ViewGroup
        fun bounds(index: Int)=requireNotNull(list.findViewHolderForAdapterPosition(index)).itemView.let { view -> Rect(0,0,view.width,view.height).apply { root.offsetDescendantRectToMyCoords(view,this) } }
        val top=bounds(start).top; val bottom=bounds(end).bottom; assertTrue(top>=0 && bottom>top && bottom<=root.height)
        val bitmap=Bitmap.createBitmap(root.width,bottom-top,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(MaterialColors.getColor(root,com.google.android.material.R.attr.colorSurface)); root.draw(Canvas(bitmap).apply { translate(0f,-top.toFloat()) })
            val folder=File("build/reports/field-advanced").apply { mkdirs() }
            File(folder,"$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
    }
}
