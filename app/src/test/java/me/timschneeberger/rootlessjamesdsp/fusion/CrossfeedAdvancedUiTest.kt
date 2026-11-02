package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

open class CrossfeedAdvancedUiFixture:CrossfeedUiFixture(){
    protected val a=CrossfeedAdvancedParameters
    protected fun toggle(key:String,value:Boolean){val row=fragment().findPreference<SwitchPreferenceCompat>(key)!!;assertTrue(row.callChangeListener(value));row.isChecked=value;layout()}
    protected fun enhancements(){CrossfeedController.publishCapabilities(app,2097151);professional(true)
        for(key in listOf(a.COMPENSATION,a.SEPARATE,a.FILTER,a.DIAGNOSTICS))toggle(key,true)}
    protected fun activeDiagnostics(){
        val identity=Any();val diag=IntArray(212);diag[0]=1;diag[1]=2;diag[2]=48000;diag[3]=1;diag[4]=1;diag[5]=-1200;diag[6]=-1300
        a.packet(prefs.all).copyInto(diag,9)
        for(i in 0 until 32){diag[20+i*6+2]=-600;diag[20+i*6+3]=-700}
        val state=intArrayOf(1,2,48000,1,1,0,0,0,0,0,0,0,700,60,100,0,0,0,0,0)
        for(frames in listOf(0,512))CrossfeedController.publishRuntime(app,listOf(CrossfeedController.Reading(identity,true,frames,48000,p.decode(state),diagnostics=a.decode(diag))))
        layout()
    }
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class CrossfeedAdvancedUiTest:CrossfeedAdvancedUiFixture(){
    @Test fun professionalSwitchDisablesTheActualTierRowAndRestoresSavedChoice(){
        prefs.edit().putInt(p.LEGACY,2).commit();launch();val legacy=fragment().findPreference<ViperSliderPreference>(p.LEGACY)!!
        assertTrue(legacy.isEnabled);professional(true);assertFalse(legacy.isEnabled);assertFalse(row(p.LEGACY).findViewById<Slider>(R.id.seekbar).isEnabled)
        assertTrue(legacy.summary.toString().contains("已接管"));assertEquals(2,p.legacy(prefs.all))
        professional(false);assertTrue(legacy.isEnabled);assertTrue(row(p.LEGACY).findViewById<Slider>(R.id.seekbar).isEnabled);assertEquals(2,legacy.value)
        assertFalse(prefs.getBoolean("v_cure",false))
    }
    @Test fun anAlreadyOpenTierDialogCannotChangeTheDisabledTier(){
        prefs.edit().putInt(p.LEGACY,1).commit();launch();row(p.LEGACY).performClick();idle();val dialog=ShadowDialog.getLatestDialog() as AlertDialog
        professional(true);dialog.listView.performItemClick(null,2,2);idle();assertEquals(1,p.legacy(prefs.all))
    }
    @Test fun recreationAndProfileUpdatesRestoreCorrectDisabledState(){
        launch();professional(true);controller!!.recreate();layout();assertFalse(fragment().findPreference<ViperSliderPreference>(p.LEGACY)!!.isEnabled)
        prefs.edit().putBoolean(p.ENABLED,false).putInt(p.LEGACY,1).commit();layout()
        val row=fragment().findPreference<ViperSliderPreference>(p.LEGACY)!!;assertTrue(row.isEnabled);assertEquals(1,row.value)
    }
    @Test fun newFeaturesStartOffAndIndependentDelaysTakeOverTheCommonDelay(){
        launch();CrossfeedController.publishCapabilities(app,2097151);professional(true)
        for(key in a.flags)assertFalse(fragment().findPreference<SwitchPreferenceCompat>(key)!!.isChecked)
        assertNull(fragment().findPreference<BassNumericPreference>(a.left.key));assertNull(fragment().findPreference<Preference>("crossfeed2_response"))
        assertTrue(number(p.delay).requestValue(137));toggle(a.SEPARATE,true)
        assertEquals(137,a.value(prefs.all,a.leftDelay));assertEquals(137,a.value(prefs.all,a.rightDelay));assertFalse(number(p.delay).isEnabled)
        toggle(a.SEPARATE,false);assertTrue(number(p.delay).isEnabled);assertEquals(137,number(p.delay).value)
    }
    @Test fun enhancementNumbersHaveInputButtonsAndIndependentReset(){
        launch();enhancements()
        for(c in a.numbers){row(c.key).findViewById<View>(R.id.seekbar_value).performClick();idle();val dialog=ShadowDialog.getLatestDialog() as AlertDialog
            dialog.findViewById<EditText>(android.R.id.text1)!!.setText(c.number(c.min+2));dialog.getButton(-1).performClick();layout()
            val number=fragment().findPreference<BassNumericPreference>(c.key)!!;assertEquals(c.min+2,number.value)
            row(c.key).findViewById<View>(R.id.bass_increment).performClick();idle();assertEquals(c.min+3,number.value)
            row(c.key).findViewById<View>(R.id.bass_decrement).performClick();idle();assertEquals(c.min+2,number.value)
            val before=prefs.all.toMap();row(c.key).findViewById<View>(R.id.bass_reset).performClick();idle();assertEquals(c.default,number.value)
            before.filterKeys {it!=c.key}.forEach {(key,value)->assertEquals(value,prefs.all[key])}
        }
        assertTrue(fragment().findPreference<FieldChoicePreference>(a.SLOPE)!!.choose(3));assertEquals(4,a.stages(prefs.all));assertFalse(prefs.getBoolean("v_cure",false))
    }
    @Test fun diagnosticsClearOnPowerOffAndAreExplicitlyTheory(){
        launch();enhancements();prefs.edit().putBoolean("v_cure",true).commit();activeDiagnostics()
        val chart=fragment().findPreference<Preference>("crossfeed2_response")!!
        assertTrue(chart.summary.toString().contains("−60"));assertTrue(chart.summary.toString().contains("-12.00 dBFS"));assertTrue(chart.title.toString().contains("理论"));assertTrue(chart.summary.toString().contains("保存参数"))
        prefs.edit().putBoolean("v_cure",false).commit();layout();assertFalse(chart.summary.toString().contains("-12.00"))
    }
}
open class CrossfeedAdvancedRasterFixture:CrossfeedAdvancedUiFixture(){
    protected fun renderAdvanced(w:Int,scale:Int){
        width=w;RuntimeEnvironment.setFontScale(scale/100f);launch();enhancements();prefs.edit().putBoolean("v_cure",true).commit();activeDiagnostics()
        val folder=File("build/reports/crossfeed2").apply {mkdirs()}
        fun shot(name:String){val v=controller!!.get().findViewById<View>(android.R.id.content);val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888)
            b.eraseColor(MaterialColors.getColor(v,com.google.android.material.R.attr.colorSurface));v.draw(Canvas(b));File(folder,"upper_${w}_${scale}_$name.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        row(p.LEGACY);shot("disabled_tiers")
        for(key in a.flags){val text=row(key).findViewById<TextView>(android.R.id.title);val layout=requireNotNull(text.layout)
            repeat(layout.lineCount){assertEquals("Unclipped ${text.text}",0,layout.getEllipsisCount(it))};assertTrue(layout.getLineEnd(layout.lineCount-1)>=text.text.length)}
        for(c in a.numbers){val view=row(c.key);for(id in listOf(android.R.id.title,R.id.seekbar_value,R.id.bass_reset)){
            val text=view.findViewById<TextView>(id);val layout=requireNotNull(text.layout);repeat(layout.lineCount){assertEquals("Unclipped ${text.text}",0,layout.getEllipsisCount(it))}
            assertTrue(layout.getLineEnd(layout.lineCount-1)>=text.text.length)}}
        row(a.left.key);shot("independent_paths");row("crossfeed2_response");shot("response");fragment().effectHeader.setExpanded(false);layout();shot("collapsed")
    }
}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class CrossfeedAdvancedRaster320_100Test:CrossfeedAdvancedRasterFixture(){@Test fun render()=renderAdvanced(320,100)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class CrossfeedAdvancedRaster320_150Test:CrossfeedAdvancedRasterFixture(){@Test fun render()=renderAdvanced(320,150)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class CrossfeedAdvancedRaster411_100Test:CrossfeedAdvancedRasterFixture(){@Test fun render()=renderAdvanced(411,100)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class CrossfeedAdvancedRaster411_150Test:CrossfeedAdvancedRasterFixture(){@Test fun render()=renderAdvanced(411,150)}
