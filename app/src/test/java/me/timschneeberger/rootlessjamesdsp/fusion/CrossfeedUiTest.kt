package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.LinearLayoutManager
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

open class CrossfeedUiFixture {
    protected val app:Application get()=RuntimeEnvironment.getApplication()
    protected val p=CrossfeedParameters
    protected val prefs get()=CrossfeedController.preferences(app)
    protected var controller:ActivityController<Host>?=null
    protected var width=320
    protected fun idle()=shadowOf(Looper.getMainLooper()).idle()
    protected fun fragment()=controller!!.get().supportFragmentManager.findFragmentByTag("crossfeed") as ViperEffectPreferenceFragment
    @Before fun prepare(){
        CrossfeedController.stopAudition(app,clearSnapshots=true);CrossfeedController.publishRuntime(app,emptyList())
        prefs.edit().clear().commit();CrossfeedController.publishCapabilities(app,524287)
    }
    @After fun cleanup(){
        controller?.pause()?.stop()?.destroy();controller=null;idle()
        CrossfeedController.stopAudition(app,clearSnapshots=true);CrossfeedController.publishRuntime(app,emptyList())
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
    protected fun professional(value:Boolean){
        prefs.edit().putBoolean(p.ENABLED,value).commit();layout()
    }
    protected fun number(c:BassParameters.Control)=fragment().findPreference<BassNumericPreference>(c.key)!!
    class Host:FragmentActivity(){
        override fun onCreate(savedInstanceState:Bundle?){
            setTheme(R.style.Theme_RootlessJamesDSP);super.onCreate(savedInstanceState)
            setContentView(FragmentContainerView(this).apply {id=R.id.viper_effect_cure})
            if(savedInstanceState==null)supportFragmentManager.beginTransaction()
                .add(R.id.viper_effect_cure,ViperEffectPreferenceFragment.newInstance("v_cure"),"crossfeed").commitNow()
        }
    }
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CrossfeedUiTest:CrossfeedUiFixture(){
    @Test fun defaultTitlePowerAndCollapsedHeaderRemainIndependent(){
        prefs.edit().putInt(p.LEGACY,2).commit();launch(false)
        assertEquals("耳机交叉馈送",fragment().effectHeader.title);assertEquals(1,fragment().listView.adapter!!.itemCount)
        row("v_cure").performClick();layout();assertTrue(fragment().effectHeader.isExpanded)
        assertFalse(prefs.getBoolean("v_cure",false));assertFalse(p.enabled(prefs.all));assertEquals(2,p.legacy(prefs.all))
        assertEquals(0,CrossfeedController.audition);assertFalse(CrossfeedController.match)
    }
    @Test fun allNumbersSupportDecimalInputButtonsAndSingleResetWithoutEnablingPower(){
        launch();professional(true)
        for(c in p.numbers){
            row(c.key).findViewById<View>(R.id.seekbar_value).performClick();idle()
            val d=ShadowDialog.getLatestDialog() as AlertDialog
            d.findViewById<EditText>(android.R.id.text1)!!.setText(c.number(c.min+2))
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();layout()
            assertEquals(c.min+2,number(c).value)
            assertTrue(number(c).step(1));assertEquals(c.min+3,p.value(prefs.all,c))
            assertTrue(number(c).step(-1));assertEquals(c.min+2,p.value(prefs.all,c))
            assertTrue(number(c).resetToDefault());assertEquals(c.default,p.value(prefs.all,c))
        }
        assertFalse(prefs.getBoolean("v_cure",false));assertEquals(0,CrossfeedController.audition)
    }
    @Test fun filterChoiceAndOriginalTierAreIndependentAndOldDriverStillAllowsEditing(){
        prefs.edit().putInt(p.LEGACY,2).commit();launch();professional(true)
        assertTrue(fragment().findPreference<FieldChoicePreference>(p.FILTER)!!.choose(1));assertEquals(1,p.filter(prefs.all))
        CrossfeedController.publishCapabilities(app,262143);layout()
        assertTrue(number(p.delay).requestValue(137));assertEquals(137,p.value(prefs.all,p.delay))
        assertEquals(2,p.legacy(prefs.all));assertFalse(fragment().findPreference<FieldChoicePreference>("crossfeed_audition")!!.isEnabled)
        assertTrue(fragment().findPreference<Preference>("crossfeed_compatibility")!!.summary.toString().contains("更新配套驱动"))
    }
    @Test fun systemPresetDialogDoesNotChangePowerAndPersonalLibraryIsSeparate(){
        launch();row("crossfeed_presets").performClick();idle()
        val d=ShadowDialog.getLatestDialog() as AlertDialog;assertTrue(d.isShowing)
        assertFalse(prefs.getBoolean("v_cure",false));assertFalse(CrossfeedController.hasComparison(0))
    }
    @Test fun collapseAndPauseClearSnapshotsAndRestoreSavedProcessing(){
        prefs.edit().putBoolean("v_cure",true).putBoolean(p.ENABLED,true).commit();launch()
        val identity=Any()
        fun publish(frames:Int){val v=intArrayOf(1,2,48000,1,1,0,0,0,0,0,CrossfeedController.epoch,0,700,60,100,0,0,0,0,0)
            CrossfeedController.publishRuntime(app,listOf(CrossfeedController.Reading(identity,true,frames,48000,p.decode(v))));idle()}
        publish(0);publish(100)
        assertTrue(CrossfeedController.saveComparison(app,Any(),0));assertTrue(CrossfeedController.saveComparison(app,Any(),1))
        val comparison=fragment().findPreference<FieldChoicePreference>("crossfeed_audition")!!
        assertTrue(comparison.choose(4));fragment().effectHeader.setExpanded(false);idle()
        assertEquals(0,CrossfeedController.audition);assertFalse(CrossfeedController.hasComparison(0));assertTrue(prefs.getBoolean("v_cure",false))
        controller!!.pause();idle();assertEquals(0,CrossfeedController.audition)
    }
}

open class CrossfeedRasterFixture:CrossfeedUiFixture(){
    protected fun renderNew(w:Int,scale:Int){
        width=w;RuntimeEnvironment.setFontScale(scale/100f);launch()
        val folder=File("build/reports/crossfeed1").apply {mkdirs()}
        fun shot(name:String){
            val v=controller!!.get().findViewById<View>(android.R.id.content)
            val bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(MaterialColors.getColor(v,com.google.android.material.R.attr.colorSurface))
            v.draw(Canvas(bitmap))
            File(folder,"${w}_${scale}_$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
        shot("legacy");professional(true)
        for(c in p.numbers){
            val view=row(c.key)
            for(id in listOf(android.R.id.title,R.id.seekbar_value,R.id.bass_reset)){
                val text=view.findViewById<TextView>(id);val layout=requireNotNull(text.layout)
                repeat(layout.lineCount){assertEquals("No clipped text: ${text.text}",0,layout.getEllipsisCount(it))
                    assertTrue("Text width: ${text.text}",layout.getLineMax(it)<=text.width-text.compoundPaddingLeft-text.compoundPaddingRight+1f)}
                assertTrue("Complete text: ${text.text}",layout.getLineEnd(layout.lineCount-1)>=text.text.length)
            }
        }
        row(p.cutoff.key);shot("professional")
        row("crossfeed_actual");shot("audition")
        fragment().effectHeader.setExpanded(false);layout();shot("collapsed")
    }
}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class CrossfeedRaster320_100Test:CrossfeedRasterFixture(){@Test fun render()=renderNew(320,100)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class CrossfeedRaster320_150Test:CrossfeedRasterFixture(){@Test fun render()=renderNew(320,150)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class CrossfeedRaster411_100Test:CrossfeedRasterFixture(){@Test fun render()=renderNew(411,100)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class CrossfeedRaster411_150Test:CrossfeedRasterFixture(){@Test fun render()=renderNew(411,150)}
