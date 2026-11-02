package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.MotionEvent
import android.view.View
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
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
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
import java.time.Duration

open class DynamicUiFixture {
    protected val app: Application get()=RuntimeEnvironment.getApplication()
    protected val p=DynamicParameters; protected val c=DynamicController
    protected val prefs get()=c.preferences(app)
    protected var controller: ActivityController<Host>?=null
    protected var width=320
    protected fun idle()=shadowOf(Looper.getMainLooper()).idle()
    protected fun fragment()=controller!!.get().supportFragmentManager.findFragmentByTag("dynamic") as ViperEffectPreferenceFragment
    protected fun group()=fragment().effectHeader
    @Before fun prepare() {
        RuntimeEnvironment.setFontScale(1f);prefs.edit().clear().commit();c.stopAudition(app,clearSnapshots=true)
        c.publishRuntime(app,emptyList());c.publishCapabilities(app,536870911)
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy();controller=null;idle();c.stopAudition(app,clearSnapshots=true);c.publishRuntime(app,emptyList());RuntimeEnvironment.setFontScale(1f)
    }
    protected fun launch(expanded: Boolean=true) {
        controller=Robolectric.buildActivity(Host::class.java).setup().visible();fragment().listView.itemAnimator=null;group().setExpanded(expanded);layout()
    }
    protected fun layout() {
        repeat(2) { idle();controller!!.get().findViewById<View>(android.R.id.content).apply {
            measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY));layout(0,0,width,2400)
        } };idle()
    }
    @Suppress("RestrictedApi") protected fun row(key: String): View {
        val list=fragment().listView;val adapter=list.adapter as PreferenceGroupAdapter
        val index=adapter.getPreferenceAdapterPosition(key);assertTrue("Visible row $key",index>=0)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index,0);layout()
        return requireNotNull(list.findViewHolderForAdapterPosition(index)).itemView
    }
    protected fun professional(on: Boolean) {
        val pref=fragment().findPreference<SwitchPreferenceCompat>(p.PROFESSIONAL)!!;assertTrue(pref.callChangeListener(on));pref.isChecked=on;layout()
    }
    protected fun optional(on: Boolean=true) {
        prefs.edit().putBoolean(p.CORE_ON,on).putBoolean(p.TONE_ON,on).putBoolean(p.ADAPTIVE_ON,on).putBoolean(p.MIX_ON,on)
            .putBoolean(p.OUTPUT_ON,on).putBoolean(p.METERS,on).putBoolean(p.RESPONSE,on).commit();layout()
    }
    protected fun base(n: Int) { prefs.edit().putString(p.BASE,n.toString()).commit();layout() }
    protected fun number(control: BassParameters.Control)=fragment().findPreference<BassNumericPreference>(control.key)!!
    protected fun response(): DynamicParameters.Response {
        val v=IntArray(200).apply { this[0]=1;this[1]=2;this[2]=48000;this[3]=p.base(prefs.all).let { if(it==4) 1 else 0 };this[4]=137;this[5]=1;this[6]=32
            for(i in 0 until 32) { this[8+i*6]=20+i*500;this[9+i*6]=(600-i*20);this[10+i*6]=-i*317;this[11+i*6]=0;this[12+i*6]=-i*137;this[13+i*6]=-i*150 } }
        return requireNotNull(p.decodeResponse(v))
    }
    protected fun reading(frames: Int,mode: Int=c.audition,stage: Int=0,pro: Boolean=true,ready: Boolean=true,responsePath: Int?=null,responseRate: Int=48000) {
        val v=IntArray(48).apply { this[0]=1;this[1]=2;this[2]=48000;this[3]=1;this[4]=if(pro) 1 else 0;this[5]=mode;this[7]=stage
            this[11]=c.epoch;this[12]=1;this[13]=p.base(prefs.all);this[14]=p.legacy(prefs.all);this[15]=if(this[13]==4) 1 else 0
            this[16]=-631;this[17]=-688;this[18]=-427;this[19]=-455;this[20]=-1337;this[21]=-1413;this[22]=-1137;this[23]=-1213
            this[24]=3;this[25]=if(ready) 1 else 0;this[26]=96000;this[27]=137;this[28]=-1137;this[29]=500;this[30]=750;this[31]=2000
            this[32]=if(this[15]==0) 157 else 0;this[33]=47;this[34]=73;this[35]=if(this[15]==1) 72 else 0
            this[36]=27;this[37]=18017;this[38]=2337;this[39]=817;this[40]=137;this[41]=1317;this[42]=937
            this[43]=73;this[44]=317;this[45]=-3471;this[46]=1;this[47]=if(this[15]==0) 3 else 0 }
        val responseWords=response().data.toIntArray().apply { this[1]=frames*2;if(responsePath!=null) this[3]=responsePath;this[2]=responseRate }
        c.publishRuntime(app,listOf(DynamicController.Reading(this,true,frames,48000,p.decode(v),response=p.decodeResponse(responseWords))));idle()
    }
    class Host: FragmentActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP);super.onCreate(state)
            setContentView(FragmentContainerView(this).apply { id=R.id.viper_effect_dynamic })
            if(state==null) supportFragmentManager.beginTransaction().add(R.id.viper_effect_dynamic,ViperEffectPreferenceFragment.newInstance("v_dynamic"),"dynamic").commitNow()
        }
    }
}

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class DynamicUiTest: DynamicUiFixture() {
    @Test fun originalPowerSixPresetsAndFoldRemainIndependentAndNewGroupsStartOff() {
        launch(false);assertEquals("动态音色处理",group().title);assertEquals(1,fragment().listView.adapter!!.itemCount)
        row(p.ENABLED).performClick();layout();assertTrue(group().isExpanded);assertFalse(group().isChecked)
        assertFalse(p.moduleEnabled(prefs.all));assertFalse(p.enabled(prefs.all))
        val presets=fragment().findPreference<ListPreference>(p.BASE)!!;assertEquals(6,presets.entries.size);assertTrue(presets.isEnabled)
        assertFalse(prefs.contains(p.bassDelta.key));professional(true);p.numbers.forEach { assertFalse(number(it).isEnabled) }
    }
    @Test fun twentyNumbersSupportInputButtonsAndSingleResetInTheirEffectiveMode() {
        launch();professional(true);optional()
        for(control in p.numbers) {
            base(if(control in listOf(p.injectDelta,p.qDelta,p.deepFreqDelta)) 4 else 0)
            val n=when(control) { p.hp -> 27;p.lp -> 18017;p.output -> -29;p.mix -> 317;else -> control.default+2 }
            assertTrue(p.candidateValid(prefs.all,control,n));assertTrue(number(control).isEnabled)
            row(control.key).findViewById<View>(R.id.seekbar_value).performClick();idle()
            val dialog=ShadowDialog.getLatestDialog() as AlertDialog;dialog.findViewById<EditText>(android.R.id.text1)!!.setText(control.number(n))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();layout();assertEquals(control.key,n,number(control).value)
            row(control.key).findViewById<View>(R.id.bass_increment).performClick();idle();assertEquals(n+1,p.value(prefs.all,control))
            row(control.key).findViewById<View>(R.id.bass_decrement).performClick();idle();assertEquals(n,p.value(prefs.all,control))
            row(control.key).findViewById<View>(R.id.bass_reset).performClick();idle();assertEquals(control.default,p.value(prefs.all,control))
        }
        assertFalse(p.moduleEnabled(prefs.all));assertEquals(0,c.audition)
    }
    @Test fun coreTakeoverGreysOnlyLegacyStrengthAndOffRestoresSavedValue() {
        prefs.edit().putInt(p.LEGACY,37).putString(p.BASE,"3").commit();launch()
        var legacy=fragment().findPreference<ViperSliderPreference>(p.LEGACY)!!;assertTrue(legacy.isEnabled);assertEquals(37,legacy.value)
        professional(true);assertTrue(legacy.isEnabled);optional();assertFalse(legacy.isEnabled)
        assertFalse(row(p.LEGACY).findViewById<Slider>(R.id.seekbar).isEnabled);assertFalse(legacy.callChangeListener(71))
        assertTrue(fragment().findPreference<ListPreference>(p.BASE)!!.isEnabled)
        prefs.edit().putInt(p.LEGACY,71).commit();layout();assertEquals(71,legacy.value);assertEquals(71,prefs.getInt(p.LEGACY,0))
        prefs.edit().putBoolean(p.CORE_ON,false).commit();layout();assertTrue(legacy.isEnabled)
        prefs.edit().putBoolean(p.CORE_ON,true).commit();layout();assertFalse(legacy.isEnabled)
        professional(false);assertTrue(legacy.isEnabled);assertEquals(71,legacy.value)
    }
    @Test fun presetSwitchPreservesOffsetsAndGreysModeSpecificControlsWithoutChangingAlgorithm() {
        launch();professional(true);optional();assertTrue(number(p.bassDelta).requestValue(23));assertTrue(number(p.xDelta).requestValue(-1900))
        assertEquals(0,p.base(prefs.all));assertTrue(number(p.bassDelta).isEnabled);assertFalse(number(p.injectDelta).isEnabled)
        base(4);assertEquals("4",fragment().findPreference<ListPreference>(p.BASE)!!.value)
        assertFalse(number(p.bassDelta).isEnabled);assertFalse(number(p.xDelta).isEnabled);assertTrue(number(p.injectDelta).isEnabled)
        assertTrue(number(p.qDelta).requestValue(43));assertTrue(number(p.deepFreqDelta).requestValue(17))
        base(0);assertEquals("0",fragment().findPreference<ListPreference>(p.BASE)!!.value)
        assertTrue(number(p.bassDelta).isEnabled);assertFalse(number(p.injectDelta).isEnabled)
        assertEquals(23,number(p.bassDelta).value);assertEquals(-1900,number(p.xDelta).value);assertEquals(43,number(p.qDelta).value)
    }
    @Test fun groupOffGreysControlsAndReopeningRestoresSavedFineValues() {
        launch();professional(true);optional();number(p.bassDelta).requestValue(23);number(p.hp).requestValue(27)
        for((key,controls) in listOf(p.CORE_ON to p.coreNumbers.take(3),p.TONE_ON to listOf(p.xDelta,p.lowFreqDelta,p.highFreqDelta,p.hp,p.lp),
            p.ADAPTIVE_ON to p.adaptiveNumbers,p.MIX_ON to listOf(p.mix),p.OUTPUT_ON to listOf(p.output))) {
            val before=p.capture(prefs.all);prefs.edit().putBoolean(key,false).commit();layout()
            for(control in controls) { assertFalse(number(control).isEnabled);assertFalse(number(control).step(1));assertFalse(number(control).resetToDefault())
                assertEquals(.38f,row(control.key).findViewById<TextView>(R.id.seekbar_value).alpha,.001f) }
            prefs.edit().putBoolean(key,true).commit();layout();assertEquals(before,p.capture(prefs.all));controls.forEach { assertTrue(number(it).isEnabled) }
        }
    }
    @Test fun staleDialogCannotWriteAfterGroupProfessionalOrBaseWasChangedAndRestored() {
        launch();professional(true);optional();number(p.bassDelta).requestValue(23)
        for(key in listOf(p.CORE_ON,p.PROFESSIONAL,p.BASE)) {
            row(p.bassDelta.key).findViewById<View>(R.id.seekbar_value).performClick();idle()
            val dialog=ShadowDialog.getLatestDialog() as AlertDialog;dialog.findViewById<EditText>(android.R.id.text1)!!.setText("7.3")
            when(key) { p.PROFESSIONAL -> { professional(false);professional(true) };p.BASE -> { base(4);base(0) }
                else -> { prefs.edit().putBoolean(key,false).commit();layout();prefs.edit().putBoolean(key,true).commit();layout() } }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertEquals(23,p.value(prefs.all,p.bassDelta));dialog.dismiss()
        }
    }
    @Test fun crossingFilterGapAndResetRejectWithoutMovingAnyOtherControl() {
        launch();professional(true);optional();assertTrue(number(p.lowFreqDelta).requestValue(19));assertFalse(number(p.lowFreqDelta).step(1))
        assertEquals(0,number(p.highFreqDelta).value);assertTrue(number(p.highFreqDelta).requestValue(1));assertTrue(number(p.lowFreqDelta).requestValue(20))
        assertFalse(number(p.highFreqDelta).resetToDefault());assertEquals(1,number(p.highFreqDelta).value);assertEquals(20,number(p.lowFreqDelta).value)
        assertTrue(number(p.hp).step(1));assertEquals(10,number(p.hp).value);assertTrue(number(p.hp).step(-1));assertEquals(0,number(p.hp).value)
        assertTrue(number(p.lp).step(1));assertEquals(100,number(p.lp).value);assertFalse(number(p.hp).requestValue(100));assertFalse(number(p.hp).requestValue(9))
        assertFalse(number(p.detectorHP).requestValue(250));assertEquals(250,number(p.detectorLP).value)
    }
    @Test fun detectorChoiceGreysAndRestoresWithoutChangingSavedPeakMode() {
        launch();professional(true);optional();val detector=fragment().findPreference<FieldChoicePreference>(p.DETECTOR)!!
        assertEquals(1,detector.value);assertTrue(detector.choose(0));prefs.edit().putBoolean(p.ADAPTIVE_ON,false).commit();layout()
        assertFalse(detector.isEnabled);assertFalse(detector.choose(1));assertEquals(0,p.detector(prefs.all))
        prefs.edit().putBoolean(p.ADAPTIVE_ON,true).commit();layout();assertTrue(detector.isEnabled);assertEquals(0,detector.value)
    }
    @Test fun foldPauseAudioChangeAndLostCapabilitiesStopTransientListening() {
        launch();professional(true);optional();prefs.edit().putBoolean(p.ENABLED,true).commit();reading(0);reading(512);layout()
        row("dynamic_pro_save_a").performClick();row("dynamic_pro_save_b").performClick();assertTrue(c.hasComparison(0))
        assertTrue(fragment().findPreference<FieldChoicePreference>("dynamic_pro_audition")!!.choose(4))
        group().setExpanded(false);layout();assertEquals(0,c.audition);assertFalse(c.hasComparison(0));assertTrue(p.moduleEnabled(prefs.all))
        group().setExpanded(true);layout();assertTrue(fragment().findPreference<FieldChoicePreference>("dynamic_pro_audition")!!.choose(7))
        number(p.bassDelta).requestValue(23);idle();assertEquals(0,c.audition)
        assertTrue(fragment().findPreference<FieldChoicePreference>("dynamic_pro_audition")!!.choose(8));controller!!.pause();idle();assertEquals(0,c.audition)
        c.publishCapabilities(app,268435455);layout();assertFalse(fragment().findPreference<FieldChoicePreference>("dynamic_pro_audition")!!.isEnabled)
    }
    @Test fun actualResponseAndDiagnosticLevelsAreHonestAndCompanderRemainsIndependent() {
        val other=me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache.getPreferences(app,me.timschneeberger.rootlessjamesdsp.utils.Constants.PREF_COMPANDER)
        other.edit().clear().putBoolean("compander_enable",true).putInt("sentinel",37).commit();val before=other.all
        launch();professional(true);optional();prefs.edit().putBoolean(p.ENABLED,true).commit();reading(0);reading(512);layout()
        val summary=fragment().findPreference<Preference>("dynamic_pro_actual")!!.summary.toString()
        assertTrue(summary.contains("48000 Hz"));assertTrue(summary.contains("96000 Hz"))
        assertTrue(summary.contains("当前增强回收 1.37 dB"));assertTrue(summary.contains("输入峰值 -6.31／-6.88"));assertTrue(summary.contains("-34.71"))
        assertEquals(before,other.all);val graph=fragment().findPreference<Preference>("dynamic_pro_response_graph")!!
        assertTrue(graph.summary.toString().contains("L=R"));assertTrue(graph.summary.toString().contains("冻结"))
        val view=row("dynamic_pro_response_graph").findViewById<DynamicResponseView>(R.id.dynamic_response_chart)
        assertTrue(view.onTouchEvent(MotionEvent.obtain(0,1,MotionEvent.ACTION_UP,view.width*.5f,30f,0)));idle()
        assertTrue(graph.summary.toString().contains("相位"));assertTrue(graph.summary.toString().contains("Hz；效果"))
        reading(1024);layout();assertTrue("Point selection survives polling",graph.summary.toString().contains("Hz；效果"))
        prefs.edit().putBoolean(p.RESPONSE,false).commit();layout();assertTrue(graph.summary.toString().contains("等待配套驱动"))
    }
    @Test fun meterAndGraphVisibilityPreserveMatchedAuditionEpochAndNumericRow() {
        launch();professional(true);optional();prefs.edit().putBoolean(p.ENABLED,true).commit();reading(0);reading(512);layout()
        row("dynamic_pro_save_a").performClick();row("dynamic_pro_save_b").performClick();c.measure(app,this);reading(1024,6,3)
        assertTrue(c.setMatch(app,true));assertTrue(fragment().findPreference<FieldChoicePreference>("dynamic_pro_audition")!!.choose(4))
        c.sync(app,536870911,this) { _,_ -> true };val epoch=c.epoch;val original=number(p.bassDelta)
        for(key in listOf(p.METERS,p.RESPONSE)) for(on in listOf(false,true)) {
            fragment().findPreference<SwitchPreferenceCompat>(key)!!.isChecked=on;layout();assertTrue(c.sync(app,536870911,this) { _,_ -> true })
            assertEquals(4,c.audition);assertTrue(c.match);assertEquals(epoch,c.epoch);assertSame(original,number(p.bassDelta))
        }
    }
    @Test fun responseClearsForUnreadyInactiveOrMismatchedPathAndRate() {
        launch();professional(true);optional();prefs.edit().putBoolean(p.ENABLED,true).commit();reading(0);reading(512);layout()
        val graph=fragment().findPreference<Preference>("dynamic_pro_response_graph")!!;assertTrue(graph.summary.toString().contains("48000 Hz"))
        reading(1024,responsePath=1);layout();assertTrue(graph.summary.toString().contains("等待配套驱动"))
        reading(1536);layout();assertTrue(graph.summary.toString().contains("48000 Hz"))
        reading(2048,responseRate=44100);layout();assertTrue(graph.summary.toString().contains("等待配套驱动"))
        reading(2560,ready=false);layout();assertTrue(graph.summary.toString().contains("等待配套驱动"))
        c.publishRuntime(app,emptyList());layout();assertTrue(graph.summary.toString().contains("等待配套驱动"))
    }
}

open class DynamicRasterFixture: DynamicUiFixture() {
    protected fun renderDynamic(w: Int,scale: Int) {
        width=w;RuntimeEnvironment.setFontScale(scale/100f);launch();professional(true);optional()
        prefs.edit().putBoolean(p.ENABLED,true).putInt(p.LEGACY,37).putInt(p.bassDelta.key,23).putInt(p.injectDelta.key,317).putInt(p.qDelta.key,43)
            .putInt(p.deepFreqDelta.key,17).putInt(p.hp.key,27).putInt(p.lp.key,18017).putInt(p.detectorHP.key,73).putInt(p.detectorLP.key,317)
            .putInt(p.threshold.key,-237).putInt(p.mix.key,317).putInt(p.output.key,-29).commit()
        reading(0);reading(512);layout();val folder=File("build/reports/dynamic1").apply { mkdirs() }
        fun complete(view: View,ids: List<Int>) { for(id in ids) {
            val text=view.findViewById<TextView>(id);val lines=requireNotNull(text.layout)
            repeat(lines.lineCount) { assertEquals("No ellipsis: ${text.text}",0,lines.getEllipsisCount(it))
                assertTrue("Text width: ${text.text}",lines.getLineMax(it)<=text.width-text.compoundPaddingLeft-text.compoundPaddingRight+1f) }
            assertTrue("Complete text: ${text.text}",lines.getLineEnd(lines.lineCount-1)>=text.text.length)
        } }
        for(control in p.numbers) complete(row(control.key),listOf(android.R.id.title,R.id.seekbar_value,R.id.bass_reset))
        for(key in p.switches) complete(row(key),listOf(android.R.id.title,android.R.id.summary))
        for(key in listOf("dynamic_pro_baseline","dynamic_pro_help","dynamic_pro_actual","dynamic_pro_compatibility","dynamic_pro_response_graph")) complete(row(key),listOf(android.R.id.title,android.R.id.summary))
        fun shot(name: String,key: String) {
            row(key);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80));layout()
            val view=controller!!.get().findViewById<View>(android.R.id.content);val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(MaterialColors.getColor(view,com.google.android.material.R.attr.colorSurface));view.draw(Canvas(bitmap))
            File(folder,"${w}_${scale}_$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
        shot("baseline",p.BASE);shot("core",p.CORE_ON);shot("tone",p.TONE_ON);shot("adaptive",p.ADAPTIVE_ON);shot("mix",p.MIX_ON)
        shot("tools","dynamic_pro_audition");shot("response","dynamic_pro_response_graph")
        base(4);reading(1024);layout();shot("deep",p.injectDelta.key)
    }
}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class DynamicRaster320_100Test: DynamicRasterFixture() { @Test fun render()=renderDynamic(320,100) }
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class DynamicRaster320_150Test: DynamicRasterFixture() { @Test fun render()=renderDynamic(320,150) }
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class DynamicRaster411_100Test: DynamicRasterFixture() { @Test fun render()=renderDynamic(411,100) }
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class DynamicRaster411_150Test: DynamicRasterFixture() { @Test fun render()=renderDynamic(411,150) }
