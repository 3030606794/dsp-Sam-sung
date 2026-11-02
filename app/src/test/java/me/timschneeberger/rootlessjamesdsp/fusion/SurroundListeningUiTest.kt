package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.color.MaterialColors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SurroundListeningUiTest:SurroundProfessionalUiFixture(){
    @Test fun defaultOffAndEveryNewNumberSupportsInputStepsAndReset(){
        SurroundController.publishCapabilities(app,262143);val before=SurroundController.preferences(app).all.toMap();launch();assertEquals(before,SurroundController.preferences(app).all)
        for(key in listOf("match_on","cal_on","diagnostics_on"))assertFalse((tagged("surround_pro_toggle_$key") as MaterialSwitch).isChecked)
        assertEquals(View.GONE,tagged("surround_diagnostics_chart").visibility);assertFalse(tagged("surround_calibration_start_stop").isEnabled)
        for(e in SurroundListeningParameters.entries.filter {it.kind=="number"}){val key=e.control.key;val c=e.control;val value=if(key=="cal_high")5000 else c.min+2
            tagged("surround_pro_value_$key").performClick();idle();val d=ShadowDialog.getLatestDialog() as AlertDialog;d.findViewById<EditText>(android.R.id.edit)!!.setText(c.number(value));d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertEquals(value,p.value(SurroundController.settings(app),key));tagged("surround_pro_plus_$key").performClick();idle();assertEquals(value+1,p.value(SurroundController.settings(app),key));tagged("surround_pro_minus_$key").performClick();idle();assertEquals(value,p.value(SurroundController.settings(app),key));tagged("surround_pro_reset_$key").performClick();idle();assertEquals(c.default,p.value(SurroundController.settings(app),key))}
        assertEquals(0,SurroundProfessionalRuntime.calibration);assertFalse(SurroundController.enabled(app))
    }
    @Test fun allowingCalibrationDoesNotPlayOrChangePowerAndBadBandIsRejected(){
        SurroundController.publishCapabilities(app,262143);launch();tagged("surround_pro_toggle_cal_on").performClick();idle();assertTrue(p.on(SurroundController.settings(app),"cal_on"));assertEquals(0,SurroundProfessionalRuntime.calibration);tagged("surround_calibration_start_stop").performClick();idle();assertEquals(0,SurroundProfessionalRuntime.calibration);assertFalse(SurroundController.enabled(app))
        tagged("surround_pro_value_cal_low").performClick();idle();val d=ShadowDialog.getLatestDialog() as AlertDialog;d.findViewById<EditText>(android.R.id.edit)!!.setText("9000");d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertEquals(200,p.value(SurroundController.settings(app),"cal_low"));assertEquals(8000,p.value(SurroundController.settings(app),"cal_high"))
    }
    @Test fun pauseCancelsAManuallyStartedCalibration(){
        SurroundController.publishCapabilities(app,262143);launch();SurroundController.setEnabled(app,true);SurroundController.save(app,p.with(SurroundController.settings(app),"cal_on",1));idle()
        val identity=Any();val v=IntArray(32);v[0]=1;v[2]=48000;v[3]=1;v[16]=200;v[17]=9000;v[18]=350;v[19]=350;v[20]=5500;v[21]=5500
        fun reading(n:Int)=SurroundProfessionalRuntime.Reading(identity,true,n,48000,SurroundProfessionalRuntime.decode(v))
        SurroundProfessionalRuntime.publish(app,listOf(reading(0)));SurroundProfessionalRuntime.publish(app,listOf(reading(512)));tagged("surround_calibration_start_stop").performClick();assertEquals(3,SurroundProfessionalRuntime.calibration)
        controller!!.pause();idle();assertEquals(0,SurroundProfessionalRuntime.calibration);assertTrue(p.on(SurroundController.settings(app),"cal_on"));controller!!.stop().destroy();controller=null;idle()
    }
}

abstract class SurroundListeningRasterFixture:SurroundProfessionalUiFixture(){
    protected fun renderNew(width:Int,scale:Int){SurroundController.publishCapabilities(app,262143);launch(width,scale);val folder=File("build/reports/surround-listening").apply {mkdirs()}
        fun check(v:View){if(v is TextView)v.layout?.let {l->for(i in 0 until l.lineCount)assertEquals("Truncated ${v.text}",0,l.getEllipsisCount(i))};if(v is ViewGroup)for(i in 0 until v.childCount)check(v.getChildAt(i))}
        for(index in 7..9){val g=tagged("surround_pro_group_$index");g.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));g.layout(0,0,width,g.measuredHeight);val bitmap=Bitmap.createBitmap(width,g.height,Bitmap.Config.ARGB_8888);try {bitmap.eraseColor(MaterialColors.getColor(g,com.google.android.material.R.attr.colorSurface));g.draw(Canvas(bitmap));check(g);File(folder,"group$index-$width-$scale.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{bitmap.recycle()}}
        val chart=tagged("surround_diagnostics_chart") as SurroundDiagnosticsView
        for(view in 0..2){chart.result=SurroundDiagnostics.calculate(SurroundController.defaults[0],48000.0,view,20);chart.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY));chart.layout(0,0,width,300);val bitmap=Bitmap.createBitmap(width,300,Bitmap.Config.ARGB_8888);try {bitmap.eraseColor(MaterialColors.getColor(chart,com.google.android.material.R.attr.colorSurface));chart.draw(Canvas(bitmap));File(folder,"diagnostic$view-$width-$scale.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{bitmap.recycle()}}
    }
}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class SurroundListeningRaster320_100Test:SurroundListeningRasterFixture(){@Test fun render()=renderNew(320,100)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class SurroundListeningRaster320_150Test:SurroundListeningRasterFixture(){@Test fun render()=renderNew(320,150)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class SurroundListeningRaster411_100Test:SurroundListeningRasterFixture(){@Test fun render()=renderNew(411,100)}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class SurroundListeningRaster411_150Test:SurroundListeningRasterFixture(){@Test fun render()=renderNew(411,150)}
