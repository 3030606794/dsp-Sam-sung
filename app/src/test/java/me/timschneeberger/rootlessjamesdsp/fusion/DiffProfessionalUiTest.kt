package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.LinearLayoutManager
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
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DiffProfessionalUiTest {
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=DiffController.preferences(app)
    private val p=DiffParameters
    private var controller: ActivityController<Host>?=null
    private var width=320
    private fun fragment()=controller!!.get().supportFragmentManager.findFragmentByTag("diff") as ViperEffectPreferenceFragment
    private fun idle()=shadowOf(Looper.getMainLooper()).idle()
    // Full-band processing has no common filter delay; band-limited cases opt in explicitly.
    private fun snapshot(mode: Int=0)=DiffRuntimeReader.Snapshot(5000,if(mode==0)220000 else 220500,0,0,
        0,0,0,10000,-2500,0,-602,-300,300,-600,Int.MIN_VALUE,-1200,-900,true,0,false,mode)
    private fun runtime(frames: Int=0,snapshot: DiffRuntimeReader.Snapshot?=snapshot(),enabled: Boolean=true) {
        DiffController.publishRuntime(app,listOf(DiffController.Reading("stream",enabled,44100,frames,snapshot))); idle()
    }
    @Before fun prepare() {
        RuntimeEnvironment.setFontScale(1f); prefs.edit().clear().commit(); DiffController.stopAudition(app)
        DiffController.publishRuntime(app,emptyList()); DiffController.publishCapabilities(app,2047); runtime()
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy(); controller=null; idle()
        DiffController.stopAudition(app); DiffController.publishRuntime(app,emptyList()); RuntimeEnvironment.setFontScale(1f)
    }
    private fun launch(expanded: Boolean=true) {
        controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        fragment().effectHeader.setExpanded(expanded); layout()
    }
    private fun layout() {
        repeat(2) { idle(); controller!!.get().findViewById<View>(android.R.id.content).apply {
            measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            layout(0,0,width,2400)
        } }; idle()
    }
    @Suppress("RestrictedApi") private fun row(key: String): View {
        val list=fragment().listView; val adapter=list.adapter as PreferenceGroupAdapter
        val pos=adapter.getPreferenceAdapterPosition(key); assertTrue("Visible row $key",pos>=0)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos,0); layout()
        return requireNotNull(list.findViewHolderForAdapterPosition(pos)).itemView
    }
    private fun number(c: BassParameters.Control)=fragment().findPreference<BassNumericPreference>(c.key)!!
    private fun mode(value: Int) { assertTrue(fragment().findPreference<FieldChoicePreference>(p.MODE)!!.choose(value)); layout() }
    @Test fun titleIndependentSwitchAndCollapsedCompatibilityRetainOriginalIntegerDelay() {
        prefs.edit().putInt(p.legacy.key,17).commit(); launch(false)
        assertEquals("声道差分延时",fragment().effectHeader.title)
        assertFalse(fragment().effectHeader.isExpanded); assertEquals(1,fragment().listView.adapter!!.itemCount)
        row("v_diff").performClick(); layout(); assertTrue(fragment().effectHeader.isExpanded)
        assertFalse(fragment().effectHeader.isChecked); assertEquals(17,number(p.legacy).value)
        assertNull(fragment().findPreference<Preference>(p.delay.key))
        row(p.legacy.key).findViewById<View>(R.id.bass_increment).performClick(); assertEquals(18,prefs.getInt(p.legacy.key,-1))
        row(p.legacy.key).findViewById<View>(R.id.bass_reset).performClick(); assertEquals(5,prefs.getInt(p.legacy.key,-1))
        assertFalse(fragment().effectHeader.isChecked)
        row("v_diff").findViewById<MaterialSwitch>(R.id.switchWidget).performClick(); layout()
        assertTrue(fragment().effectHeader.isChecked); assertTrue(fragment().effectHeader.isExpanded)
    }
    @Test fun preciseControlsAreIndependentAndModeVisibilitySurvivesFoldRebindAndRecreation() {
        prefs.edit().putInt(p.legacy.key,17).commit(); launch(); mode(1)
        assertEquals(500,number(p.delay).value); assertNull(fragment().findPreference<Preference>(p.legacy.key))
        p.professional.forEach { c ->
            val delta=if(c.default==c.max)-1 else 1
            row(c.key).findViewById<View>(if(delta<0)R.id.bass_decrement else R.id.bass_increment).performClick(); idle()
            assertEquals(c.default+delta,number(c).value); assertTrue(number(c).resetToDefault()); assertEquals(c.default,number(c).value)
        }
        assertTrue(fragment().findPreference<FieldChoicePreference>(p.CHANNEL)!!.choose(1))
        fragment().findPreference<SwitchPreferenceCompat>(p.BAND_ENABLED)!!.isChecked=true
        assertFalse(fragment().effectHeader.isChecked)
        repeat(2) { fragment().effectHeader.setExpanded(false); layout(); fragment().effectHeader.setExpanded(true); layout() }
        assertNull(fragment().findPreference<Preference>(p.legacy.key)); assertTrue(number(p.delay).isEnabled)
        mode(0); assertEquals(17,number(p.legacy).value); assertNull(fragment().findPreference<Preference>(p.mix.key))
        mode(1); controller!!.recreate(); layout()
        assertEquals(1,fragment().findPreference<FieldChoicePreference>(p.CHANNEL)!!.value)
        assertTrue(fragment().findPreference<SwitchPreferenceCompat>(p.BAND_ENABLED)!!.isChecked)
        assertFalse(fragment().effectHeader.isChecked)
    }
    @Test fun numericInputHonorsActualPrecisionAndCrossedFrequencyEditsAreRejected() {
        launch(); mode(1)
        row(p.delay.key).findViewById<View>(R.id.seekbar_value).performClick(); idle()
        var dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        var input=dialog.findViewById<EditText>(android.R.id.text1)!!
        for(v in listOf("0.001","20.01","-0.01","NaN")) { input.setText(v); dialog.getButton(-1).performClick(); assertTrue(dialog.isShowing) }
        input.setText("0.01"); dialog.getButton(-1).performClick(); assertFalse(dialog.isShowing); assertEquals(1,number(p.delay).value)
        assertTrue(number(p.low).requestValue(1999)); assertTrue(number(p.high).requestValue(2000)); layout()
        row(p.low.key).findViewById<View>(R.id.bass_increment).performClick(); idle()
        assertEquals(1999,number(p.low).value); assertTrue(ShadowToast.getTextOfLatestToast().contains("先调高上限"))
        row(p.low.key).findViewById<View>(R.id.seekbar_value).performClick(); idle()
        dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog; input=dialog.findViewById<EditText>(android.R.id.text1)!!
        input.setText("2000"); dialog.getButton(-1).performClick(); assertTrue(dialog.isShowing)
        input.setText("150"); dialog.getButton(-1).performClick(); assertFalse(dialog.isShowing)
        assertTrue(number(p.high).resetToDefault()); assertEquals(12000,number(p.high).value)
        assertFalse(fragment().effectHeader.isChecked); assertFalse(prefs.getBoolean(p.BAND_ENABLED,true))
    }
    @Test fun oldDriverUsesCompatibilityAndRetainsProfessionalSettingsUntilCapabilityArrives() {
        prefs.edit().putInt(p.MODE,1).putInt(p.delay.key,125).putInt(p.CHANNEL,1).putBoolean(p.BAND_ENABLED,true).commit()
        DiffController.publishCapabilities(app,1023); launch()
        assertFalse(fragment().findPreference<FieldChoicePreference>(p.MODE)!!.isEnabled)
        assertEquals(0,fragment().findPreference<FieldChoicePreference>(p.MODE)!!.value)
        assertTrue(number(p.legacy).isEnabled); assertNull(fragment().findPreference<Preference>(p.delay.key))
        assertFalse(fragment().findPreference<FieldChoicePreference>("diff_audition")!!.isEnabled)
        assertNotNull(fragment().findPreference<Preference>("diff_driver")); assertEquals(125,prefs.getInt(p.delay.key,-1))
        DiffController.publishCapabilities(app,2047); layout()
        assertEquals(125,number(p.delay).value); assertEquals(1,fragment().findPreference<FieldChoicePreference>(p.CHANNEL)!!.value)
        assertTrue(fragment().findPreference<SwitchPreferenceCompat>(p.BAND_ENABLED)!!.isChecked)
        assertFalse(fragment().effectHeader.isChecked)
    }
    @Test fun corruptImportedTypesAndFrequencyPairRenderExactlyTheCanonicalPacket() {
        prefs.edit().putInt(p.MODE,1).putString(p.delay.key,"5.01").putFloat(p.mix.key,500.5f)
            .putInt(p.low.key,2000).putInt(p.high.key,2000).putString(p.BAND_ENABLED,"true").commit()
        launch(); assertEquals(500,number(p.delay).value); assertEquals(1000,number(p.mix).value)
        val packet=p.packet(prefs.all)
        assertEquals(packet[5],number(p.low).value); assertEquals(packet[6],number(p.high).value)
        assertFalse(fragment().findPreference<SwitchPreferenceCompat>(p.BAND_ENABLED)!!.isChecked)
        prefs.edit().putFloat(p.delay.key,0.5f).commit(); idle(); assertEquals(500,prefs.getInt(p.delay.key,-1))
    }
    private fun touch(slider: Slider,action: Int,time: Long,fraction: Float) {
        assertTrue(slider.isAttachedToWindow && slider.isEnabled && slider.getGlobalVisibleRect(Rect()))
        val event=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,slider.trackSidePadding+slider.trackWidth*fraction,slider.height/2f,0)
        try { assertTrue(slider.dispatchTouchEvent(event)) } finally { event.recycle() }
    }
    @Test fun preciseDraggingPersistsBeforeReleaseWithoutRebindOrDuplicateWrites() {
        launch(); mode(1); val bound=row(p.delay.key); val slider=bound.findViewById<Slider>(R.id.seekbar)
        var writes=0; var rebound=0
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,key -> if(key==p.delay.key)writes++ }
        val observer=object: RecyclerView.AdapterDataObserver() { override fun onItemRangeChanged(positionStart: Int,itemCount: Int) { rebound+=itemCount } }
        prefs.registerOnSharedPreferenceChangeListener(listener); fragment().listView.adapter!!.registerAdapterDataObserver(observer)
        try {
            val start=(slider.value-slider.valueFrom)/(slider.valueTo-slider.valueFrom)
            val firstMove=start+maxOf(.15f,(ViewConfiguration.get(slider.context).scaledTouchSlop+1f)/slider.trackWidth)
            val time=SystemClock.uptimeMillis(); touch(slider,MotionEvent.ACTION_DOWN,time,start); touch(slider,MotionEvent.ACTION_MOVE,time,firstMove); idle()
            val first=number(p.delay).value; assertEquals(first,prefs.getInt(p.delay.key,-1))
            touch(slider,MotionEvent.ACTION_MOVE,time,.85f); idle(); assertTrue(number(p.delay).value>first)
            assertSame(bound,fragment().listView.findContainingItemView(slider))
            val count=writes; repeat(3) { touch(slider,MotionEvent.ACTION_MOVE,time,.85f); idle() }
            touch(slider,MotionEvent.ACTION_UP,time,.85f); idle()
            assertEquals(count,writes); assertEquals(0,rebound); assertFalse(fragment().effectHeader.isChecked)
        } finally { prefs.unregisterOnSharedPreferenceChangeListener(listener); fragment().listView.adapter!!.unregisterAdapterDataObserver(observer) }
    }
    @Test fun telemetryShowsTrueLegacyQuantizationAndDistinctUndefinedIdleAndTransitionStates() {
        prefs.edit().putBoolean("v_diff",true).commit(); launch(); runtime(64)
        fun status()=fragment().findPreference<Preference>("diff_actual")!!.summary.toString()
        assertTrue(status().contains("目标 5.00 ms")); assertTrue(status().contains("4.989 ms／220.000 采样"))
        assertTrue(status().contains("输出峰值 3.00 dBFS")); assertTrue(status().contains("输出相关度 -0.2500"))
        assertTrue(status().contains("单声道合并可能减弱")); assertTrue(status().contains("选频关闭"))
        mode(1); prefs.edit().putBoolean(p.BAND_ENABLED,true).commit(); idle()
        assertTrue("Pending preferences do not pretend to be a confirmed driver target",status().contains("选频关闭"))
        runtime(128,snapshot(1).copy(lowHz=150,highHz=12000,commonSamples=1010,commonUs=22902,switching=true,bandEnabled=true))
        assertTrue(status().contains("5.000 ms／220.500 采样")); assertTrue(status().contains("22.902 ms／1010 采样"))
        assertTrue(status().contains("正在平滑切换")); assertTrue(status().contains("有效频带目标 150–12000 Hz"))
        runtime(192,snapshot(1).copy(inputCorrelation=Int.MIN_VALUE,monoFold=Int.MIN_VALUE,midRms=Int.MIN_VALUE,sideRms=-900))
        assertTrue(status().contains("输入相关度 未定义")); assertTrue(status().contains("−∞（合并后无中央能量）"))
        runtime(256,snapshot(1).copy(monoFold=Int.MIN_VALUE,midRms=Int.MIN_VALUE,sideRms=Int.MIN_VALUE,peak=Int.MIN_VALUE))
        assertTrue(status().contains("未定义（静音）")); assertTrue(status().contains("输出峰值 −∞"))
        runtime(320,snapshot(1).copy(ready=false)); assertTrue(status().contains("等待完整 100 ms"))
        runtime(320); assertTrue(status().contains("当前未处理音频")); assertFalse(status().contains("输出相关度 -0.2500"))
        runtime(384,null); assertTrue(status().contains("等待读数")); assertFalse(status().contains("输出峰值"))
        runtime(448,snapshot(1).copy(bandEnabled=true)); assertTrue(status().contains("作用频带不可用"))
    }
    private fun scrollEvent() {
        android.view.ViewTreeObserver::class.java.getDeclaredMethod("dispatchOnScrollChanged").apply { isAccessible=true }
            .invoke(fragment().requireView().viewTreeObserver); idle()
    }
    @Test fun localAuditionClearsOnWholeCardExitFoldModuleOffPauseAndRebuildWithoutPersistentKeys() {
        prefs.edit().putBoolean("v_diff",true).commit(); launch()
        fun start(value: Int=2) { assertTrue(fragment().findPreference<FieldChoicePreference>("diff_audition")!!.choose(value)); idle(); assertEquals(value,DiffController.audition) }
        start(1); start(); assertTrue(prefs.all.keys.none { it.contains("audition") })
        fragment().effectHeader.setExpanded(false); idle(); assertEquals(0,DiffController.audition)
        fragment().effectHeader.setExpanded(true); layout(); start()
        val viewport=controller!!.get().findViewById<ViewGroup>(android.R.id.content)
        viewport.scrollTo(0,viewport.height+1); assertFalse(fragment().requireView().getGlobalVisibleRect(Rect()))
        scrollEvent(); assertEquals(0,DiffController.audition); viewport.scrollTo(0,0); layout(); start()
        prefs.edit().putBoolean("v_diff",false).commit(); idle(); assertEquals(0,DiffController.audition)
        prefs.edit().putBoolean("v_diff",true).commit(); idle(); start()
        controller!!.recreate(); layout(); assertEquals(0,DiffController.audition)
        start(); controller!!.pause(); idle(); assertEquals(0,DiffController.audition); controller!!.resume()
    }
    @Test fun nativeRenderingAt320dpNormalAndLargeFonts() { renderGroups(320,1f); renderGroups(320,1.5f) }
    @Test @Config(qualifiers="zh-rCN-w411dp-h891dp-port-mdpi")
    fun nativeRenderingAt411dpNormalAndLargeFonts() { renderGroups(411,1f); renderGroups(411,1.5f) }
    private fun renderGroups(width: Int,font: Float) {
        this.width=width; RuntimeEnvironment.setFontScale(font); prefs.edit().clear().putBoolean("v_diff",true).commit()
        try {
            launch(); runtime(64); runtime(128)
            verifyText(row("v_diff").findViewById(android.R.id.title)); verifyNumeric(row(p.legacy.key))
            capture("v_diff",p.legacy.key,"legacy-$width-${(font*100).toInt()}")
            mode(1); p.professional.forEach { verifyNumeric(row(it.key)) }
            verifyText(row("diff_mix_help").findViewById(android.R.id.summary))
            capture(p.MODE,"diff_mix_help","precise-$width-${(font*100).toInt()}")
            verifyText(row(p.BAND_ENABLED).findViewById(android.R.id.summary))
            capture(p.BAND_ENABLED,p.high.key,"band-$width-${(font*100).toInt()}")
            runtime(192,snapshot(1)); runtime(256,snapshot(1))
            val monitor=fragment().findPreference<Preference>("diff_actual")!!.summary.toString()
            assertTrue(monitor.contains("选频关闭")); assertTrue(monitor.contains("公共滤波延时 0.000 ms／0 采样"))
            listOf("diff_help","diff_actual").forEach { verifyText(row(it).findViewById(android.R.id.summary)) }
            capture("diff_audition","diff_actual","monitor-$width-${(font*100).toInt()}")
        } finally { controller?.pause()?.stop()?.destroy(); controller=null; idle() }
    }
    private fun verifyText(text: TextView) {
        val layout=requireNotNull(text.layout)
        repeat(layout.lineCount) { assertEquals(0,layout.getEllipsisCount(it)); assertTrue("${text.text} fits",layout.getLineMax(it)<=text.width-text.compoundPaddingLeft-text.compoundPaddingRight+1f) }
        assertTrue(layout.height<=text.height-text.totalPaddingTop-text.totalPaddingBottom)
        assertTrue(layout.lineCount>0 && layout.getLineEnd(layout.lineCount-1)>=text.text.length)
    }
    private fun verifyNumeric(row: View) {
        val value=row.findViewById<TextView>(R.id.seekbar_value); val minus=row.findViewById<View>(R.id.bass_decrement)
        val plus=row.findViewById<View>(R.id.bass_increment); val reset=row.findViewById<TextView>(R.id.bass_reset)
        assertTrue(minus.right<=value.left && value.right<=plus.left && plus.right<=reset.left)
        listOf(row.findViewById<TextView>(android.R.id.title),value,reset).forEach(::verifyText)
    }
    @Suppress("RestrictedApi") private fun capture(first: String,last: String,name: String) {
        val list=fragment().listView; val adapter=list.adapter as PreferenceGroupAdapter
        val start=adapter.getPreferenceAdapterPosition(first); val end=adapter.getPreferenceAdapterPosition(last)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(start,0); layout()
        val root=fragment().requireView() as ViewGroup
        fun bounds(i: Int)=requireNotNull(list.findViewHolderForAdapterPosition(i)).itemView.let { view -> Rect(0,0,view.width,view.height).apply { root.offsetDescendantRectToMyCoords(view,this) } }
        val top=bounds(start).top; val bottom=bounds(end).bottom; assertTrue(top>=0 && bottom>top && bottom<=root.height)
        val bitmap=Bitmap.createBitmap(root.width,bottom-top,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(MaterialColors.getColor(root,com.google.android.material.R.attr.colorSurface)); root.draw(Canvas(bitmap).apply { translate(0f,-top.toFloat()) })
            val folder=File("build/reports/diff-professional").apply { mkdirs() }
            File(folder,"$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
    }
    class Host: FragmentActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP); super.onCreate(state)
            setContentView(FrameLayout(this).apply { addView(MaterialCardView(context).apply {
                addView(FragmentContainerView(context).apply { id=DIFF_ID },ViewGroup.LayoutParams(-1,-1))
            },FrameLayout.LayoutParams(-1,-1)) })
            if(state==null)supportFragmentManager.beginTransaction().add(DIFF_ID,ViperEffectPreferenceFragment.newInstance("v_diff"),"diff").commitNow()
        }
        companion object { const val DIFF_ID=0x745133 }
    }
}
