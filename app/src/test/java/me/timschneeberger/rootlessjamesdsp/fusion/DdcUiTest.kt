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
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

open class DdcUiFixture {
    protected val app:Application get()=RuntimeEnvironment.getApplication()
    protected val p=DdcParameters;protected val c=DdcController
    protected val prefs get()=c.preferences(app)
    protected var controller:ActivityController<Host>?=null
    protected var width=320
    protected val session=Any()
    protected lateinit var source:DdcFoundation
    protected fun idle()=shadowOf(Looper.getMainLooper()).idle()
    protected fun fragment()=controller!!.get().supportFragmentManager.findFragmentByTag("ddc") as DdcPreferenceFragment
    protected fun group()=fragment().findPreference<SwitchPreferenceGroup>(p.ENABLED)!!
    @Before fun prepare(){stopKoin();val preferences=Preferences(app);startKoin {modules(module {single {preferences.App()}})};RuntimeEnvironment.setFontScale(1f);prefs.edit().clear().commit();c.stopAudition(app,clearSnapshots=true);c.publishRuntime(app,emptyList());c.publishCapabilities(app,2)
        val file=File(app.getExternalFilesDir(null),"DDC/test.vdc");file.parentFile!!.mkdirs();file.writeText("SR_44100:1,0,0,0,0\nSR_48000:1,0,0,0,0",Charsets.US_ASCII);source=DdcFoundation.selected(app,"DDC/test.vdc");prefs.edit().putString(p.FILE,"DDC/test.vdc").commit();c.publishFile(app,session,source.path,source.path,source.sha256,source.label)
    }
    @After fun cleanup(){controller?.pause()?.stop()?.destroy();controller=null;idle();c.sessionClosed(app,session);c.stopAudition(app,clearSnapshots=true);c.publishRuntime(app,emptyList());RuntimeEnvironment.setFontScale(1f);stopKoin()}
    protected fun launch(expanded:Boolean=true){controller=Robolectric.buildActivity(Host::class.java).setup().visible();group().setExpanded(expanded);layout()}
    protected fun layout(){repeat(2){idle();controller!!.get().findViewById<View>(android.R.id.content).apply {measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY));layout(0,0,width,2400)}};idle()}
    @Suppress("RestrictedApi") protected fun row(key:String):View{val list=fragment().listView;val adapter=list.adapter as PreferenceGroupAdapter;val index=adapter.getPreferenceAdapterPosition(key);assertTrue("Visible row $key",index>=0);(list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index,0);layout();return requireNotNull(list.findViewHolderForAdapterPosition(index)).itemView}
    protected fun professional(on:Boolean){val pref=fragment().findPreference<SwitchPreferenceCompat>(p.PROFESSIONAL)!!;assertTrue(pref.callChangeListener(on));pref.isChecked=on;layout()}
    protected fun optional(on:Boolean=true){val e=prefs.edit();p.switches.filterNot {it in setOf(p.PROFESSIONAL,p.LINKED)}.forEach {e.putBoolean(it,on)};e.putBoolean(p.LINKED,false).commit();layout()}
    protected fun number(control:BassParameters.Control)=fragment().findPreference<BassNumericPreference>(control.key)!!
    protected fun response(rate:Int=48000,epoch:Int=2):DdcParameters.Response {val v=IntArray(232).apply {this[0]=1;this[1]=epoch;this[2]=rate;this[3]=1;this[4]=32;for(i in 0 until 32){this[8+i*7]=20+i*500;this[9+i*7]=-137+i*7;this[10+i*7]=-317+i*6;this[11+i*7]=-417+i*6;this[14+i*7]=1}};return requireNotNull(p.decodeResponse(v))}
    protected fun reading(frames:Int,mode:Int=c.audition,stage:Int=0,responseRate:Int=48000){val v=IntArray(80).apply {this[0]=1;this[1]=2;this[2]=48000;this[3]=1;this[4]=1;this[5]=1;this[6]=mode;this[8]=stage;this[12]=c.epoch;this[13]=1;this[14]=1;this[15]=1;this[16]=96000;this[17]=48000;this[18]=1;this[20]=-631;this[21]=-688;this[22]=-427;this[23]=-455;this[24]=-1337;this[25]=-1413;this[26]=-1137;this[27]=-1213;this[28]=3;this[29]=-317;this[30]=-29;this[31]=13;this[32]=317;this[33]=417;this[34]=-13;this[35]=-23;this[44]=120;this[45]=8000;this[46]=1000;this[47]=6000;this[59]=137;p.shaWords(source.sha256).copyInto(this,36);p.shaWords(source.sha256).copyInto(this,62)};c.publishRuntime(app,listOf(DdcController.Reading(session,true,frames,96000,p.decode(v),response=response(responseRate))));idle()}
    class Host:FragmentActivity(){override fun onCreate(state:Bundle?){setTheme(R.style.Theme_RootlessJamesDSP);super.onCreate(state);setContentView(FragmentContainerView(this).apply {id=R.id.card_ddc});if(state==null)supportFragmentManager.beginTransaction().add(R.id.card_ddc,DdcPreferenceFragment(),"ddc").commitNow()}}
}

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class DdcUiTest:DdcUiFixture(){
    @Test fun originalFileAndPowerFoldingRemainWithAllAddedGroupsOff(){launch(false);assertEquals(1,fragment().listView.adapter!!.itemCount);row(p.ENABLED).performClick();layout();assertTrue(group().isExpanded);assertFalse(group().isChecked);assertFalse(p.moduleEnabled(prefs.all));assertNotNull(fragment().findPreference<FileLibraryPreference>(p.FILE));professional(true);p.numbers.forEach {assertFalse(number(it).isEnabled)};assertEquals(source.path,prefs.getString(p.FILE,null))}
    @Test fun allTwentyNumbersAcceptNumericInputIncrementDecrementAndNeutralReset(){launch();professional(true);optional();for(control in p.numbers){val n=if(control.default>=control.max-2)control.default-2 else control.default+2;assertTrue(control.key,number(control).isEnabled);row(control.key).findViewById<View>(R.id.seekbar_value).performClick();idle();val dialog=ShadowDialog.getLatestDialog() as AlertDialog;dialog.findViewById<EditText>(android.R.id.text1)!!.setText(control.number(n));dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();layout();assertEquals(control.key,n,number(control).value);row(control.key).findViewById<View>(R.id.bass_increment).performClick();idle();assertEquals(n+1,p.value(prefs.all,control));row(control.key).findViewById<View>(R.id.bass_decrement).performClick();idle();assertEquals(n,p.value(prefs.all,control));row(control.key).findViewById<View>(R.id.bass_reset).performClick();idle();assertEquals(control.default,p.value(prefs.all,control))};assertEquals(source.path,prefs.getString(p.FILE,null));assertFalse(p.moduleEnabled(prefs.all))}
    @Test fun disabledGroupsGreyControlsAndRestoreEverySavedValue(){launch();professional(true);optional();number(p.lowGain).requestValue(37);for((flag,controls)in listOf(p.RATIO_ON to listOf(p.ratio),p.COMPENSATION_ON to p.compensationNumbers,p.EAR_ON to p.earNumbers,p.PREAMP_ON to listOf(p.preamp),p.AUTO_HEADROOM_ON to listOf(p.margin),p.OUTPUT_ON to listOf(p.output))){val before=p.capture(prefs.all);prefs.edit().putBoolean(flag,false).commit();layout();controls.forEach {assertFalse(number(it).isEnabled);assertFalse(number(it).step(1));assertFalse(number(it).resetToDefault());assertEquals(.38f,row(it.key).findViewById<TextView>(R.id.seekbar_value).alpha,.001f)};prefs.edit().putBoolean(flag,true).commit();layout();assertEquals(before,p.capture(prefs.all));controls.forEach {assertTrue(number(it).isEnabled)}};prefs.edit().putBoolean(p.LOW_ON,false).commit();layout();assertFalse(number(p.lowGain).isEnabled);assertTrue(number(p.highGain).isEnabled)}
    @Test fun linkedRightEarValuesAreGreyAndNeverOverwrittenByLeftEditsOrReset(){launch();professional(true);optional();number(p.rightRatio).requestValue(317);number(p.rightLevel).requestValue(-29);prefs.edit().putBoolean(p.LINKED,true).commit();layout();assertFalse(number(p.rightRatio).isEnabled);assertFalse(number(p.rightLevel).isEnabled);assertFalse(number(p.rightRatio).requestValue(1000));assertTrue(number(p.leftRatio).requestValue(731));assertTrue(number(p.leftLevel).requestValue(-13));assertTrue(number(p.leftRatio).resetToDefault());assertEquals(317,p.value(prefs.all,p.rightRatio));assertEquals(-29,p.value(prefs.all,p.rightLevel));prefs.edit().putBoolean(p.LINKED,false).commit();layout();assertTrue(number(p.rightRatio).isEnabled);assertEquals(317,number(p.rightRatio).value);assertEquals(-29,number(p.rightLevel).value)}
    @Test fun professionalOffRestoresOriginalFileWithoutResettingStoredPersonalCorrections(){launch();professional(true);optional();number(p.lowGain).requestValue(37);number(p.ratio).requestValue(317);val before=p.capture(prefs.all);professional(false);assertNull(fragment().findPreference<BassNumericPreference>(p.lowGain.key));assertTrue(fragment().findPreference<FileLibraryPreference>(p.FILE)!!.isEnabled);assertEquals(source.path,prefs.getString(p.FILE,null));professional(true);assertEquals(before,p.capture(prefs.all));assertEquals(37,number(p.lowGain).value);assertEquals(317,number(p.ratio).value)}
    @Test fun numericDialogOpenedBeforeAFileSwitchCannotWriteEvenAfterReturning(){launch();professional(true);optional();number(p.lowGain).requestValue(37);row(p.lowGain.key).findViewById<View>(R.id.seekbar_value).performClick();idle();val dialog=ShadowDialog.getLatestDialog() as AlertDialog;dialog.findViewById<EditText>(android.R.id.text1)!!.setText("7.3");prefs.edit().putString(p.FILE,"DDC/missing.vdc").commit();layout();prefs.edit().putString(p.FILE,source.path).commit();layout();dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertEquals(37,p.value(prefs.all,p.lowGain));assertTrue(dialog.isShowing);dialog.dismiss()}
    @Test fun numericDialogOpenedBeforeGroupDisableCannotWriteAfterReenable(){launch();professional(true);optional();number(p.lowGain).requestValue(37);row(p.lowGain.key).findViewById<View>(R.id.seekbar_value).performClick();idle();val dialog=ShadowDialog.getLatestDialog() as AlertDialog;dialog.findViewById<EditText>(android.R.id.text1)!!.setText("7.3");prefs.edit().putBoolean(p.LOW_ON,false).commit();layout();prefs.edit().putBoolean(p.LOW_ON,true).commit();layout();dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertEquals(37,p.value(prefs.all,p.lowGain));assertTrue(dialog.isShowing);dialog.dismiss()}
    @Test fun responseAndMetersDistinguishFileLeftRightAndInternalHostRates(){val other=ToneAdvancedController.preferences(app);other.edit().clear().putInt("sentinel",73).commit();val before=other.all;launch();professional(true);optional();prefs.edit().putBoolean(p.ENABLED,true).commit();reading(0);reading(512);layout();val actual=fragment().findPreference<Preference>("ddc_pro_actual")!!.summary.toString();assertTrue(actual.contains("内部 48000 Hz；宿主 96000 Hz"));assertTrue(actual.contains("自动衰减 -3.17 dB"));assertTrue(actual.contains("31.7%／41.7%"));assertTrue(actual.contains("最终试听输出峰值"));assertEquals(before,other.all);val graph=fragment().findPreference<Preference>("ddc_pro_response_graph")!!;assertTrue(graph.summary.toString().contains("不是耳机实测频响"));val chart=row(graph.key).findViewById<DdcResponseView>(R.id.ddc_response_chart);assertTrue(chart.onTouchEvent(MotionEvent.obtain(0,1,MotionEvent.ACTION_UP,chart.width*.5f,30f,0)));idle();assertTrue(graph.summary.toString().contains("左输出"));reading(1024,responseRate=44100);layout();assertTrue(graph.summary.toString().contains("等待配套驱动"))}
    @Test fun foldPauseAndFileSwitchClearTemporaryToolsPreservingSavedSettings(){launch();professional(true);optional();prefs.edit().putBoolean(p.ENABLED,true).commit();reading(0);reading(512);layout();row("ddc_pro_save_a").performClick();assertTrue(fragment().findPreference<FieldChoicePreference>("ddc_pro_audition")!!.choose(2));val before=p.capture(prefs.all);group().setExpanded(false);layout();assertFalse(c.hasComparison(0));assertEquals(0,c.audition);assertEquals(before,p.capture(prefs.all));assertTrue(p.moduleEnabled(prefs.all));group().setExpanded(true);layout();reading(1024);row("ddc_pro_save_a").performClick();prefs.edit().putString(p.FILE,"DDC/missing.vdc").commit();layout();assertFalse(c.hasComparison(0));prefs.edit().putString(p.FILE,source.path).commit();layout();row("ddc_pro_save_a").performClick();controller!!.pause();idle();assertFalse(c.hasComparison(0))}
    @Test fun invalidFileAndUnsupportedDriverHaveClearStatusAndNeverLockFileSelection(){launch();professional(true);optional();c.publishCapabilities(app,0);layout();assertTrue(fragment().findPreference<Preference>("ddc_pro_compatibility")!!.summary.toString().contains("实际继续原文件 DDC"));assertFalse(fragment().findPreference<Preference>("ddc_pro_audition")!!.isEnabled);prefs.edit().putString(p.FILE,"DDC/missing.vdc").commit();c.publishFile(app,session,"DDC/missing.vdc",source.path,source.sha256,source.label,"找不到所选文件");layout();assertTrue(fragment().findPreference<Preference>("ddc_pro_baseline")!!.summary.toString().contains("上一有效校正保留"));assertFalse(number(p.lowGain).isEnabled);assertFalse(fragment().findPreference<Preference>("ddc_pro_save_a")!!.isEnabled);assertTrue(fragment().findPreference<FileLibraryPreference>(p.FILE)!!.isEnabled);assertEquals("DDC/missing.vdc",prefs.getString(p.FILE,null))}
    @Test fun retainedFragmentViewRecreationRebindsControlsPreservingPowerFoldAndSavedParameters(){launch();professional(true);optional();group().setValue(true);number(p.lowGain).requestValue(37);reading(0);reading(512);layout();row("ddc_pro_save_a").performClick();assertTrue(c.hasComparison(0));val oldRow=number(p.lowGain);val retained=fragment();val before=p.capture(prefs.all);val manager=controller!!.get().supportFragmentManager;manager.beginTransaction().detach(retained).commitNow();idle();assertFalse(c.hasComparison(0));manager.beginTransaction().attach(retained).commitNow();layout();assertSame(retained,fragment());assertTrue(group().isExpanded);assertTrue(group().isChecked);assertEquals(before,p.capture(prefs.all));assertEquals(source.path,prefs.getString(p.FILE,null));assertNotSame(oldRow,number(p.lowGain));assertFalse(oldRow.requestValue(99));assertTrue(number(p.lowGain).step(1));assertEquals(38,p.value(prefs.all,p.lowGain));professional(false);assertFalse(p.enabled(prefs.all));assertTrue(fragment().findPreference<FileLibraryPreference>(p.FILE)!!.isEnabled)}
}

open class DdcRasterFixture:DdcUiFixture(){
    protected fun renderDdc(w:Int,scale:Int){width=w;RuntimeEnvironment.setFontScale(scale/100f);launch();professional(true);optional();prefs.edit().putBoolean(p.ENABLED,true).putInt(p.ratio.key,731).putInt(p.lowGain.key,37).putInt(p.highGain.key,-29).putInt(p.lowSlope.key,731).putInt(p.peak1Q.key,137).putInt(p.peak2Gain.key,-37).putInt(p.leftRatio.key,731).putInt(p.rightRatio.key,317).putInt(p.leftLevel.key,-13).putInt(p.rightLevel.key,-29).putInt(p.preamp.key,-29).putInt(p.margin.key,13).putInt(p.output.key,13).commit();reading(0);reading(512);layout()
        val folder=File("build/reports/ddc1").apply {mkdirs()}
        fun complete(view:View,ids:List<Int>){for(id in ids){val text=view.findViewById<TextView>(id);val lines=requireNotNull(text.layout);repeat(lines.lineCount){assertEquals("No ellipsis: ${text.text}",0,lines.getEllipsisCount(it));assertTrue("Text width: ${text.text}",lines.getLineMax(it)<=text.width-text.compoundPaddingLeft-text.compoundPaddingRight+1f)};assertTrue("Complete text: ${text.text}",lines.getLineEnd(lines.lineCount-1)>=text.text.length)}}
        p.numbers.forEach {complete(row(it.key),listOf(android.R.id.title,R.id.seekbar_value,R.id.bass_reset))};p.switches.forEach {complete(row(it),listOf(android.R.id.title,android.R.id.summary))};for(key in listOf("ddc_pro_baseline","ddc_pro_help","ddc_pro_actual","ddc_pro_compatibility","ddc_pro_response_graph"))complete(row(key),listOf(android.R.id.title,android.R.id.summary))
        fun shot(name:String,key:String){row(key);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80));layout();val view=controller!!.get().findViewById<View>(android.R.id.content);val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(MaterialColors.getColor(view,com.google.android.material.R.attr.colorSurface));view.draw(Canvas(bitmap));File(folder,"${w}_${scale}_$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        shot("original",p.FILE);shot("ratio",p.RATIO_ON);shot("low",p.LOW_ON);shot("high",p.HIGH_ON);shot("peak1",p.PEAK1_ON);shot("peak2",p.PEAK2_ON);shot("ears",p.EAR_ON);shot("headroom",p.PREAMP_ON);shot("output",p.OUTPUT_ON);shot("tools","ddc_pro_audition");shot("graph","ddc_pro_response_graph");optional(false);layout();shot("disabled",p.COMPENSATION_ON)
    }
}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class DdcRaster320_100Test:DdcRasterFixture(){@Test fun render()=renderDdc(320,100)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class DdcRaster320_150Test:DdcRasterFixture(){@Test fun render()=renderDdc(320,150)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class DdcRaster411_100Test:DdcRasterFixture(){@Test fun render()=renderDdc(411,100)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class DdcRaster411_150Test:DdcRasterFixture(){@Test fun render()=renderDdc(411,150)}
