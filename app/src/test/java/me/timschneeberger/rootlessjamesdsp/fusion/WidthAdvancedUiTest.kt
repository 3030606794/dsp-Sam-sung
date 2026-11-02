package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.card.MaterialCardView
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
abstract class WidthAdvancedUiFixture {
    protected val app: Application get()=RuntimeEnvironment.getApplication()
    protected val p=WidthParameters
    protected val prefs get()=WidthController.preferences(app)
    protected var controller: ActivityController<Host>?=null
    protected var width=320
    protected val id=Any()
    protected fun fragment()=controller!!.get().supportFragmentManager.findFragmentByTag("width") as PreferenceGroupFragment
    protected fun group()=fragment().findPreference<SwitchPreferenceGroup>(p.ENABLED)!!
    protected fun idle()=shadowOf(Looper.getMainLooper()).idle()
    @Before fun prepare(){stopKoin();val preferences=Preferences(app);startKoin {modules(module {single {preferences.App()}})};RuntimeEnvironment.setFontScale(1f);prefs.edit().clear().commit();WidthController.stopAudition(app);WidthController.publishRuntime(app,emptyList());WidthController.publishCapabilities(app,65535);runtime(0);runtime(512)}
    @After fun cleanup(){controller?.pause()?.stop()?.destroy();controller=null;idle();WidthController.sessionClosed(app,id);WidthController.stopAudition(app);RuntimeEnvironment.setFontScale(1f);stopKoin()}
    protected fun runtime(frames: Int){WidthController.publishRuntime(app,listOf(WidthController.Reading(id,true,frames,WidthRuntime.decode(WidthParametersTest.bulk()),advanced=WidthAdvancedParametersTest.bulk())));idle()}
    protected fun launch(){controller=Robolectric.buildActivity(Host::class.java).setup().visible();group().setExpanded(true);layout()}
    protected fun layout(){repeat(2){idle();controller!!.get().findViewById<View>(android.R.id.content).apply {measure(View.MeasureSpec.makeMeasureSpec(this@WidthAdvancedUiFixture.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(3600,View.MeasureSpec.EXACTLY));layout(0,0,this@WidthAdvancedUiFixture.width,3600)}};idle()}
    @Suppress("RestrictedApi") protected fun row(key: String): View {val list=fragment().listView;val adapter=list.adapter as PreferenceGroupAdapter;val i=adapter.getPreferenceAdapterPosition(key);assertTrue("Visible $key",i>=0);(list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i,0);layout();return requireNotNull(list.findViewHolderForAdapterPosition(i)).itemView}
    protected fun number(c: BassParameters.Control)=fragment().findPreference<BassNumericPreference>(c.key)!!
    @Suppress("RestrictedApi") protected fun renderSection(first: String,last: String,w: Int,font: Float,name: String) {
        width=w;RuntimeEnvironment.setFontScale(font);prefs.edit().putInt(p.MODE,if(first=="width2_dyn3_on")1 else 2).putBoolean(p.ENABLED,true).putBoolean("width2_meter_on",true).putInt("width2_guard_mode",1).commit();launch();runtime(1024);runtime(1536)
        val adapter=fragment().listView.adapter as PreferenceGroupAdapter;val from=adapter.getPreferenceAdapterPosition(first);val end=adapter.getPreferenceAdapterPosition(last)
        for(i in from..end){val key=adapter.getItem(i)!!.key;val v=row(key);v.findViewById<TextView>(android.R.id.title)?.let(::verify);v.findViewById<TextView>(R.id.seekbar_value)?.let(::verify)}
        capture(first,last,name)
    }
    protected fun verify(v: TextView){val l=requireNotNull(v.layout);repeat(l.lineCount){assertEquals("No truncation: ${v.text}",0,l.getEllipsisCount(it));assertTrue("Width fits ${v.text}: ${l.getLineMax(it)} / ${v.width-v.compoundPaddingLeft-v.compoundPaddingRight}",l.getLineMax(it)<=v.width-v.compoundPaddingLeft-v.compoundPaddingRight+1)};assertTrue("Height fits ${v.text}: ${l.height} / ${v.height-v.totalPaddingTop-v.totalPaddingBottom}",l.height<=v.height-v.totalPaddingTop-v.totalPaddingBottom);assertTrue("Complete ${v.text}: ${l.getLineEnd(l.lineCount-1)}",l.getLineEnd(l.lineCount-1)>=v.text.length)}
    @Suppress("RestrictedApi") protected fun capture(first: String,last: String,name: String) {
        val list=fragment().listView;val adapter=list.adapter as PreferenceGroupAdapter;val start=adapter.getPreferenceAdapterPosition(first);val end=adapter.getPreferenceAdapterPosition(last);(list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(start,0);layout();val root=fragment().requireView() as ViewGroup
        fun bounds(i: Int)=requireNotNull(list.findViewHolderForAdapterPosition(i)).itemView.let {v->Rect(0,0,v.width,v.height).apply {root.offsetDescendantRectToMyCoords(v,this)}}
        val top=bounds(start).top;val bottom=bounds(end).bottom;assertTrue(top>=0 && bottom>top && bottom<=root.height)
        assertEquals(width,root.width);val bitmap=Bitmap.createBitmap(root.width,bottom-top,Bitmap.Config.ARGB_8888);try {bitmap.eraseColor(MaterialColors.getColor(root,com.google.android.material.R.attr.colorSurface));root.draw(Canvas(bitmap).apply {translate(0f,-top.toFloat())});val folder=File("build/reports/stereo-width2").apply {mkdirs()};File(folder,"$name.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{bitmap.recycle()}
    }
    class Host: FragmentActivity() {
        override fun onCreate(state: Bundle?) {setTheme(R.style.Theme_RootlessJamesDSP);super.onCreate(state);setContentView(FrameLayout(this).apply {addView(MaterialCardView(context).apply {addView(FragmentContainerView(context).apply {id=WIDTH_ID},ViewGroup.LayoutParams(-1,-1))},FrameLayout.LayoutParams(-1,-1))});if(state==null)supportFragmentManager.beginTransaction().add(WIDTH_ID,PreferenceGroupFragment.newInstance(WidthParameters.NAMESPACE,R.xml.dsp_stereowide_preferences),"width").commitNow()}
        companion object {const val WIDTH_ID=0x745145}
    }
}


@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class WidthAdvancedUiTest: WidthAdvancedUiFixture() {
    @Test fun numericBand0()=checkNumbers(WidthAdvancedParameters.entries.filter {it.kind=="number" && it.band==0})
    @Test fun numericBand1()=checkNumbers(WidthAdvancedParameters.entries.filter {it.kind=="number" && it.band==1})
    @Test fun numericBand2()=checkNumbers(WidthAdvancedParameters.entries.filter {it.kind=="number" && it.band==2})
    @Test fun numericBand3()=checkNumbers(WidthAdvancedParameters.entries.filter {it.kind=="number" && it.band==3})
    @Test fun numericGroups2To3()=checkNumbers(WidthAdvancedParameters.entries.filter {it.kind=="number" && it.group in 2..3})
    @Test fun numericGroups4To6()=checkNumbers(WidthAdvancedParameters.entries.filter {it.kind=="number" && it.group in 4..6})
    private fun checkNumbers(entries: List<WidthAdvancedParameters.Entry>) {
        prefs.edit().putInt(p.MODE,2).commit();launch()
        for(e in entries) {
            val c=e.control;prefs.edit().putInt(p.MODE,if(e.band==3)1 else 2).putInt("width2_guard_mode",1).commit();idle()
            val entered=if(c.default<c.max)c.default+1 else c.default-1
            row(c.key).findViewById<View>(R.id.seekbar_value).performClick();idle();val dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val input=dialog.findViewById<EditText>(android.R.id.text1)!!;input.setText("NaN");dialog.getButton(-1).performClick();assertTrue(dialog.isShowing)
            input.setText(c.number(entered));dialog.getButton(-1).performClick();idle();assertFalse(c.key,dialog.isShowing);assertEquals(entered,number(c).value)
            row(c.key).findViewById<View>(R.id.bass_decrement).performClick();idle();assertEquals(entered-1,number(c).value)
            row(c.key).findViewById<View>(R.id.bass_increment).performClick();idle();assertEquals(entered,number(c).value)
            row(c.key).findViewById<View>(R.id.bass_reset).performClick();idle();assertEquals(c.default,number(c).value);assertFalse(prefs.getBoolean(p.ENABLED,false))
        }
    }
    @Test fun oldDriverRetainsEveryNewValueAndOnlySupportsTheFirstRound() {
        prefs.edit().putFloat(p.LEGACY,58.5f).putInt(p.MODE,2).putInt("width2_dyn0_threshold",-99).commit();launch()
        WidthAdvancedParameters.entries.filter {it.kind=="switch"}.forEach {assertFalse(prefs.getBoolean(it.control.key,false))}
        WidthController.publishCapabilities(app,32767);idle();assertFalse(number(WidthAdvancedParameters.all.first()).isEnabled);assertTrue(number(p.master).isEnabled)
        assertEquals(-99,prefs.getInt("width2_dyn0_threshold",0));assertEquals(58.5f,prefs.getFloat(p.LEGACY,0f));group().setExpanded(false);layout();assertEquals(1,fragment().listView.adapter!!.itemCount)
        WidthController.publishCapabilities(app,65535);group().setExpanded(true);layout();assertTrue(number(WidthAdvancedParameters.all.first()).isEnabled)
    }
    @Test fun comparisonsAreTemporaryRestoreSavedParametersAndStopOnFold() {
        prefs.edit().putInt(p.MODE,2).putBoolean(p.ENABLED,true).putInt(p.master.key,1200).commit();launch()
        row("width2_save_a").performClick();idle();assertTrue(WidthController.hasComparison(8));prefs.edit().putInt(p.master.key,1700).commit();idle();row("width2_save_b").performClick();idle()
        val comparison=fragment().findPreference<FieldChoicePreference>("width2_compare")!!;assertTrue(comparison.choose(5));idle();assertEquals(8,WidthController.audition)
        val packets=mutableMapOf<Int,IntArray>();assertTrue(WidthController.sync(app,65535,id){key,v->packets[key]=v;true});assertEquals(1200,packets[1410]!![2]);assertEquals(1700,prefs.getInt(p.master.key,0));assertEquals(8,packets[1413]!![0])
        group().setExpanded(false);idle();assertEquals(0,WidthController.audition);assertTrue(WidthController.sync(app,65535,id){key,v->packets[key]=v;true});assertEquals(1700,packets[1410]!![2]);assertEquals(0,packets[1413]!![0]);assertFalse(prefs.all.keys.any {it.contains("compare") || it.contains("audition")})
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class WidthAdvancedRaster320_100Test: WidthAdvancedUiFixture() {
    @Test fun renderGroup1_320_100()=renderSection("width2_dyn0_on","width2_dyn0_max",320,1.0f,"group1-320-100")
    @Test fun renderGroup2_320_100()=renderSection("width2_dyn1_on","width2_dyn1_max",320,1.0f,"group2-320-100")
    @Test fun renderGroup3_320_100()=renderSection("width2_dyn2_on","width2_dyn2_max",320,1.0f,"group3-320-100")
    @Test fun renderGroup4_320_100()=renderSection("width2_dyn3_on","width2_dyn3_max",320,1.0f,"group4-320-100")
    @Test fun renderGroup5_320_100()=renderSection("width2_group2","width2_transient_release",320,1.0f,"group5-320-100")
    @Test fun renderGroup6_320_100()=renderSection("width2_group3","width2_guard_high",320,1.0f,"group6-320-100")
    @Test fun renderGroup7_320_100()=renderSection("width2_group4","width2_eq1_q",320,1.0f,"group7-320-100")
    @Test fun renderGroup8_320_100()=renderSection("width2_group5","width2_auto_direction",320,1.0f,"group8-320-100")
    @Test fun renderGroup9_320_100()=renderSection("width2_group6","width2_compare",320,1.0f,"group9-320-100")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class WidthAdvancedRaster320_150Test: WidthAdvancedUiFixture() {
    @Test fun renderGroup1_320_150()=renderSection("width2_dyn0_on","width2_dyn0_max",320,1.5f,"group1-320-150")
    @Test fun renderGroup2_320_150()=renderSection("width2_dyn1_on","width2_dyn1_max",320,1.5f,"group2-320-150")
    @Test fun renderGroup3_320_150()=renderSection("width2_dyn2_on","width2_dyn2_max",320,1.5f,"group3-320-150")
    @Test fun renderGroup4_320_150()=renderSection("width2_dyn3_on","width2_dyn3_max",320,1.5f,"group4-320-150")
    @Test fun renderGroup5_320_150()=renderSection("width2_group2","width2_transient_release",320,1.5f,"group5-320-150")
    @Test fun renderGroup6_320_150()=renderSection("width2_group3","width2_guard_high",320,1.5f,"group6-320-150")
    @Test fun renderGroup7_320_150()=renderSection("width2_group4","width2_eq1_q",320,1.5f,"group7-320-150")
    @Test fun renderGroup8_320_150()=renderSection("width2_group5","width2_auto_direction",320,1.5f,"group8-320-150")
    @Test fun renderGroup9_320_150()=renderSection("width2_group6","width2_compare",320,1.5f,"group9-320-150")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class WidthAdvancedRaster411_100Test: WidthAdvancedUiFixture() {
    @Test fun renderGroup1_411_100()=renderSection("width2_dyn0_on","width2_dyn0_max",411,1.0f,"group1-411-100")
    @Test fun renderGroup2_411_100()=renderSection("width2_dyn1_on","width2_dyn1_max",411,1.0f,"group2-411-100")
    @Test fun renderGroup3_411_100()=renderSection("width2_dyn2_on","width2_dyn2_max",411,1.0f,"group3-411-100")
    @Test fun renderGroup4_411_100()=renderSection("width2_dyn3_on","width2_dyn3_max",411,1.0f,"group4-411-100")
    @Test fun renderGroup5_411_100()=renderSection("width2_group2","width2_transient_release",411,1.0f,"group5-411-100")
    @Test fun renderGroup6_411_100()=renderSection("width2_group3","width2_guard_high",411,1.0f,"group6-411-100")
    @Test fun renderGroup7_411_100()=renderSection("width2_group4","width2_eq1_q",411,1.0f,"group7-411-100")
    @Test fun renderGroup8_411_100()=renderSection("width2_group5","width2_auto_direction",411,1.0f,"group8-411-100")
    @Test fun renderGroup9_411_100()=renderSection("width2_group6","width2_compare",411,1.0f,"group9-411-100")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class WidthAdvancedRaster411_150Test: WidthAdvancedUiFixture() {
    @Test fun renderGroup1_411_150()=renderSection("width2_dyn0_on","width2_dyn0_max",411,1.5f,"group1-411-150")
    @Test fun renderGroup2_411_150()=renderSection("width2_dyn1_on","width2_dyn1_max",411,1.5f,"group2-411-150")
    @Test fun renderGroup3_411_150()=renderSection("width2_dyn2_on","width2_dyn2_max",411,1.5f,"group3-411-150")
    @Test fun renderGroup4_411_150()=renderSection("width2_dyn3_on","width2_dyn3_max",411,1.5f,"group4-411-150")
    @Test fun renderGroup5_411_150()=renderSection("width2_group2","width2_transient_release",411,1.5f,"group5-411-150")
    @Test fun renderGroup6_411_150()=renderSection("width2_group3","width2_guard_high",411,1.5f,"group6-411-150")
    @Test fun renderGroup7_411_150()=renderSection("width2_group4","width2_eq1_q",411,1.5f,"group7-411-150")
    @Test fun renderGroup8_411_150()=renderSection("width2_group5","width2_auto_direction",411,1.5f,"group8-411-150")
    @Test fun renderGroup9_411_150()=renderSection("width2_group6","width2_compare",411,1.5f,"group9-411-150")
}
