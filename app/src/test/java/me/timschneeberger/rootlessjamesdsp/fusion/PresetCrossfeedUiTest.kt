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
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fragment.PreferenceGroupFragment
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
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

open class PresetCrossfeedUiFixture {
    protected val app:Application get()=RuntimeEnvironment.getApplication()
    protected val p=PresetCrossfeedParameters
    protected val prefs get()=PresetCrossfeedController.preferences(app)
    protected var controller:ActivityController<Host>?=null
    protected var width=320
    protected fun idle()=shadowOf(Looper.getMainLooper()).idle()
    protected fun fragment()=controller!!.get().supportFragmentManager.findFragmentByTag("six-crossfeed") as PreferenceGroupFragment
    protected fun group()=fragment().findPreference<SwitchPreferenceGroup>(p.ENABLED)!!
    @Before fun prepare(){stopKoin();val preferences=Preferences(app);startKoin {modules(module {single {preferences.App()}})}
        RuntimeEnvironment.setFontScale(1f);prefs.edit().clear().commit();CrossfeedController.preferences(app).edit().clear().commit()
        PresetCrossfeedController.stopAudition(app,clearSnapshots=true);PresetCrossfeedController.publishRuntime(app,emptyList());PresetCrossfeedController.publishCapabilities(app,2097151)}
    @After fun cleanup(){controller?.pause()?.stop()?.destroy();controller=null;idle();PresetCrossfeedController.stopAudition(app,clearSnapshots=true);PresetCrossfeedController.publishRuntime(app,emptyList());RuntimeEnvironment.setFontScale(1f);stopKoin()}
    protected fun launch(expanded:Boolean=true){controller=Robolectric.buildActivity(Host::class.java).setup().visible();group().setExpanded(expanded);layout()}
    protected fun layout(){repeat(2){idle();controller!!.get().findViewById<View>(android.R.id.content).apply {
        measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY));layout(0,0,width,2400)}};idle()}
    @Suppress("RestrictedApi") protected fun row(key:String):View{val list=fragment().listView;val adapter=list.adapter as PreferenceGroupAdapter;val i=adapter.getPreferenceAdapterPosition(key)
        assertTrue("Visible $key",i>=0);(list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i,0);layout();return requireNotNull(list.findViewHolderForAdapterPosition(i)).itemView}
    protected fun professional(value:Boolean){val pref=fragment().findPreference<SwitchPreferenceCompat>(p.PROFESSIONAL)!!;pref.isChecked=value;layout()}
    protected fun base(mode:Int){fragment().findPreference<ListPreference>(p.BASE)!!.callChangeListener(mode.toString());layout()}
    protected fun number(c:BassParameters.Control)=fragment().findPreference<BassNumericPreference>(c.key)!!
    protected fun active(){val identity=Any();val v=IntArray(24);v[0]=1;v[1]=2;v[2]=48000;v[3]=1;v[4]=1;v[12]=p.base(prefs.all);v[23]=1
        for(frames in listOf(0,512))PresetCrossfeedController.publishRuntime(app,listOf(PresetCrossfeedController.Reading(identity,true,frames,48000,p.decode(v))));layout()}
    class Host:FragmentActivity(){override fun onCreate(state:Bundle?){setTheme(R.style.Theme_RootlessJamesDSP);super.onCreate(state)
        setContentView(FragmentContainerView(this).apply {id=R.id.card_crossfeed})
        if(state==null)supportFragmentManager.beginTransaction().add(R.id.card_crossfeed,PreferenceGroupFragment.newInstance(PresetCrossfeedParameters.NAMESPACE,R.xml.dsp_crossfeed_preferences),"six-crossfeed").commitNow()}}
}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class PresetCrossfeedUiTest:PresetCrossfeedUiFixture(){
    @Test fun powerAndFoldRemainIndependentAndSixBasesRemainSelectable(){
        launch(false);assertEquals(1,fragment().listView.adapter!!.itemCount);row(p.ENABLED).performClick();layout();assertTrue(group().isExpanded);assertFalse(group().isChecked)
        assertFalse(p.enabled(prefs.all));assertEquals(5,p.base(prefs.all));professional(true)
        for(mode in 0..5){base(mode);assertTrue(fragment().findPreference<ListPreference>(p.BASE)!!.isEnabled);assertEquals(mode,p.base(prefs.all));assertTrue(p.enabled(prefs.all));assertFalse(group().isChecked)
            assertEquals(mode<2,fragment().findPreference<BassNumericPreference>(p.cutoff.key)!=null);assertEquals(mode>=2,fragment().findPreference<BassNumericPreference>(p.spatial.key)!=null)}
    }
    @Test fun allApplicableNumbersHaveInputButtonsAndBaseSpecificReset(){
        launch();professional(true)
        for(mode in listOf(1,3)){base(mode);val controls=if(mode<2)listOf(p.cutoff,p.attenuation,p.mix,p.delay,p.output)else listOf(p.spatial,p.mix,p.delay,p.output)
            for(c in controls){row(c.key).findViewById<View>(R.id.seekbar_value).performClick();idle();val dialog=ShadowDialog.getLatestDialog() as AlertDialog
                dialog.findViewById<EditText>(android.R.id.text1)!!.setText(c.number(c.min+2));dialog.getButton(-1).performClick();layout();assertEquals(c.min+2,number(c).value)
                row(c.key).findViewById<View>(R.id.bass_increment).performClick();idle();assertEquals(c.min+3,number(c).value)
                row(c.key).findViewById<View>(R.id.bass_decrement).performClick();idle();assertEquals(c.min+2,number(c).value)
                row(c.key).findViewById<View>(R.id.bass_reset).performClick();idle();assertEquals(p.defaultFor(mode,c),number(c).value)
            }
        }
        assertFalse(prefs.getBoolean(p.ENABLED,false));assertTrue(CrossfeedController.preferences(app).all.isEmpty())
    }
    @Test fun advancedGroupsDefaultOffLinkedRowGraysAndEveryNumberSupportsEditing(){
        launch();professional(true);PresetCrossfeedController.publishCapabilities(app,16777215);layout()
        fun toggle(key:String,on:Boolean){fragment().findPreference<SwitchPreferenceCompat>(key)!!.isChecked=on;layout()}
        for(key in listOf(p.PATH_ON,p.FILTER_ON,p.CENTER_ON))assertFalse(fragment().findPreference<SwitchPreferenceCompat>(key)!!.isChecked)
        for(c in p.advancedNumbers)assertFalse(number(c).isEnabled)
        toggle(p.PATH_ON,true);assertTrue(number(p.leftToRight).isEnabled);assertFalse(number(p.rightToLeft).isEnabled)
        toggle(p.PATH_LINK,false);assertTrue(number(p.rightToLeft).isEnabled)
        toggle(p.FILTER_ON,true);toggle(p.CENTER_ON,true)
        for(c in p.advancedNumbers){
            row(c.key).findViewById<View>(R.id.seekbar_value).performClick();idle();val dialog=ShadowDialog.getLatestDialog() as AlertDialog
            dialog.findViewById<EditText>(android.R.id.text1)!!.setText(c.number(c.min+2));dialog.getButton(-1).performClick();layout()
            assertEquals(c.min+2,number(c).value);row(c.key).findViewById<View>(R.id.bass_increment).performClick();idle();assertEquals(c.min+3,number(c).value)
            row(c.key).findViewById<View>(R.id.bass_decrement).performClick();idle();assertEquals(c.min+2,number(c).value)
            row(c.key).findViewById<View>(R.id.bass_reset).performClick();idle();assertEquals(c.default,number(c).value)
        }
        number(p.rightToLeft).requestValue(-31);toggle(p.PATH_LINK,true);assertFalse(number(p.rightToLeft).isEnabled)
        assertEquals(-31,number(p.rightToLeft).value);toggle(p.PATH_LINK,false);assertTrue(number(p.rightToLeft).isEnabled);assertEquals(-31,number(p.rightToLeft).value)
        assertTrue(fragment().findPreference<FieldChoicePreference>(p.FILTER_SLOPE)!!.choose(1));assertEquals(2,prefs.getInt(p.FILTER_SLOPE,0))
        professional(false);assertNull(fragment().findPreference<Preference>(p.PATH_ON));professional(true);assertEquals(-31,number(p.rightToLeft).value)
        assertFalse(prefs.getBoolean(p.ENABLED,false));assertTrue(CrossfeedController.preferences(app).all.isEmpty())
    }
    @Test fun choosingAnotherBaseLoadsNeutralFineSettingsAndDisablingRetainsTheBase(){
        launch();professional(true);base(0);number(p.mix).requestValue(43);number(p.delay).requestValue(137);base(1)
        assertEquals(650,number(p.cutoff).value);assertEquals(95,number(p.attenuation).value);assertEquals(100,number(p.mix).value);assertEquals(0,number(p.delay).value)
        professional(false);assertEquals(1,p.base(prefs.all));assertNull(fragment().findPreference<BassNumericPreference>(p.cutoff.key));assertTrue(fragment().findPreference<ListPreference>(p.BASE)!!.isEnabled)
        professional(true);assertEquals(650,number(p.cutoff).value)
    }
    @Test fun aSnapshotSurvivesBaseSelectionForCrossPresetComparison(){
        launch();professional(true);base(0);prefs.edit().putBoolean(p.ENABLED,true).commit();active()
        row("crossfeed6_save_a").performClick();idle();assertTrue(PresetCrossfeedController.hasComparison(0));base(3)
        assertTrue(PresetCrossfeedController.hasComparison(0));row("crossfeed6_save_b").performClick();idle()
        assertTrue(fragment().findPreference<FieldChoicePreference>("crossfeed6_audition")!!.choose(4))
        val packets=linkedMapOf<Int,IntArray>();assertTrue(PresetCrossfeedController.sync(app,2097151,this){id,v->packets[id]=v;true})
        assertEquals(0,packets[1421]!![5]);assertEquals(3,packets[1421]!![14])
    }
    @Test fun foldPauseAndParameterEditsStopAuditionAndReleaseSnapshots(){
        launch();professional(true);prefs.edit().putBoolean(p.ENABLED,true).commit();active()
        row("crossfeed6_save_a").performClick();row("crossfeed6_save_b").performClick();idle()
        assertTrue(PresetCrossfeedController.hasComparison(0));assertTrue(fragment().findPreference<FieldChoicePreference>("crossfeed6_audition")!!.choose(4))
        group().setExpanded(false);layout();assertEquals(0,PresetCrossfeedController.audition);assertFalse(PresetCrossfeedController.hasComparison(0));assertTrue(prefs.getBoolean(p.ENABLED,false))
        group().setExpanded(true);layout();assertTrue(fragment().findPreference<FieldChoicePreference>("crossfeed6_audition")!!.choose(1));number(p.mix).requestValue(43);idle();assertEquals(0,PresetCrossfeedController.audition)
        assertTrue(fragment().findPreference<FieldChoicePreference>("crossfeed6_audition")!!.choose(1));controller!!.pause();idle();assertEquals(0,PresetCrossfeedController.audition)
    }
    @Test fun oldDriverClearlyFallsBackAndPresetsDoNotEnableEitherModule(){
        launch();professional(true);base(3);PresetCrossfeedController.publishCapabilities(app,524287);layout()
        assertTrue(number(p.spatial).requestValue(-31));assertFalse(fragment().findPreference<FieldChoicePreference>("crossfeed6_audition")!!.isEnabled)
        assertTrue(fragment().findPreference<Preference>("crossfeed6_compatibility")!!.summary.toString().contains("更新配套驱动"))
        row("crossfeed6_presets").performClick();idle();assertTrue((ShadowDialog.getLatestDialog() as AlertDialog).isShowing)
        assertFalse(prefs.getBoolean(p.ENABLED,false));assertFalse(CrossfeedController.preferences(app).getBoolean("v_cure",false))
    }
    @Test fun recreationKeepsFineParametersAndProfessionalState(){
        launch();professional(true);base(3);number(p.spatial).requestValue(-31);controller!!.recreate();layout();group().setExpanded(true);layout()
        assertTrue(p.enabled(prefs.all));assertEquals(3,p.base(prefs.all));assertEquals(-31,number(p.spatial).value);assertFalse(group().isChecked)
    }
}
open class PresetCrossfeedRasterFixture:PresetCrossfeedUiFixture(){
    protected fun renderSix(w:Int,scale:Int){width=w;RuntimeEnvironment.setFontScale(scale/100f);launch();val folder=File("build/reports/crossfeed2").apply {mkdirs()}
        fun shot(name:String){val v=controller!!.get().findViewById<View>(android.R.id.content);val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888)
            b.eraseColor(MaterialColors.getColor(v,com.google.android.material.R.attr.colorSurface));v.draw(Canvas(b));File(folder,"lower_${w}_${scale}_$name.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        shot("legacy");professional(true)
        for(mode in listOf(1,3)){base(mode);val controls=if(mode<2)listOf(p.cutoff,p.attenuation,p.mix,p.delay,p.output)else listOf(p.spatial,p.mix,p.delay,p.output)
            for(c in controls){val view=row(c.key);for(id in listOf(android.R.id.title,R.id.seekbar_value,R.id.bass_reset)){val text=view.findViewById<TextView>(id);val layout=requireNotNull(text.layout)
                repeat(layout.lineCount){assertEquals("Unclipped ${text.text}",0,layout.getEllipsisCount(it))};assertTrue(layout.getLineEnd(layout.lineCount-1)>=text.text.length)}}
            row(if(mode<2)p.cutoff.key else p.spatial.key);shot(if(mode<2)"bs2b"else"spatial")}
        PresetCrossfeedController.publishCapabilities(app,16777215)
        for(key in listOf(p.PATH_ON,p.FILTER_ON,p.CENTER_ON))fragment().findPreference<SwitchPreferenceCompat>(key)!!.isChecked=true
        fragment().findPreference<SwitchPreferenceCompat>(p.PATH_LINK)!!.isChecked=false;layout()
        for(c in p.advancedNumbers){val view=row(c.key);val text=view.findViewById<TextView>(android.R.id.title);val textLayout=requireNotNull(text.layout)
            repeat(textLayout.lineCount){assertEquals(0,textLayout.getEllipsisCount(it))};assertTrue(textLayout.getLineEnd(textLayout.lineCount-1)>=text.text.length)}
        row(p.PATH_ON);shot("advanced_paths");row(p.FILTER_ON);shot("advanced_filter_center")
        row("crossfeed6_actual");shot("audition");group().setExpanded(false);layout();shot("collapsed")
    }
}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class PresetCrossfeedRaster320_100Test:PresetCrossfeedRasterFixture(){@Test fun render()=renderSix(320,100)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class PresetCrossfeedRaster320_150Test:PresetCrossfeedRasterFixture(){@Test fun render()=renderSix(320,150)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class PresetCrossfeedRaster411_100Test:PresetCrossfeedRasterFixture(){@Test fun render()=renderSix(411,100)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class PresetCrossfeedRaster411_150Test:PresetCrossfeedRasterFixture(){@Test fun render()=renderSix(411,150)}
