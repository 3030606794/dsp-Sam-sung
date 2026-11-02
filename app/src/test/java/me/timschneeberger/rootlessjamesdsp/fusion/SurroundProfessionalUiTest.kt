package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.SurroundActivity
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.Assert.*
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

abstract class SurroundProfessionalUiFixture {
    protected val app:Application get()=RuntimeEnvironment.getApplication()
    protected val p=SurroundProfessionalParameters
    protected var controller:ActivityController<SurroundActivity>?=null
    protected fun idle()=shadowOf(Looper.getMainLooper()).idle()
    protected fun host()=controller!!.get()
    @Before fun setup(){stopKoin();RuntimeEnvironment.setFontScale(1f);listOf(SurroundController.PREFS,SurroundController.STATUS_PREFS,Constants.PREF_APP).forEach {app.getSharedPreferences(it,Context.MODE_PRIVATE).edit().clear().commit()};val prefs=Preferences(app);startKoin {modules(module {single {prefs.App()};single {prefs.Var()}})};SurroundProfessionalRuntime.clearComparisons(app);SurroundProfessionalRuntime.publish(app,emptyList());SurroundController.publishCapabilities(app,131071)}
    @After fun finish(){controller?.pause()?.stop()?.destroy();controller=null;idle();RuntimeEnvironment.setFontScale(1f);SurroundProfessionalRuntime.clearComparisons(app);SurroundProfessionalRuntime.publish(app,emptyList());stopKoin()}
    protected fun launch(width:Int=320,scale:Int=100){RuntimeEnvironment.setFontScale(scale/100f);controller=Robolectric.buildActivity(SurroundActivity::class.java).setup().visible();repeat(2){idle();host().findViewById<View>(android.R.id.content).apply {measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(820,View.MeasureSpec.EXACTLY));layout(0,0,width,820)}};idle()}
    protected fun tagged(tag:String):View=host().findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>(tag) ?: error("Missing $tag")
    protected fun drawGroups(width:Int,scale:Int){
        launch(width,scale);val s=p.with(SurroundController.settings(app),"response_view",1);SurroundController.save(app,s);idle()
        val folder=File("build/reports/surround-professional").apply {mkdirs()}
        for(index in 1..6){val group=tagged("surround_pro_group_$index");group.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));group.layout(0,0,width,group.measuredHeight)
            val bitmap=Bitmap.createBitmap(width,group.height,Bitmap.Config.ARGB_8888);try {bitmap.eraseColor(MaterialColors.getColor(group,com.google.android.material.R.attr.colorSurface));group.draw(Canvas(bitmap));assertText(group);File(folder,"group$index-$width-$scale.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{bitmap.recycle()}}
        val chart=tagged("surround_complete_response");chart.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY));chart.layout(0,0,width,300)
        val bitmap=Bitmap.createBitmap(width,300,Bitmap.Config.ARGB_8888);try {bitmap.eraseColor(MaterialColors.getColor(chart,com.google.android.material.R.attr.colorSurface));chart.draw(Canvas(bitmap));File(folder,"response-$width-$scale.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
    private fun assertText(view:View){if(view is TextView)view.layout?.let {layout->for(i in 0 until layout.lineCount)assertEquals("Truncated ${view.text}",0,layout.getEllipsisCount(i))};if(view is ViewGroup)for(i in 0 until view.childCount)assertText(view.getChildAt(i))}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SurroundProfessionalUiTest:SurroundProfessionalUiFixture(){
    @Test fun visitingKeepsAllNewProcessingOffAndDoesNotWriteProfessionalKeys(){val before=SurroundController.preferences(app).all.toMap();launch();assertEquals(before,SurroundController.preferences(app).all);assertEquals("环绕音效",host().supportActionBar!!.title.toString());assertFalse(SurroundController.enabled(app));for(key in listOf("enabled","rms_on","peak_on","meters"))assertFalse((tagged("surround_pro_toggle_$key") as MaterialSwitch).isChecked)}
    @Test fun allNewNumbersSupportRealInputIncrementDecrementAndSingleReset(){
        SurroundController.save(app,p.with(SurroundController.settings(app),"link",0));launch()
        for(e in p.entries.filter {it.kind=="number"}){val key=e.control.key;val c=e.control;val value=c.min+if(c.divisor==1000)13 else e.step;tagged("surround_pro_value_$key").performClick();idle();val d=ShadowDialog.getLatestDialog() as AlertDialog;d.findViewById<EditText>(android.R.id.edit)!!.setText(c.number(value));d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertEquals(key,value,p.value(SurroundController.settings(app),key))
            tagged("surround_pro_plus_$key").performClick();idle();assertEquals(value+e.step,p.value(SurroundController.settings(app),key));tagged("surround_pro_minus_$key").performClick();idle();assertEquals(value,p.value(SurroundController.settings(app),key));tagged("surround_pro_reset_$key").performClick();idle();assertEquals(p.default(SurroundController.defaults[0],e),p.value(SurroundController.settings(app),key))}
        assertFalse(SurroundController.enabled(app));assertFalse(p.on(SurroundController.settings(app),"enabled"))
    }
    @Test fun linkedRoutesKeepSecondaryValuesAndGeometryDoesNotTurnOnProcessing(){launch();assertFalse(tagged("surround_pro_value_rl_delay").isEnabled);tagged("surround_pro_toggle_link").performClick();idle();assertTrue(tagged("surround_pro_value_rl_delay").isEnabled);tagged("surround_pro_plus_rl_delay").performClick();idle();assertEquals(351,p.value(SurroundController.settings(app),"rl_delay"));tagged("surround_pro_toggle_link").performClick();idle();assertEquals(351,p.value(SurroundController.settings(app),"rl_delay"));tagged("surround_pro_apply_geometry").performClick();idle();val s=SurroundController.settings(app);assertEquals(p.geometry(s).delayTenthsUs,p.value(s,"lr_delay"));assertEquals(p.value(s,"lr_delay"),p.value(s,"rl_delay"));assertFalse(p.on(s,"enabled"));assertEquals(35,s.delayUs)}
    @Test fun legacyMicroAdjustmentAndProfessionalModePreserveOldValues(){SurroundController.save(app,SurroundController.defaults[0].copy(attenuationMilliDb=8753));launch();tagged("surround_legacy_ATTENUATION_1").performClick();idle();assertEquals(8763,SurroundController.settings(app).attenuationMilliDb);tagged("surround_pro_toggle_enabled").performClick();idle();assertFalse(tagged("surround_legacy_ATTENUATION_1").isEnabled);assertEquals(8763,SurroundController.settings(app).attenuationMilliDb);tagged("surround_pro_toggle_enabled").performClick();idle();assertTrue(tagged("surround_legacy_ATTENUATION_1").isEnabled);tagged("surround_legacy_ATTENUATION_2").performClick();idle();assertEquals(5500,SurroundController.settings(app).attenuationMilliDb)}
    @Test fun fullResponseAndSnapshotsAreTemporary(){launch();tagged("surround_pro_choice_response_view").performClick();idle();val d=ShadowDialog.getLatestDialog() as AlertDialog;d.listView.performItemClick(d.listView.getChildAt(1),1,d.listView.adapter.getItemId(1));idle();assertEquals(View.VISIBLE,tagged("surround_complete_response").visibility);tagged("surround_pro_save_a").performClick();idle();assertTrue(SurroundProfessionalRuntime.hasComparison(3));tagged("surround_pro_plus_mix").performClick();tagged("surround_pro_save_b").performClick();idle();assertTrue(SurroundProfessionalRuntime.hasComparison(4));assertTrue(SurroundController.preferences(app).all.keys.none {it.contains("audition") || it.contains("comparison")});controller!!.pause().stop().destroy();controller=null;idle();assertFalse(SurroundProfessionalRuntime.hasComparison(3));assertFalse(SurroundProfessionalRuntime.hasComparison(4))}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SurroundProfessionalRaster320_100Test:SurroundProfessionalUiFixture(){@Test fun render()=drawGroups(320,100)}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SurroundProfessionalRaster320_150Test:SurroundProfessionalUiFixture(){@Test fun render()=drawGroups(320,150)}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SurroundProfessionalRaster411_100Test:SurroundProfessionalUiFixture(){@Test fun render()=drawGroups(411,100)}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h820dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SurroundProfessionalRaster411_150Test:SurroundProfessionalUiFixture(){@Test fun render()=drawGroups(411,150)}
