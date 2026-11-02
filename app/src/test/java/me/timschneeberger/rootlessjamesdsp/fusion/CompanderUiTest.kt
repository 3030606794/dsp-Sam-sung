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

open class CompanderUiFixture {
    protected val app: Application get() = RuntimeEnvironment.getApplication()
    protected val p = CompanderParameters
    protected val c = CompanderController
    protected val prefs get() = c.preferences(app)
    protected var controller: ActivityController<Host>? = null
    protected var width = 320
    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()
    protected fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("compander") as PreferenceGroupFragment
    protected fun group() = fragment().findPreference<SwitchPreferenceGroup>(p.ENABLED)!!
    @Before fun prepare() {
        stopKoin(); val preferences = Preferences(app); startKoin { modules(module { single { preferences.App() } }) }
        RuntimeEnvironment.setFontScale(1f); prefs.edit().clear().commit(); ReverbController.preferences(app).edit().clear().commit()
        c.stopAudition(app, clearSnapshots = true); c.publishRuntime(app, emptyList()); c.publishCapabilities(app, 2147483647)
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
    protected fun optional(on: Boolean=true) {
        val e=prefs.edit();p.switches.filterNot {it in setOf(p.PROFESSIONAL,p.EXACT_FREQUENCY)}.forEach {e.putBoolean(it,on)};e.commit();layout()
    }
    protected fun engine(n: Int) {prefs.edit().putInt(p.ENGINE,n).commit();layout()}
    protected fun number(control: BassParameters.Control)=fragment().findPreference<BassNumericPreference>(control.key)!!
    protected fun response(epoch: Int=2,engine: Int=p.engine(prefs.all),rate: Int=48000): CompanderParameters.Response {
        val v=IntArray(200).apply {this[0]=1;this[1]=epoch;this[2]=rate;this[3]=engine;this[4]=1;this[5]=32;this[6]=p.legacyTransform(prefs.all).toInt()
            for(i in 0 until 32){this[8+i*6]=20+i*500;this[9+i*6]=-317+i*7;this[10+i*6]=-137;this[11+i*6]=-1737;this[12+i*6]=-2337;this[13+i*6]=if(i in 2..29) 1 else 0}}
        return requireNotNull(p.decodeResponse(v))
    }
    protected fun reading(frames: Int,mode: Int=c.audition,stage: Int=0,ready: Boolean=true,responseEngine: Int=p.engine(prefs.all),responseRate: Int=48000) {
        val v=IntArray(80).apply {this[0]=1;this[1]=2;this[2]=48000;this[3]=1;this[4]=1;this[5]=mode;this[7]=stage;this[11]=c.epoch;this[12]=1;this[13]=p.engine(prefs.all);this[14]=p.legacyTransform(prefs.all).toInt();this[15]=p.legacyGranularity(prefs.all).toInt()
            this[16]=-631;this[17]=-688;this[18]=-427;this[19]=-455;this[20]=-1337;this[21]=-1413;this[22]=-1137;this[23]=-1213;this[24]=3;this[25]=if(ready)1 else 0;this[26]=96000;this[27]=137;this[28]=317;this[29]=500;this[30]=750;this[31]=2000
            this[32]=1536;this[33]=2200;this[34]=137;this[35]=43;this[36]=317;this[37]=23;this[38]=43;this[39]=-800;this[40]=1;this[41]=-1737;this[42]=5333;this[43]=137;this[44]=-237;this[45]=431;this[46]=p.type(prefs.all);this[47]=p.detector(prefs.all);this[48]=20;this[49]=73;this[50]=18017;this[51]=73;this[52]=18017;this[53]=1200;this[54]=2400;this[55]=1;this[56]=43;this[57]=1;this[58]=1;this[59]=1;this[60]=317;this[61]=-29
            for(i in 0 until 7){this[64+i]=95+i*500;this[71+i]=-137+i*20}}
        c.publishRuntime(app,listOf(CompanderController.Reading(this,true,frames,48000,p.decode(v),response=response(2,responseEngine,responseRate))));idle()
    }
    class Host: FragmentActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP);super.onCreate(state)
            setContentView(FragmentContainerView(this).apply {id=R.id.card_compressor})
            if(state==null) supportFragmentManager.beginTransaction().add(R.id.card_compressor,PreferenceGroupFragment.newInstance(CompanderParameters.NAMESPACE,R.xml.dsp_compander_preferences),"compander").commitNow()
        }
    }
}

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class CompanderUiTest: CompanderUiFixture() {
    @Test fun oldFourAlgorithmsFiveGranularitiesPowerAndFoldRemainWithNewFlagsOff() {
        launch(false);assertEquals(1,fragment().listView.adapter!!.itemCount);row(p.ENABLED).performClick();layout()
        assertTrue(group().isExpanded);assertFalse(group().isChecked);assertFalse(p.moduleEnabled(prefs.all));assertFalse(p.enabled(prefs.all))
        assertEquals(4,fragment().findPreference<ListPreference>(p.BASE_TRANSFORM)!!.entries.size)
        assertFalse(prefs.contains(p.frequencies[0].key));professional(true);p.numbers.forEach {assertFalse(number(it).isEnabled)}
    }
    @Test fun onlyTakenOverLegacyFieldsGreyAndRestoreExactSavedValues() {
        prefs.edit().putFloat(p.BASE_TIME,.17317f).putFloat(p.BASE_GRANULARITY,3f).putString(p.BASE_TRANSFORM,"2").putString(p.BASE_CURVE,p.DEFAULT_CURVE+";").commit();launch();professional(true)
        val curve=fragment().findPreference<CompanderPreference>(p.BASE_CURVE)!!;val time=fragment().findPreference<MaterialSeekbarPreference>(p.BASE_TIME)!!
        assertTrue(curve.isEnabled);assertTrue(time.isEnabled);prefs.edit().putBoolean(p.CORE_ON,true).commit();layout();assertFalse(curve.isEnabled);assertTrue(time.isEnabled)
        assertFalse(curve.callChangeListener(p.DEFAULT_CURVE));prefs.edit().putBoolean(p.TIME_ON,true).commit();layout();assertFalse(time.isEnabled)
        prefs.edit().putBoolean(p.CORE_ON,false).commit();layout();assertTrue(curve.isEnabled);assertFalse(time.isEnabled)
        professional(false);assertTrue(curve.isEnabled);assertTrue(time.isEnabled);assertEquals(.17317f,prefs.getFloat(p.BASE_TIME,0f));assertEquals(p.DEFAULT_CURVE+";",prefs.getString(p.BASE_CURVE,null))
    }
    @Test fun standardEngineGreysFourOldControlsEvenWhenItsCoreGroupIsOff() {
        launch();professional(true);engine(1)
        for(key in listOf(p.BASE_CURVE,p.BASE_TIME,p.BASE_GRANULARITY,p.BASE_TRANSFORM)) {val old=fragment().findPreference<Preference>(key)!!;assertFalse(key,old.isEnabled);assertFalse(old.callChangeListener(if(key==p.BASE_CURVE)p.DEFAULT_CURVE else "0"))}
        assertTrue(fragment().findPreference<Preference>("compander_pro_baseline")!!.summary.toString().contains("原保存值保留"));professional(false)
        for(key in listOf(p.BASE_CURVE,p.BASE_TIME,p.BASE_GRANULARITY,p.BASE_TRANSFORM)) assertTrue(fragment().findPreference<Preference>(key)!!.isEnabled)
    }
    @Test fun allThirtySixNumbersSupportInputPlusMinusAndSingleReset() {
        launch();professional(true);optional()
        for(control in p.numbers) {
            engine(if(control in p.standardNumbers || control in listOf(p.sideHP,p.sideLP,p.lookahead))1 else 0)
            if(control==p.hysteresis) prefs.edit().putInt(p.TYPE,3).commit() else prefs.edit().putInt(p.TYPE,0).putInt(p.DETECTOR,1).commit();layout()
            val n=when(control){p.rangeLow,p.sideHP->73;p.rangeHigh,p.sideLP->18017;p.output->-29;p.mix->317;else->control.default+2}
            assertTrue(control.key,number(control).isEnabled);assertTrue(p.candidateValid(prefs.all,control,n))
            row(control.key).findViewById<View>(R.id.seekbar_value).performClick();idle();val dialog=ShadowDialog.getLatestDialog() as AlertDialog
            dialog.findViewById<EditText>(android.R.id.text1)!!.setText(control.number(n));dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();layout();assertEquals(control.key,n,number(control).value)
            row(control.key).findViewById<View>(R.id.bass_increment).performClick();idle();assertEquals(n+1,p.value(prefs.all,control))
            row(control.key).findViewById<View>(R.id.bass_decrement).performClick();idle();assertEquals(n,p.value(prefs.all,control))
            row(control.key).findViewById<View>(R.id.bass_reset).performClick();idle();assertEquals(control.default,p.value(prefs.all,control))
        }
        assertFalse(p.moduleEnabled(prefs.all))
    }
    @Test fun disabledGroupsKeepNumbersAndEnablingRestoresSavedValues() {
        launch();professional(true);optional();number(p.coefficients[0]).requestValue(-317)
        for((flag,controls) in listOf(p.CORE_ON to p.coreNumbers,p.TIME_ON to p.timeNumbers,p.DETECT_ON to p.detectNumbers,p.LIMITS_ON to p.limitNumbers,p.MIX_ON to listOf(p.mix),p.OUTPUT_ON to listOf(p.output))) {
            engine(0);val before=p.capture(prefs.all);prefs.edit().putBoolean(flag,false).commit();layout()
            for(control in controls){assertFalse(number(control).isEnabled);assertFalse(number(control).step(1));assertFalse(number(control).resetToDefault());assertEquals(.38f,row(control.key).findViewById<TextView>(R.id.seekbar_value).alpha,.001f)}
            prefs.edit().putBoolean(flag,true).commit();layout();assertEquals(before,p.capture(prefs.all));controls.forEach {assertTrue(number(it).isEnabled)}
        }
    }
    @Test fun modesExposeOnlyEffectiveRatioGateRmsMappingAndRelativeParameters() {
        launch();professional(true);optional();assertTrue(fragment().findPreference<Preference>(p.EXACT_FREQUENCY)!!.isEnabled);engine(1)
        assertFalse(fragment().findPreference<Preference>(p.EXACT_FREQUENCY)!!.isEnabled);assertFalse(number(p.relative).isEnabled);assertFalse(number(p.weakFloor).isEnabled)
        val type=fragment().findPreference<FieldChoicePreference>(p.TYPE)!!;assertTrue(type.choose(1));layout();assertTrue(number(p.weakFloor).isEnabled);assertTrue(type.choose(3));layout();assertFalse(number(p.ratio).isEnabled);assertTrue(number(p.hysteresis).isEnabled)
        assertTrue(fragment().findPreference<FieldChoicePreference>(p.DETECTOR)!!.choose(0));layout();assertFalse(number(p.rmsWindow).isEnabled)
        assertTrue(type.choose(0));layout();assertTrue(number(p.ratio).isEnabled);assertFalse(number(p.hysteresis).isEnabled)
    }
    @Test fun staleNumericDialogCannotWriteAfterGroupEngineOrFoundationChangedAndReturned() {
        launch();professional(true);optional();number(p.coefficients[0]).requestValue(-317)
        for(key in listOf(p.CORE_ON,p.ENGINE,p.BASE_TIME)) {
            row(p.coefficients[0].key).findViewById<View>(R.id.seekbar_value).performClick();idle();val dialog=ShadowDialog.getLatestDialog() as AlertDialog
            dialog.findViewById<EditText>(android.R.id.text1)!!.setText("0.731")
            when(key){p.CORE_ON->{prefs.edit().putBoolean(key,false).commit();layout();prefs.edit().putBoolean(key,true).commit()};p.ENGINE->{engine(1);engine(0)};else->{prefs.edit().putFloat(key,.17317f).commit();layout();prefs.edit().putFloat(key,.22f).commit()}};layout()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertEquals(-317,p.value(prefs.all,p.coefficients[0]));assertTrue(dialog.isShowing);dialog.dismiss()
        }
    }
    @Test fun manualCrossingAndBypassGapRejectWithoutChangingTheOtherValue() {
        launch();professional(true);optional();assertFalse(number(p.frequencies[0]).requestValue(105000));assertEquals(0,p.value(prefs.all,p.frequencies[1]))
        assertTrue(number(p.rangeLow).requestValue(500));assertFalse(number(p.rangeHigh).requestValue(500));assertEquals(0,p.value(prefs.all,p.rangeHigh));assertFalse(number(p.rangeLow).requestValue(1))
        assertTrue(number(p.frequencies[0]).requestValue(-7317));layout()
        val control=row(p.frequencies[0].key);val slider=control.findViewById<Slider>(R.id.seekbar)
        val range=requireNotNull(number(p.frequencies[0]).sliderRange);assertTrue(range.last-range.first<=48000)
        assertEquals(-7317,p.value(prefs.all,p.frequencies[0]));assertEquals(1f,slider.stepSize)
        val y=slider.height/2f;val x=slider.trackSidePadding+(slider.value/slider.valueTo)*slider.trackWidth
        val moved=slider.trackSidePadding+((slider.value+2)/slider.valueTo)*slider.trackWidth
        val recognized=slider.trackSidePadding+((slider.value+8)/slider.valueTo)*slider.trackWidth;val t=SystemClock.uptimeMillis()
        slider.dispatchTouchEvent(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,x,y,0));slider.dispatchTouchEvent(MotionEvent.obtain(t,t+16,MotionEvent.ACTION_MOVE,recognized,y,0));idle()
        val firstMove=p.value(prefs.all,p.frequencies[0]);assertNotEquals("A drag beyond the scrolling touch slop commits before release",-7317,firstMove)
        slider.dispatchTouchEvent(MotionEvent.obtain(t,t+32,MotionEvent.ACTION_MOVE,moved,y,0));idle()
        assertNotEquals("The same bound slider continues fine motion before release",firstMove,p.value(prefs.all,p.frequencies[0]))
        slider.dispatchTouchEvent(MotionEvent.obtain(t,t+48,MotionEvent.ACTION_UP,moved,y,0));idle()
        val changed=p.value(prefs.all,p.frequencies[0]);assertNotEquals(-7317,changed);assertTrue(changed in range);assertTrue(kotlin.math.abs(changed+7317)<=3000);assertEquals(0,p.value(prefs.all,p.frequencies[1]))
    }
    @Test fun foldAndPauseClearToolsAndSnapshotsWithoutChangingSavedPowerOrFineValues() {
        launch();professional(true);optional();prefs.edit().putBoolean(p.ENABLED,true).commit();reading(0);reading(512);layout();val saved=p.capture(prefs.all)
        row("compander_pro_save_a").performClick();assertTrue(fragment().findPreference<FieldChoicePreference>("compander_pro_audition")!!.choose(2));group().setExpanded(false);layout();assertEquals(0,c.audition);assertFalse(c.hasComparison(0));assertEquals(saved,p.capture(prefs.all));assertTrue(p.moduleEnabled(prefs.all))
        group().setExpanded(true);layout();reading(1024);row("compander_pro_save_a").performClick();controller!!.pause();idle();assertFalse(c.hasComparison(0))
    }
    @Test fun diagnosticReadingsAndGraphUseRealGainUnitsAndDoNotTouchDynamicTone() {
        val other=DynamicController.preferences(app);other.edit().clear().putBoolean("v_dynamic",true).putInt("sentinel",73).commit();val before=other.all
        launch();professional(true);optional();prefs.edit().putBoolean(p.ENABLED,true).commit();reading(0);reading(512);layout()
        val text=fragment().findPreference<Preference>("compander_pro_actual")!!.summary.toString();assertTrue(text.contains("48000 Hz"));assertTrue(text.contains("96000 Hz"));assertTrue(text.contains("当前最大提升 1.37 dB／衰减 3.17 dB"));assertTrue(text.contains("5.333 ms"));assertEquals(before,other.all)
        val graph=fragment().findPreference<Preference>("compander_pro_response_graph")!!;assertTrue(graph.summary.toString().contains("不是完整传递频响"))
        val view=row(graph.key).findViewById<CompanderResponseView>(R.id.compander_response_chart);assertTrue(view.onTouchEvent(MotionEvent.obtain(0,1,MotionEvent.ACTION_UP,view.width*.5f,30f,0)));idle();assertTrue(graph.summary.toString().contains("Hz；动态增益"));reading(1024);layout();assertTrue(graph.summary.toString().contains("Hz；动态增益"))
        reading(1536,responseEngine=1);layout();assertTrue(graph.summary.toString().contains("等待配套驱动"));reading(2048,responseRate=44100);layout();assertTrue(graph.summary.toString().contains("等待配套驱动"))
    }
    @Test fun meterAndGraphVisibilityPreserveMatchedAuditionAndNumericRows() {
        launch();professional(true);optional();prefs.edit().putBoolean(p.ENABLED,true).commit();reading(0);reading(512);layout();row("compander_pro_save_a").performClick();row("compander_pro_save_b").performClick();assertTrue(fragment().findPreference<FieldChoicePreference>("compander_pro_audition")!!.choose(6));reading(1024,6,3);assertTrue(c.setMatch(app,true));assertTrue(fragment().findPreference<FieldChoicePreference>("compander_pro_audition")!!.choose(4))
        c.sync(app,2147483647,this){_,_->true};val epoch=c.epoch;val numeric=number(p.amount)
        for(key in listOf(p.METERS,p.RESPONSE)) for(on in listOf(false,true)){fragment().findPreference<SwitchPreferenceCompat>(key)!!.isChecked=on;layout();c.sync(app,2147483647,this){_,_->true};assertEquals(4,c.audition);assertTrue(c.match);assertEquals(epoch,c.epoch);assertSame(numeric,number(p.amount))}
    }
    @Test fun oldDriverKeepsSavedProfessionalValuesAndClearlyReportsInactiveSupport() {
        c.publishCapabilities(app,1073741823);launch();professional(true);optional();number(p.amount).requestValue(1317)
        assertEquals(1317,p.value(prefs.all,p.amount));assertTrue(fragment().findPreference<Preference>("compander_pro_compatibility")!!.summary.toString().contains("实际继续原版处理"));assertFalse(fragment().findPreference<Preference>("compander_pro_audition")!!.isEnabled)
    }
    @Test fun invalidSavedCurveIsVisibleAndDoesNotOfferTemporarySnapshots() {
        prefs.edit().putString(p.BASE_CURVE,"malformed").commit();launch();professional(true)
        assertTrue(fragment().findPreference<Preference>("compander_pro_baseline")!!.summary.toString().contains("原曲线无效"));assertTrue(fragment().findPreference<Preference>("compander_pro_actual")!!.summary.toString().contains("保留上次有效处理"));assertFalse(fragment().findPreference<Preference>("compander_pro_save_a")!!.isEnabled);assertEquals("malformed",prefs.getString(p.BASE_CURVE,null))
    }

}

open class CompanderRasterFixture: CompanderUiFixture() {
    protected fun renderCompander(w: Int,scale: Int) {
        width=w;RuntimeEnvironment.setFontScale(scale/100f);launch();professional(true);optional();prefs.edit().putBoolean(p.ENABLED,true).putInt(p.amount.key,1317).putInt(p.frequencies[0].key,-7317).putInt(p.coefficients[0].key,-317).putInt(p.backgroundDelta.key,137).putInt(p.attack.key,137).putInt(p.hold.key,43).putInt(p.release.key,317).putInt(p.relative.key,23).putInt(p.knee.key,43).putInt(p.rangeLow.key,73).putInt(p.rangeHigh.key,18017).putInt(p.sideHP.key,73).putInt(p.sideLP.key,18017).putInt(p.threshold.key,-237).putInt(p.ratio.key,431).putInt(p.lookahead.key,137).putInt(p.mix.key,317).putInt(p.output.key,-29).commit();reading(0);reading(512);layout()
        val folder=File("build/reports/compander1").apply {mkdirs()}
        fun complete(view: View,ids: List<Int>) {for(id in ids){val text=view.findViewById<TextView>(id);val lines=requireNotNull(text.layout);repeat(lines.lineCount){assertEquals("No ellipsis: ${text.text}",0,lines.getEllipsisCount(it));assertTrue("Text width: ${text.text}",lines.getLineMax(it)<=text.width-text.compoundPaddingLeft-text.compoundPaddingRight+1f)};assertTrue("Complete text: ${text.text}",lines.getLineEnd(lines.lineCount-1)>=text.text.length)}}
        for(control in p.numbers) complete(row(control.key),listOf(android.R.id.title,R.id.seekbar_value,R.id.bass_reset))
        for(key in p.switches) complete(row(key),listOf(android.R.id.title,android.R.id.summary))
        for(key in listOf("compander_pro_baseline","compander_pro_help","compander_pro_actual","compander_pro_compatibility","compander_pro_response_graph")) complete(row(key),listOf(android.R.id.title,android.R.id.summary))
        fun shot(name: String,key: String){row(key);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80));layout();val view=controller!!.get().findViewById<View>(android.R.id.content);val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(MaterialColors.getColor(view,com.google.android.material.R.attr.colorSurface));view.draw(Canvas(bitmap));File(folder,"${w}_${scale}_$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        shot("baseline",p.BASE_TIME);shot("core_frequency",p.CORE_ON);shot("core_coefficient",p.coefficients[0].key);shot("time",p.TIME_ON);shot("detect",p.DETECT_ON);shot("mix",p.MIX_ON);shot("tools","compander_pro_audition");shot("gain","compander_pro_response_graph")
        engine(1);reading(1024);layout();shot("standard",p.TYPE);shot("sidechain",p.SIDECHAIN_ON);prefs.edit().putInt(p.TYPE,3).commit();layout();shot("gate",p.hysteresis.key)
        optional(false);layout();shot("disabled",p.TIME_ON)
    }
}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class CompanderRaster320_100Test: CompanderRasterFixture(){@Test fun render()=renderCompander(320,100)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class CompanderRaster320_150Test: CompanderRasterFixture(){@Test fun render()=renderCompander(320,150)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class CompanderRaster411_100Test: CompanderRasterFixture(){@Test fun render()=renderCompander(411,100)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class CompanderRaster411_150Test: CompanderRasterFixture(){@Test fun render()=renderCompander(411,150)}
