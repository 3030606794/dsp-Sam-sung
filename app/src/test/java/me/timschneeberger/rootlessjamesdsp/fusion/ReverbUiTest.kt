package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
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
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
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

open class ReverbUiFixture {
    protected val app:Application get()=RuntimeEnvironment.getApplication()
    protected val p=ReverbParameters
    protected val prefs get()=ReverbController.preferences(app)
    protected var controller:ActivityController<Host>?=null
    protected var width=320
    protected fun idle()=shadowOf(Looper.getMainLooper()).idle()
    protected fun fragment()=controller!!.get().supportFragmentManager.findFragmentByTag("reverb") as ViperEffectPreferenceFragment
    @Before fun prepare(){
        ReverbController.stopAudition(app,clearSnapshots=true);ReverbController.publishRuntime(app,emptyList())
        prefs.edit().clear().commit();ReverbController.publishCapabilities(app,4194303)
    }
    @After fun cleanup(){
        controller?.pause()?.stop()?.destroy();controller=null;idle()
        ReverbController.stopAudition(app,clearSnapshots=true);ReverbController.publishRuntime(app,emptyList())
        RuntimeEnvironment.setFontScale(1f)
    }
    protected fun launch(expanded:Boolean=true){
        controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        fragment().effectHeader.setExpanded(expanded);layout()
    }
    protected fun layout(){
        repeat(2){idle();controller!!.get().findViewById<View>(android.R.id.content).apply {
            measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            layout(0,0,width,2400)
        }};idle()
    }
    @Suppress("RestrictedApi") protected fun row(key:String):View {
        val list=fragment().listView;val adapter=list.adapter as PreferenceGroupAdapter
        val index=adapter.getPreferenceAdapterPosition(key);assertTrue("row $key",index>=0)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index,0);layout()
        return requireNotNull(list.findViewHolderForAdapterPosition(index)).itemView
    }
    protected fun professional(on:Boolean){
        val pref=fragment().findPreference<SwitchPreferenceCompat>(p.PROFESSIONAL)!!
        assertTrue(pref.callChangeListener(on));pref.isChecked=on;layout()
    }
    protected fun optional(on:Boolean=true){
        prefs.edit().putBoolean(p.PREDELAY_ON,on).putBoolean(p.LOW_CUT_ON,on).putBoolean(p.HIGH_CUT_ON,on)
            .putBoolean(p.OUTPUT_ON,on).putBoolean(p.METERS,on).commit();layout()
    }
    protected fun number(c:BassParameters.Control)=fragment().findPreference<BassNumericPreference>(c.key)!!
    protected fun publish(frames:Int,mode:Int=ReverbController.audition,measurement:Int=0){
        val v=IntArray(32);v[0]=1;v[1]=2;v[2]=48000;v[3]=1;v[4]=1;v[5]=mode;v[7]=measurement;v[11]=ReverbController.epoch
        v[13]=137;v[14]=80;v[15]=12000;v[16]=-631;v[17]=-688;v[18]=-1201;v[19]=-1332;v[20]=-427;v[21]=-455
        v[22]=3;v[23]=1;v[24]=1000;v[25]=5000;v[26]=2000;v[30]=1
        ReverbController.publishRuntime(app,listOf(ReverbController.Reading(this,true,frames,48000,p.decode(v))));idle()
    }
    class Host:FragmentActivity(){
        override fun onCreate(savedInstanceState:Bundle?){
            setTheme(R.style.Theme_RootlessJamesDSP);super.onCreate(savedInstanceState)
            setContentView(FragmentContainerView(this).apply {id=R.id.viper_effect_reverb})
            if(savedInstanceState==null)supportFragmentManager.beginTransaction()
                .add(R.id.viper_effect_reverb,ViperEffectPreferenceFragment.newInstance("v_reverb"),"reverb").commitNow()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReverbUiTest:ReverbUiFixture(){
    @Test fun cardKeepsItsTitlePowerAndCollapsedRulesWithoutInitializingProfessionalNumbers(){
        prefs.edit().putInt("v_reverb_size",63).commit();launch(false)
        assertEquals("ViPER 混响",fragment().effectHeader.title);assertEquals(1,fragment().listView.adapter!!.itemCount)
        row("v_reverb").performClick();layout();assertTrue(fragment().effectHeader.isExpanded)
        assertFalse(p.moduleEnabled(prefs.all));assertFalse(p.enabled(prefs.all));assertEquals(63,p.legacy(prefs.all)[0])
        p.numbers.forEach {assertFalse("Uninitialized ${it.key}",prefs.contains(it.key))}
        p.switches.forEach {assertFalse(p.on(prefs.all,it))}
    }
    @Test fun professionalGreysAllFiveLegacyControlsAndClosingRestoresTheirSavedValues(){
        prefs.edit().putInt("v_reverb_size",63).putInt("v_reverb_dry",47).commit();launch();professional(true)
        for(c in p.legacyControls){
            val pref=fragment().findPreference<ViperSliderPreference>(c.key)!!
            assertFalse(pref.isEnabled);assertTrue(pref.summary.toString().contains("专业调节已接管"))
            assertFalse(row(c.key).findViewById<Slider>(R.id.seekbar).isEnabled)
        }
        assertEquals(630,number(p.size).value);assertEquals(470,number(p.dry).value)
        assertTrue(number(p.size).requestValue(637));professional(false)
        p.legacyControls.forEach {assertTrue(fragment().findPreference<ViperSliderPreference>(it.key)!!.isEnabled)}
        assertEquals(63,p.legacy(prefs.all)[0]);professional(true);assertEquals(637,number(p.size).value)
        assertFalse(p.moduleEnabled(prefs.all))
    }
    @Test fun restoredProfessionalProfileKeepsSwitchAndTakeoverInAgreement(){
        p.apply(prefs,ReverbParameters.Settings(legacy=listOf(63,24,37,19,47),professional=true,size=637))
        launch();assertTrue(fragment().findPreference<SwitchPreferenceCompat>(p.PROFESSIONAL)!!.isChecked)
        assertFalse(fragment().findPreference<ViperSliderPreference>("v_reverb_size")!!.isEnabled)
        assertEquals(637,number(p.size).value);professional(false)
        assertEquals(63,fragment().findPreference<ViperSliderPreference>("v_reverb_size")!!.value)
    }
    @Test fun aLegacyInputOpenedBeforeTakeoverCannotCommitAfterProfessionalStarts(){
        launch();row("v_reverb_size").performClick();idle()
        val dialog=ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<EditText>(android.R.id.text1)!!.setText("27")
        professional(true);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle()
        assertEquals(0,p.legacy(prefs.all)[0]);dialog.dismiss()
    }
    @Test fun everyProfessionalNumberSupportsInputPlusMinusAndSingleReset(){
        launch();professional(true);optional()
        for(c in p.numbers){
            if(c==p.mix){prefs.edit().putBoolean(p.LINKED,true).commit();layout()}
            row(c.key).findViewById<View>(R.id.seekbar_value).performClick();idle()
            val dialog=ShadowDialog.getLatestDialog() as AlertDialog
            dialog.findViewById<EditText>(android.R.id.text1)!!.setText(c.number(c.min+2))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();layout()
            assertEquals(c.min+2,number(c).value);assertTrue(number(c).step(1));assertEquals(c.min+3,p.value(prefs.all,c))
            assertTrue(number(c).step(-1));assertEquals(c.min+2,p.value(prefs.all,c))
            assertTrue(number(c).resetToDefault());assertEquals(c.default,p.value(prefs.all,c))
        }
        assertFalse(p.moduleEnabled(prefs.all));assertEquals(0,ReverbController.audition)
    }
    @Test fun linkingGreysIndependentLevelsAndUnlinkingRestoresThemWhileOptionalSwitchesGateTheirRows(){
        launch();professional(true);number(p.wet).requestValue(137);number(p.dry).requestValue(472)
        assertFalse(number(p.predelay).isEnabled);assertFalse(number(p.predelay).requestValue(137))
        optional();assertTrue(number(p.predelay).requestValue(137))
        row(p.wet.key).findViewById<View>(R.id.seekbar_value).performClick();idle();val dialog=ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<EditText>(android.R.id.text1)!!.setText("19.2")
        prefs.edit().putBoolean(p.LINKED,true).commit();layout()
        assertFalse(number(p.wet).isEnabled);assertFalse(number(p.dry).isEnabled);assertTrue(number(p.mix).isEnabled)
        assertFalse(number(p.wet).step(1));assertFalse(number(p.wet).resetToDefault())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertEquals(137,p.value(prefs.all,p.wet));dialog.dismiss()
        prefs.edit().putBoolean(p.LINKED,false).commit();layout()
        assertTrue(number(p.wet).isEnabled);assertEquals(137,number(p.wet).value);assertEquals(472,number(p.dry).value)
    }
    @Test fun oldDriverAllowsEditingButExplainsFallbackAndDisablesAudition(){
        launch();professional(true);optional();ReverbController.publishCapabilities(app,2097151);layout()
        assertTrue(number(p.predelay).requestValue(137));assertEquals(137,p.value(prefs.all,p.predelay))
        assertFalse(fragment().findPreference<FieldChoicePreference>("v_reverb_pro_audition")!!.isEnabled)
        assertTrue(fragment().findPreference<Preference>("v_reverb_pro_compatibility")!!.summary.toString().contains("更新配套驱动"))
        assertFalse(p.moduleEnabled(prefs.all))
    }
    @Test fun libraryStopsPlaybackRetainsSnapshotsAndCollapseClearsThem(){
        launch();professional(true);prefs.edit().putBoolean(p.ENABLED,true).commit();publish(0);publish(512)
        row("v_reverb_pro_save_a").performClick();row("v_reverb_pro_save_b").performClick();idle()
        assertTrue(ReverbController.hasComparison(0));assertTrue(ReverbController.hasComparison(1))
        assertTrue(fragment().findPreference<FieldChoicePreference>("v_reverb_pro_audition")!!.choose(4))
        row("v_reverb_pro_presets").performClick();idle();assertEquals(0,ReverbController.audition)
        assertTrue(ReverbController.hasComparison(0));(ShadowDialog.getLatestDialog() as AlertDialog).dismiss()
        fragment().effectHeader.setExpanded(false);idle();assertFalse(ReverbController.hasComparison(0))
        assertTrue(p.moduleEnabled(prefs.all))
    }
    @Test fun pauseAndPageDestructionClearSnapshotsWithoutChangingSavedAudioSettings(){
        launch();professional(true);row("v_reverb_pro_save_a").performClick();idle();assertTrue(ReverbController.hasComparison(0))
        val before=p.capture(prefs.all);controller!!.pause();idle();assertFalse(ReverbController.hasComparison(0));assertEquals(before,p.capture(prefs.all))
    }
    @Test fun realRuntimeShowsSeparateWarmMeasureAndSamplePeakState(){
        launch();professional(true);optional();prefs.edit().putBoolean(p.ENABLED,true).commit();publish(0);publish(512,measurement=1)
        val pref=fragment().findPreference<Preference>("v_reverb_pro_actual")!!
        assertTrue(pref.summary.toString().contains("预热 1000 / 5000 ms"));assertTrue(pref.summary.toString().contains("输出 -4.27／-4.55"))
        publish(1024,measurement=2);assertTrue(pref.summary.toString().contains("电平测量 0 / 2000 ms"))
    }
}

open class ReverbRasterFixture:ReverbUiFixture(){
    protected fun renderNew(w:Int,scale:Int){
        width=w;RuntimeEnvironment.setFontScale(scale/100f);prefs.edit().putInt("v_reverb_size",63).putInt("v_reverb_wet",19).commit();launch()
        val folder=File("build/reports/reverb").apply {mkdirs()}
        fun shot(name:String){
            val v=controller!!.get().findViewById<View>(android.R.id.content)
            val bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(MaterialColors.getColor(v,com.google.android.material.R.attr.colorSurface));v.draw(Canvas(bitmap))
            File(folder,"${w}_${scale}_$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
        fun complete(view:View,ids:List<Int>){for(id in ids){
            val text=view.findViewById<TextView>(id);val lines=requireNotNull(text.layout)
            repeat(lines.lineCount){assertEquals("No ellipsis: ${text.text}",0,lines.getEllipsisCount(it))
                assertTrue("Text width: ${text.text}",lines.getLineMax(it)<=text.width-text.compoundPaddingLeft-text.compoundPaddingRight+1f)}
            assertTrue("Complete text: ${text.text}",lines.getLineEnd(lines.lineCount-1)>=text.text.length)
        }}
        shot("legacy");professional(true);row("v_reverb_size");shot("legacy_grey")
        optional()
        for(c in p.numbers)complete(row(c.key),listOf(android.R.id.title,R.id.seekbar_value,R.id.bass_reset))
        row(p.size.key);shot("professional")
        prefs.edit().putBoolean(p.LINKED,true).commit();layout();row(p.wet.key);shot("linked_grey")
        for(key in listOf(p.PROFESSIONAL,p.LINKED,p.PREDELAY_ON,p.LOW_CUT_ON,p.HIGH_CUT_ON,p.OUTPUT_ON,p.METERS))
            complete(row(key),listOf(android.R.id.title,android.R.id.summary))
        for(key in listOf("v_reverb_pro_presets","v_reverb_pro_save_a","v_reverb_pro_save_b","v_reverb_pro_measure","v_reverb_pro_stop"))
            complete(row(key),listOf(android.R.id.title))
        row(p.predelay.key);shot("filters")
        prefs.edit().putBoolean(p.ENABLED,true).commit();publish(0);publish(512,measurement=1)
        row("v_reverb_pro_actual");shot("meters")
        fragment().effectHeader.setExpanded(false);layout();shot("collapsed")
    }
}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class ReverbRaster320_100Test:ReverbRasterFixture(){@Test fun render()=renderNew(320,100)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class ReverbRaster320_150Test:ReverbRasterFixture(){@Test fun render()=renderNew(320,150)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class ReverbRaster411_100Test:ReverbRasterFixture(){@Test fun render()=renderNew(411,100)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class ReverbRaster411_150Test:ReverbRasterFixture(){@Test fun render()=renderNew(411,150)}
