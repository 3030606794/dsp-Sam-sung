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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReverbAdvancedUiTest:ReverbUiFixture(){
    private val a=ReverbAdvancedParameters
    @Test fun rtAndBandTakeoverGreyOnlyTheirOwnRowsAndOffRestoresSavedValues(){
        launch();professional(true);number(p.size).requestValue(637);number(p.damp).requestValue(273)
        assertFalse(number(a.rt60).isEnabled);assertFalse(number(a.bassRatio).isEnabled)
        prefs.edit().putBoolean(a.RT_ON,true).commit();layout()
        assertFalse(number(p.size).isEnabled);assertTrue(number(p.damp).isEnabled);assertTrue(number(a.rt60).isEnabled)
        assertFalse(row(p.size.key).findViewById<Slider>(R.id.seekbar).isEnabled)
        assertEquals(0.38f,row(p.size.key).findViewById<TextView>(R.id.seekbar_value).alpha,0.001f)
        assertFalse(number(p.size).step(1));assertFalse(number(p.size).resetToDefault())
        prefs.edit().putBoolean(a.BANDS_ON,true).commit();layout()
        assertFalse(number(p.damp).isEnabled);assertTrue(number(a.bassRatio).isEnabled)
        prefs.edit().putBoolean(a.RT_ON,false).putBoolean(a.BANDS_ON,false).commit();layout()
        assertTrue(number(p.size).isEnabled);assertTrue(number(p.damp).isEnabled)
        assertEquals(637,number(p.size).value);assertEquals(273,number(p.damp).value)
        assertFalse(number(a.rt60).isEnabled);assertFalse(number(a.trebleRatio).isEnabled)
        assertFalse(p.moduleEnabled(prefs.all))
    }
    @Test fun takeoverWhileOldNumericDialogIsOpenRejectsItsLateCommit(){
        launch();professional(true);number(p.size).requestValue(637)
        row(p.size.key).findViewById<View>(R.id.seekbar_value).performClick();idle()
        val dialog=ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<EditText>(android.R.id.text1)!!.setText("77.7")
        prefs.edit().putBoolean(a.RT_ON,true).commit();layout()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle()
        assertEquals(637,p.value(prefs.all,p.size));dialog.dismiss()
    }
    @Test fun allAdvancedValuesSupportInputOneTickStepsAndIndividualReset(){
        launch();professional(true)
        prefs.edit().putBoolean(a.RT_ON,true).putBoolean(a.BANDS_ON,true).putBoolean(a.DUCK_ON,true).commit();layout()
        for(control in a.numbers){
            row(control.key).findViewById<View>(R.id.seekbar_value).performClick();idle()
            val dialog=ShadowDialog.getLatestDialog() as AlertDialog
            dialog.findViewById<EditText>(android.R.id.text1)!!.setText(control.number(control.min+2))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();layout()
            assertEquals(control.min+2,p.value(prefs.all,control));assertTrue(number(control).step(1))
            assertEquals(control.min+3,p.value(prefs.all,control));assertTrue(number(control).step(-1))
            assertEquals(control.min+2,p.value(prefs.all,control));assertTrue(number(control).resetToDefault())
            assertEquals(control.default,p.value(prefs.all,control))
        }
        val saved=p.capture(prefs.all).advanced;professional(false)
        assertEquals(saved,p.capture(prefs.all).advanced);assertNull(fragment().findPreference<Preference>(a.rt60.key))
        professional(true);assertEquals(saved,p.capture(prefs.all).advanced);assertFalse(p.moduleEnabled(prefs.all))
    }
    @Test fun graphIsOptionalAndDriverFallbackExplicitlyNamesUnsupportedFeatures(){
        launch();professional(true);assertNull(fragment().findPreference<Preference>("v_reverb_pro_decay_graph"))
        prefs.edit().putBoolean(a.GRAPH_ON,true).commit();layout()
        assertNotNull(fragment().findPreference<Preference>("v_reverb_pro_decay_graph"))
        assertTrue(fragment().findPreference<Preference>(a.DUCK_ON)!!.summary.toString().contains("不识别人声"))
        assertTrue(fragment().findPreference<Preference>("v_reverb_pro_compatibility")!!.summary.toString().contains("暂不生效"))
        ReverbController.publishCapabilities(app,8388607);layout()
        assertTrue(fragment().findPreference<Preference>("v_reverb_pro_compatibility")!!.summary.toString().contains("混响增强驱动"))
        prefs.edit().putBoolean(a.GRAPH_ON,false).commit();layout()
        assertNull(fragment().findPreference<Preference>("v_reverb_pro_decay_graph"))
    }
}

open class ReverbAdvancedRasterFixture:ReverbUiFixture(){
    protected fun renderAdvanced(w:Int,scale:Int){
        width=w;RuntimeEnvironment.setFontScale(scale/100f);ReverbController.publishCapabilities(app,8388607)
        launch();professional(true);number(p.size).requestValue(637);number(p.damp).requestValue(273)
        val a=ReverbAdvancedParameters
        prefs.edit().putBoolean(a.RT_ON,true).putBoolean(a.BANDS_ON,true).putBoolean(a.DUCK_ON,true)
            .putBoolean(a.GRAPH_ON,true).putBoolean(p.ENABLED,true).commit();layout()
        val report=a.decode(intArrayOf(1,2,48000,1,1,1,1,1,1,1500,2250,1500,975,640,250,4000))!!
        val runtime=IntArray(32).apply {this[0]=1;this[1]=2;this[2]=48000;this[3]=1;this[4]=1;this[26]=2000;this[30]=1}
        val reading=ReverbController.Reading(this,true,0,48000,p.decode(runtime),advanced=report)
        ReverbController.publishRuntime(app,listOf(reading));ReverbController.publishRuntime(app,listOf(reading.copy(frames=512)));layout()
        val folder=File("build/reports/reverb2").apply {mkdirs()}
        fun shot(name:String,key:String){
            row(key);val view=controller!!.get().findViewById<View>(android.R.id.content)
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(MaterialColors.getColor(view,com.google.android.material.R.attr.colorSurface));view.draw(Canvas(bitmap))
            File(folder,"${w}_${scale}_$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
        fun complete(view:View,ids:List<Int>){for(id in ids){
            val text=view.findViewById<TextView>(id);val lines=requireNotNull(text.layout)
            repeat(lines.lineCount){assertEquals("No ellipsis: ${text.text}",0,lines.getEllipsisCount(it))
                assertTrue("Text width: ${text.text}",lines.getLineMax(it)<=text.width-text.compoundPaddingLeft-text.compoundPaddingRight+1f)}
            assertTrue("Complete text: ${text.text}",lines.getLineEnd(lines.lineCount-1)>=text.text.length)
        }}
        for(control in a.numbers)complete(row(control.key),listOf(android.R.id.title,R.id.seekbar_value,R.id.bass_reset))
        for(key in a.switches)complete(row(key),listOf(android.R.id.title,android.R.id.summary))
        complete(row("v_reverb_pro_decay_graph"),listOf(android.R.id.title,android.R.id.summary))
        assertTrue(fragment().findPreference<Preference>("v_reverb_pro_decay_graph")!!.summary.toString().contains("低频 2.25 s"))
        shot("takeover",p.size.key);shot("rt_bands",a.RT_ON);shot("ducking",a.DUCK_ON);shot("prediction","v_reverb_pro_decay_graph")
    }
}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class ReverbAdvancedRaster320_100Test:ReverbAdvancedRasterFixture(){@Test fun render()=renderAdvanced(320,100)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class ReverbAdvancedRaster320_150Test:ReverbAdvancedRasterFixture(){@Test fun render()=renderAdvanced(320,150)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class ReverbAdvancedRaster411_100Test:ReverbAdvancedRasterFixture(){@Test fun render()=renderAdvanced(411,100)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class ReverbAdvancedRaster411_150Test:ReverbAdvancedRasterFixture(){@Test fun render()=renderAdvanced(411,150)}
