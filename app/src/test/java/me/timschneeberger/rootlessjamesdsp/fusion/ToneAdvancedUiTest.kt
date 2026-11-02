package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fragment.PreferenceGroupFragment
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.preference.CompanderPreference
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSeekbarPreference
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
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
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

open class ToneAdvancedUiFixture {
    protected val app: Application get() = RuntimeEnvironment.getApplication()
    protected val p = ToneAdvancedParameters
    protected val c = ToneAdvancedController
    protected val prefs get() = c.preferences(app)
    protected var controller: ActivityController<Host>? = null
    protected var width = 320
    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()
    protected fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("tone") as TonePreferenceFragment
    protected fun group() = fragment().findPreference<SwitchPreferenceGroup>(p.ENABLED)!!
    @Before fun prepare() {
        stopKoin(); val preferences = Preferences(app); startKoin { modules(module { single { preferences.App() } }) }
        RuntimeEnvironment.setFontScale(1f); prefs.edit().clear().commit(); ReverbController.preferences(app).edit().clear().commit()
        c.stopAudition(app, clearSnapshots = true); c.publishRuntime(app, emptyList()); c.publishCapabilities(app, 1)
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy(); controller = null; idle()
        c.stopAudition(app, clearSnapshots = true); c.publishRuntime(app, emptyList()); RuntimeEnvironment.setFontScale(1f); stopKoin()
    }
    protected fun launch(expanded: Boolean = true) {
        controller = Robolectric.buildActivity(Host::class.java).setup().visible(); group().setExpanded(expanded); layout()
    }
    protected fun layout() {
        repeat(2) { idle(); controller!!.get().findViewById<View>(android.R.id.content).apply {
            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            layout(0, 0, width, 2400)
        } }; idle()
    }
    @Suppress("RestrictedApi") protected fun row(key: String): View {
        val list = fragment().listView; val adapter = list.adapter as PreferenceGroupAdapter
        val index = adapter.getPreferenceAdapterPosition(key); assertTrue("Visible row $key", index >= 0)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, 0); layout()
        return requireNotNull(list.findViewHolderForAdapterPosition(index)).itemView
    }
    protected fun professional(on: Boolean) {
        val pref = fragment().findPreference<SwitchPreferenceCompat>(p.PROFESSIONAL)!!
        assertTrue(pref.callChangeListener(on)); pref.isChecked = on; layout()
    }
    protected fun number(control:BassParameters.Control)=fragment().findPreference<BassNumericPreference>(control.key)!!
    protected fun optional(on:Boolean=true) {val e=prefs.edit();p.switches.filterNot {it==p.PROFESSIONAL}.forEach {e.putBoolean(it,on)};e.commit();layout()}
    protected fun response(epoch:Int=2,rate:Int=48000):ToneAdvancedParameters.Response {val v=IntArray(200).apply {this[0]=1;this[1]=epoch;this[2]=rate;this[3]=1;this[4]=32;for(i in 0 until 32){this[8+i*6]=20+i*500;this[9+i*6]=-137+i*7;this[10+i*6]=-317+i*6;this[13+i*6]=1}};return requireNotNull(p.decodeResponse(v))}
    protected fun reading(frames:Int,mode:Int=c.audition,stage:Int=0,ready:Boolean=true,responseRate:Int=48000) {val v=IntArray(64).apply {this[0]=1;this[1]=2;this[2]=48000;this[3]=1;this[4]=1;this[5]=mode;this[7]=stage;this[11]=c.epoch;this[12]=1;this[13]=if(ready)1 else 0;this[14]=100;this[15]=1;this[16]=-631;this[17]=-688;this[18]=-427;this[19]=-455;this[20]=-1337;this[21]=-1413;this[22]=-1137;this[23]=-1213;this[24]=3;this[25]=1;this[26]=1;this[27]=-317;this[28]=-29;this[29]=137;this[30]=-173;this[31]=2000;this[32]=317;this[33]=-29;this[34]=137;this[35]=73;this[36]=137;this[37]=23;this[38]=73;this[39]=-800;this[40]=1;for(i in 0 until 10){this[41+i]=137+i*3;this[51+i]=95+i*500}};c.publishRuntime(app,listOf(ToneAdvancedController.Reading(this,true,frames,96000,p.decode(v),response=response(rate=responseRate))));idle()}
    class Host: FragmentActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP);super.onCreate(state)
            setContentView(FragmentContainerView(this).apply {id=R.id.card_compressor})
            if(state==null) supportFragmentManager.beginTransaction().add(R.id.card_compressor,TonePreferenceFragment(),"tone").commitNow()
        }
    }
}


@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class ToneAdvancedUiTest:ToneAdvancedUiFixture() {
    @Test fun originalTenThreeRangesAndPowerFoldingRemainWithDefaultGroupsOff(){launch(false);assertEquals(1,fragment().listView.adapter!!.itemCount);row(p.ENABLED).performClick();layout();assertTrue(group().isExpanded);assertFalse(group().isChecked);assertFalse(p.moduleEnabled(prefs.all));assertEquals(listOf(20,40,100),ToneController.RANGES);p.baseKeys.forEach {assertNotNull(fragment().findPreference<ToneSliderPreference>(it))};professional(true);p.numbers.forEach {assertFalse(number(it).isEnabled)}}
    @Test fun coreTakeoverGreysTenAndRangeThenRestoresExactFoundation(){prefs.edit().putString(p.BASE_RANGE,"40").putInt(p.baseKeys[0],37).commit();launch();professional(true);prefs.edit().putBoolean(p.SHAPE_ON,true).putBoolean(p.DYNAMIC_ON,true).commit();layout();assertTrue(fragment().findPreference<ToneSliderPreference>(p.baseKeys[0])!!.isEnabled);assertTrue(fragment().findPreference<ToneRangePreference>(p.BASE_RANGE)!!.isEnabled);prefs.edit().putBoolean(p.CORE_ON,true).commit();layout();p.baseKeys.forEach {val row=fragment().findPreference<ToneSliderPreference>(it)!!;assertFalse(row.isEnabled);assertFalse(row.callChangeListener(0))};val range=fragment().findPreference<ToneRangePreference>(p.BASE_RANGE)!!;assertFalse(range.selectRange(20));assertEquals(37,prefs.getInt(p.baseKeys[0],0));professional(false);assertTrue(range.isEnabled);p.baseKeys.forEach {assertTrue(fragment().findPreference<ToneSliderPreference>(it)!!.isEnabled)};assertEquals("40",prefs.getString(p.BASE_RANGE,null));val saved=p.capture(prefs.all).copy(baseRange="20",base=List(10){13},professional=true,coreOn=true);p.apply(prefs,saved);layout();assertEquals(saved,p.capture(prefs.all));assertEquals(20,range.range);p.baseKeys.forEach {assertEquals(13,fragment().findPreference<ToneSliderPreference>(it)!!.value)};assertFalse(range.selectRange(100))}
    @Test fun allFortyNineNumbersAcceptInputPlusMinusAndCurrentBasisReset(){prefs.edit().putInt(p.baseKeys[0],37).commit();launch();professional(true);optional();for(control in p.numbers){val n=when(control){p.output,p.headroom->-29;p.mix->317;p.weakFloor->-731;in p.slope->control.default-2;else->control.default+2};assertTrue(control.key,number(control).isEnabled);row(control.key).findViewById<View>(R.id.seekbar_value).performClick();idle();val dialog=ShadowDialog.getLatestDialog() as AlertDialog;dialog.findViewById<EditText>(android.R.id.text1)!!.setText(control.number(n));dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();layout();assertEquals(control.key,n,number(control).value);row(control.key).findViewById<View>(R.id.bass_increment).performClick();idle();assertEquals(n+1,p.value(prefs.all,control));row(control.key).findViewById<View>(R.id.bass_decrement).performClick();idle();assertEquals(n,p.value(prefs.all,control));row(control.key).findViewById<View>(R.id.bass_reset).performClick();idle();assertEquals(control.default,p.value(prefs.all,control))};assertEquals(37,prefs.getInt(p.baseKeys[0],0));assertFalse(p.moduleEnabled(prefs.all))}
    @Test fun disablingGroupsGreysNumericInputsAndRestoresSavedValues(){launch();professional(true);optional();number(p.delta[0]).requestValue(137);for((flag,controls)in listOf(p.CORE_ON to p.coreNumbers,p.SHAPE_ON to p.shapeNumbers,p.DYNAMIC_ON to p.dynamicNumbers,p.MIX_ON to listOf(p.mix),p.OUTPUT_ON to listOf(p.output),p.HEADROOM_ON to listOf(p.headroom))){val before=p.capture(prefs.all);prefs.edit().putBoolean(flag,false).commit();layout();for(control in controls){assertFalse(control.key,number(control).isEnabled);assertFalse(number(control).step(1));assertFalse(number(control).resetToDefault());assertEquals(.38f,row(control.key).findViewById<TextView>(R.id.seekbar_value).alpha,.001f)};prefs.edit().putBoolean(flag,true).commit();layout();assertEquals(before,p.capture(prefs.all));controls.forEach {assertTrue(number(it).isEnabled)}}}
    @Test fun localZoomChangesOnlyPresentationRetainingOutOfWindowAndOffGridValues(){launch();professional(true);optional();number(p.delta[0]).requestValue(1371);number(p.delta[1]).requestValue(-317);layout();val before=prefs.all;val window=fragment().findPreference<FieldChoicePreference>("tone_pro_local_window")!!;for(i in 0..3){assertTrue(window.choose(i));layout();assertEquals(before,prefs.all);assertEquals(1371,number(p.delta[0]).value);assertTrue(1371 in number(p.delta[0]).sliderRange!!);assertEquals(-317,number(p.delta[1]).value)};assertFalse(prefs.contains("tone_pro_local_window"))}
    @Test fun originalRangeStillTruncatesOnlyWhenExplicitlySelected(){prefs.edit().putInt(p.baseKeys[0],80).commit();launch();val range=fragment().findPreference<ToneRangePreference>(p.BASE_RANGE)!!;assertTrue(range.selectRange(20));layout();assertEquals(20,prefs.getInt(p.baseKeys[0],0));assertEquals("20",prefs.getString(p.BASE_RANGE,null));professional(true);optional();number(p.delta[0]).requestValue(137);assertEquals(20,prefs.getInt(p.baseKeys[0],0))}
    @Test fun staleNumericDialogCannotWriteAfterGroupOrFoundationChangedAndReturned(){launch();professional(true);optional();number(p.delta[0]).requestValue(137);for(key in listOf(p.CORE_ON,p.baseKeys[0])){row(p.delta[0].key).findViewById<View>(R.id.seekbar_value).performClick();idle();val dialog=ShadowDialog.getLatestDialog() as AlertDialog;dialog.findViewById<EditText>(android.R.id.text1)!!.setText("73.1");if(key==p.CORE_ON){prefs.edit().putBoolean(key,false).commit();layout();prefs.edit().putBoolean(key,true).commit()}else {prefs.edit().putInt(key,13).commit();layout();prefs.edit().putInt(key,0).commit()};layout();dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertEquals(137,p.value(prefs.all,p.delta[0]));assertTrue(dialog.isShowing);dialog.dismiss()}}
    @Test fun fastSlowValidationAndWeakSignalDependOnEffectiveGroup(){launch();professional(true);optional();assertFalse(number(p.bassSlow).requestValue(5));assertTrue(number(p.bassFast).requestValue(49));assertTrue(number(p.bassSlow).requestValue(5));assertFalse(number(p.bassFast).requestValue(50));assertEquals(49,p.value(prefs.all,p.bassFast));prefs.edit().putBoolean(p.WEAK_ON,false).commit();layout();assertFalse(number(p.weakFloor).isEnabled);prefs.edit().putBoolean(p.WEAK_ON,true).commit();layout();assertTrue(number(p.weakFloor).isEnabled)}
    @Test fun originalAllZeroResetsFineSettingsAndToolsButKeepsPowerAndFold(){launch();professional(true);optional();prefs.edit().putBoolean(p.ENABLED,true).putInt(p.baseKeys[0],37).putInt(p.delta[0].key,137).commit();reading(0);reading(512);layout();row("tone_pro_save_a").performClick();row("fusion_tone_reset").performClick();layout();assertEquals(p.defaults(),p.capture(prefs.all));assertTrue(p.moduleEnabled(prefs.all));assertTrue(group().isExpanded);assertFalse(c.hasComparison(0));assertEquals(0,c.audition)}
    @Test fun foldAndPauseClearToolsWithoutChangingPowerAndCorrections(){launch();professional(true);optional();prefs.edit().putBoolean(p.ENABLED,true).commit();reading(0);reading(512);layout();row("tone_pro_save_a").performClick();assertTrue(fragment().findPreference<FieldChoicePreference>("tone_pro_audition")!!.choose(2));val before=p.capture(prefs.all);group().setExpanded(false);layout();assertFalse(c.hasComparison(0));assertEquals(0,c.audition);assertEquals(before,p.capture(prefs.all));assertTrue(p.moduleEnabled(prefs.all));group().setExpanded(true);layout();reading(1024);row("tone_pro_save_a").performClick();controller!!.pause();idle();assertFalse(c.hasComparison(0))}
    @Test fun diagnosticsKeepStaticResponseAndDynamicGainDistinctAndOtherModulesUnchanged(){val other=DynamicController.preferences(app);other.edit().clear().putInt("sentinel",73).commit();val before=other.all;launch();professional(true);optional();prefs.edit().putBoolean(p.ENABLED,true).commit();reading(0);reading(512);layout();val text=fragment().findPreference<Preference>("tone_pro_actual")!!.summary.toString();assertTrue(text.contains("48000 Hz"));assertTrue(text.contains("96000 Hz"));assertTrue(text.contains("自动余量 -3.17 dB"));assertTrue(text.contains("低音动态增益 1.37 dB"));assertTrue(text.contains("瞬态动态增益 -1.73 dB"));assertEquals(before,other.all);val graph=fragment().findPreference<Preference>("tone_pro_response_graph")!!;assertTrue(graph.summary.toString().contains("不是实测频响"));val view=row(graph.key).findViewById<ToneAdvancedResponseView>(R.id.tone_advanced_response_chart);assertTrue(view.onTouchEvent(MotionEvent.obtain(0,1,MotionEvent.ACTION_UP,view.width*.5f,30f,0)));idle();assertTrue(graph.summary.toString().contains("Hz；静态滤波"));reading(1024,responseRate=44100);layout();assertTrue(graph.summary.toString().contains("等待配套驱动"))}
    @Test fun meterAndResponseTogglesPreserveMatchedListeningAndNumericRows(){launch();professional(true);optional();prefs.edit().putBoolean(p.ENABLED,true).commit();reading(0);reading(512);layout();row("tone_pro_save_a").performClick();row("tone_pro_save_b").performClick();assertTrue(fragment().findPreference<FieldChoicePreference>("tone_pro_audition")!!.choose(6));reading(1024,6,3);assertTrue(c.setMatch(app,true));assertTrue(fragment().findPreference<FieldChoicePreference>("tone_pro_audition")!!.choose(4));c.sync(app,1,this){_,_->true};val epoch=c.epoch;val numeric=number(p.amount);for(key in listOf(p.METERS,p.RESPONSE))for(on in listOf(false,true)){fragment().findPreference<SwitchPreferenceCompat>(key)!!.isChecked=on;layout();c.sync(app,1,this){_,_->true};assertEquals(4,c.audition);assertTrue(c.match);assertEquals(epoch,c.epoch);assertSame(numeric,number(p.amount))}}
    @Test fun unsupportedDriverAndInvalidBasisAreVisibleAndDoNotOfferSnapshots(){c.publishCapabilities(app,0);launch();professional(true);optional();number(p.amount).requestValue(1317);assertEquals(1317,p.value(prefs.all,p.amount));assertTrue(fragment().findPreference<Preference>("tone_pro_compatibility")!!.summary.toString().contains("实际继续原版处理"));assertFalse(fragment().findPreference<Preference>("tone_pro_audition")!!.isEnabled);prefs.edit().putString(p.baseKeys[0],"bad").commit();layout();assertTrue(fragment().findPreference<Preference>("tone_pro_baseline")!!.summary.toString().contains("原十项或范围无效"));assertFalse(fragment().findPreference<Preference>("tone_pro_save_a")!!.isEnabled);assertEquals("bad",prefs.getString(p.baseKeys[0],null))}
}

open class ToneAdvancedRasterFixture:ToneAdvancedUiFixture(){
    protected fun renderTone(w:Int,scale:Int){width=w;RuntimeEnvironment.setFontScale(scale/100f);launch();professional(true);optional();prefs.edit().putBoolean(p.ENABLED,true).putInt(p.baseKeys[0],37).putInt(p.delta[0].key,137).putInt(p.amount.key,1317).putInt(p.frequency[3].key,137).putInt(p.q[0].key,83).putInt(p.slope[0].key,317).putInt(p.bassFast.key,137).putInt(p.bassSlow.key,317).putInt(p.transientFast.key,73).putInt(p.transientSlow.key,137).putInt(p.mix.key,317).putInt(p.output.key,-29).putInt(p.headroom.key,-29).commit();reading(0);reading(512);layout()
        val folder=File("build/reports/tone1").apply {mkdirs()}
        fun complete(view:View,ids:List<Int>){for(id in ids){val text=view.findViewById<TextView>(id);val lines=requireNotNull(text.layout);repeat(lines.lineCount){assertEquals("No ellipsis: ${text.text}",0,lines.getEllipsisCount(it));assertTrue("Text width: ${text.text}",lines.getLineMax(it)<=text.width-text.compoundPaddingLeft-text.compoundPaddingRight+1f)};assertTrue("Complete text: ${text.text}",lines.getLineEnd(lines.lineCount-1)>=text.text.length)}}
        for(control in p.numbers)complete(row(control.key),listOf(android.R.id.title,R.id.seekbar_value,R.id.bass_reset))
        for(key in p.switches)complete(row(key),listOf(android.R.id.title,android.R.id.summary))
        for(key in listOf("tone_pro_baseline","tone_pro_help","tone_pro_actual","tone_pro_compatibility","tone_pro_response_graph"))complete(row(key),listOf(android.R.id.title,android.R.id.summary))
        fun shot(name:String,key:String){row(key);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80));layout();val view=controller!!.get().findViewById<View>(android.R.id.content);val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(MaterialColors.getColor(view,com.google.android.material.R.attr.colorSurface));view.draw(Canvas(bitmap));File(folder,"${w}_${scale}_$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        shot("original",p.baseKeys[0]);shot("core",p.CORE_ON);shot("shape_frequency",p.SHAPE_ON);shot("shape_q",p.q[0].key);shot("shape_slope",p.slope[0].key);shot("dynamic_weights",p.DYNAMIC_ON);shot("dynamic_time",p.bassFast.key);shot("dynamic_detect",p.bassSensitivity.key);shot("mix",p.MIX_ON);shot("tools","tone_pro_audition");shot("graph","tone_pro_response_graph");optional(false);layout();shot("disabled",p.DYNAMIC_ON)
    }
}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class ToneAdvancedRaster320_100Test:ToneAdvancedRasterFixture(){@Test fun render()=renderTone(320,100)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class ToneAdvancedRaster320_150Test:ToneAdvancedRasterFixture(){@Test fun render()=renderTone(320,150)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class ToneAdvancedRaster411_100Test:ToneAdvancedRasterFixture(){@Test fun render()=renderTone(411,100)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class ToneAdvancedRaster411_150Test:ToneAdvancedRasterFixture(){@Test fun render()=renderTone(411,150)}
