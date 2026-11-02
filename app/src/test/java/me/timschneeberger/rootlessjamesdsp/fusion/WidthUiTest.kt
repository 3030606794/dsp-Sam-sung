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
class WidthUiTest {
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val p=WidthParameters
    private val prefs get()=WidthController.preferences(app)
    private var controller: ActivityController<Host>?=null
    private var width=320
    private val id=Any()
    private fun fragment()=controller!!.get().supportFragmentManager.findFragmentByTag("width") as PreferenceGroupFragment
    private fun group()=fragment().findPreference<SwitchPreferenceGroup>(p.ENABLED)!!
    private fun idle()=shadowOf(Looper.getMainLooper()).idle()
    @Before fun prepare(){stopKoin();val preferences=Preferences(app);startKoin {modules(module {single {preferences.App()}})};RuntimeEnvironment.setFontScale(1f);prefs.edit().clear().commit();WidthController.stopAudition(app);WidthController.publishRuntime(app,emptyList());WidthController.publishCapabilities(app,32767);runtime(0);runtime(512)}
    @After fun cleanup(){controller?.pause()?.stop()?.destroy();controller=null;idle();WidthController.sessionClosed(app,id);WidthController.stopAudition(app);RuntimeEnvironment.setFontScale(1f);stopKoin()}
    private fun runtime(frames: Int){WidthController.publishRuntime(app,listOf(WidthController.Reading(id,true,frames,WidthRuntime.decode(WidthParametersTest.bulk()))));idle()}
    private fun launch(){controller=Robolectric.buildActivity(Host::class.java).setup().visible();group().setExpanded(true);layout()}
    private fun layout(){repeat(2){idle();controller!!.get().findViewById<View>(android.R.id.content).apply {measure(View.MeasureSpec.makeMeasureSpec(this@WidthUiTest.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(4800,View.MeasureSpec.EXACTLY));layout(0,0,this@WidthUiTest.width,4800)}};idle()}
    @Suppress("RestrictedApi") private fun row(key: String): View {val list=fragment().listView;val adapter=list.adapter as PreferenceGroupAdapter;val i=adapter.getPreferenceAdapterPosition(key);assertTrue("Visible $key",i>=0);(list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i,0);layout();return requireNotNull(list.findViewHolderForAdapterPosition(i)).itemView}
    private fun number(c: BassParameters.Control)=fragment().findPreference<BassNumericPreference>(c.key)!!
    @Test fun allTwentyNumericRowsHaveRealInputMicrostepsAndSingleResetWhilePowerRemainsOff() {
        prefs.edit().putInt(p.MODE,2).commit();launch()
        for(c in p.all){val step=if(c in listOf(p.autoWindow,p.meterWindow))10 else 1;val entered=if(c.default+step<=c.max)c.default+step else c.default-step
            row(c.key).findViewById<View>(R.id.seekbar_value).performClick();idle();val dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val input=dialog.findViewById<EditText>(android.R.id.text1)!!;input.setText("NaN");dialog.getButton(-1).performClick();assertTrue(dialog.isShowing)
            input.setText(c.number(entered));dialog.getButton(-1).performClick();assertFalse(c.key,dialog.isShowing);assertEquals(entered,number(c).value)
            row(c.key).findViewById<View>(R.id.bass_decrement).performClick();idle();assertEquals(entered-step,number(c).value)
            row(c.key).findViewById<View>(R.id.bass_increment).performClick();idle();assertEquals(entered,number(c).value)
            row(c.key).findViewById<View>(R.id.bass_reset).performClick();idle();assertEquals(c.default,number(c).value);assertFalse(group().isChecked);assertFalse(prefs.getBoolean(p.ENABLED,false))
        }
        fragment().findPreference<FieldChoicePreference>(p.MODE)!!.choose(0);idle()
        val c=p.legacy;row(c.key).findViewById<View>(R.id.seekbar_value).performClick();idle();val d=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog;d.findViewById<EditText>(android.R.id.text1)!!.setText("61");d.getButton(-1).performClick();idle();assertEquals(61f,prefs.getFloat(p.LEGACY,0f))
        row(c.key).findViewById<View>(R.id.bass_decrement).performClick();idle();assertEquals(60f,prefs.getFloat(p.LEGACY,0f));row(c.key).findViewById<View>(R.id.bass_increment).performClick();idle();assertEquals(61f,prefs.getFloat(p.LEGACY,0f));row(c.key).findViewById<View>(R.id.bass_reset).performClick();idle();assertEquals(60f,prefs.getFloat(p.LEGACY,0f))
    }
    @Test fun linkedEditsAndSingleResetPreserveOthersAndOldCapabilityKeepsLegacyEditable() {
        prefs.edit().putFloat(p.LEGACY,58.5f).putInt(p.MODE,2).putInt(p.low.key,1100).putInt(p.high.key,1300).commit();launch();assertEquals(58.5f,prefs.getFloat(p.LEGACY,0f))
        fragment().findPreference<SwitchPreferenceCompat>(p.LINK)!!.isChecked=true;idle();assertEquals(1300,number(p.high).value)
        assertTrue(number(p.low).requestValue(1201));idle();p.bands.forEach {assertEquals(1201,number(it).value)}
        assertTrue(number(p.low).resetToDefault());idle();assertEquals(1000,number(p.low).value);assertEquals(1201,number(p.high).value)
        WidthController.publishCapabilities(app,16383);idle();assertFalse(number(p.master).isEnabled);assertTrue(number(p.legacy).isEnabled);assertEquals(2,prefs.getInt(p.MODE,0));assertEquals(1201,prefs.getInt(p.high.key,0))
        WidthController.publishCapabilities(app,32767);idle();assertTrue(number(p.master).isEnabled);group().setExpanded(false);layout();assertEquals(1,fragment().listView.adapter!!.itemCount)
        group().setExpanded(true);layout();controller!!.recreate();layout();assertFalse(group().isChecked);assertEquals("立体声宽度控制",group().title.toString());assertEquals(1201,number(p.high).value)
    }
    @Test fun temporaryAuditionStopsOnFoldScrollPausePowerCapabilityAndSessionClosure() {
        prefs.edit().putBoolean(p.ENABLED,true).commit();launch();val choice=fragment().findPreference<FieldChoicePreference>("width1_audition")!!
        assertTrue(choice.choose(1));idle();group().setExpanded(false);idle();assertEquals(0,WidthController.audition);group().setExpanded(true);layout()
        assertTrue(choice.choose(2));idle();fragment().requireView().apply {visibility=View.GONE;android.view.ViewTreeObserver::class.java.getDeclaredMethod("dispatchOnScrollChanged").apply {isAccessible=true}.invoke(viewTreeObserver)};idle();assertEquals(0,WidthController.audition);fragment().requireView().visibility=View.VISIBLE;layout()
        assertTrue(choice.choose(3));idle();controller!!.pause();idle();assertEquals(0,WidthController.audition);controller!!.resume();layout()
        assertTrue(choice.choose(1));idle();prefs.edit().putBoolean(p.ENABLED,false).commit();idle();assertEquals(0,WidthController.audition);prefs.edit().putBoolean(p.ENABLED,true).commit();idle()
        assertTrue(choice.choose(2));idle();WidthController.publishCapabilities(app,16383);idle();assertEquals(0,WidthController.audition);WidthController.publishCapabilities(app,32767);idle()
        assertTrue(choice.choose(3));idle();WidthController.sessionClosed(app,id);idle();assertEquals(0,WidthController.audition);assertFalse(prefs.all.keys.any {it.contains("audition")})
    }
    @Test fun allSixGroupsRenderAtBothWidths(){for(w in listOf(320,411))render(w,1f)}
    @Test fun monitoringHidesOldSignalWhenDisabledIdleOrCapabilityIsLost() {
        launch();val meter=fragment().findPreference<Preference>("width1_actual")!!
        assertTrue(meter.summary.toString().contains("未启用"));assertFalse(meter.summary.toString().contains("+0.5000"))
        prefs.edit().putBoolean(p.ENABLED,true).commit();runtime(1024);runtime(1536);assertTrue(meter.summary.toString().contains("+0.5000"))
        runtime(1536);assertTrue(meter.summary.toString().contains("读数未更新"));assertFalse(meter.summary.toString().contains("+0.5000"))
        WidthController.publishCapabilities(app,16383);idle();assertTrue(meter.summary.toString().contains("不支持专业宽度监测"))
    }
    @Test fun allSixGroupsRenderWithLargeFonts(){for(w in listOf(320,411))render(w,1.5f)}
    private fun render(w: Int,font: Float) {
        width=w;RuntimeEnvironment.setFontScale(font);prefs.edit().putInt(p.MODE,2).putBoolean(p.ENABLED,true).commit();launch();runtime(1024);runtime(1536)
        try {
            verify(row(p.ENABLED).findViewById(android.R.id.title))
            p.all.forEach {val v=row(it.key);val value=v.findViewById<TextView>(R.id.seekbar_value);val minus=v.findViewById<View>(R.id.bass_decrement);val plus=v.findViewById<View>(R.id.bass_increment);assertTrue(minus.right<=value.left && value.right<=plus.left);verify(v.findViewById(android.R.id.title));verify(value)}
            p.switches.forEach {verify(row(it).findViewById(android.R.id.title))}
            val groups=listOf(p.ENABLED to "width1_support","width1_group2" to p.LINK,"width1_group3" to p.MONO_SLOPE,"width1_group4" to p.autoMax.key,"width1_group5" to p.strength.key,"width1_group6" to "width1_audition")
            groups.forEachIndexed {i,pair->capture(pair.first,pair.second,"group${i+1}-$w-${(font*100).toInt()}")}
            assertTrue(fragment().findPreference<Preference>("width1_actual")!!.summary.toString().contains("44100 Hz"))
        }finally{controller?.pause()?.stop()?.destroy();controller=null;idle()}
    }
    private fun verify(v: TextView){val l=requireNotNull(v.layout);repeat(l.lineCount){assertEquals("No truncation: ${v.text}",0,l.getEllipsisCount(it));assertTrue("Width fits ${v.text}: ${l.getLineMax(it)} / ${v.width-v.compoundPaddingLeft-v.compoundPaddingRight}",l.getLineMax(it)<=v.width-v.compoundPaddingLeft-v.compoundPaddingRight+1)};assertTrue("Height fits ${v.text}: ${l.height} / ${v.height-v.totalPaddingTop-v.totalPaddingBottom}",l.height<=v.height-v.totalPaddingTop-v.totalPaddingBottom);assertTrue("Complete ${v.text}: ${l.getLineEnd(l.lineCount-1)}",l.getLineEnd(l.lineCount-1)>=v.text.length)}
    @Suppress("RestrictedApi") private fun capture(first: String,last: String,name: String) {
        val list=fragment().listView;val adapter=list.adapter as PreferenceGroupAdapter;val start=adapter.getPreferenceAdapterPosition(first);val end=adapter.getPreferenceAdapterPosition(last);(list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(start,0);layout();val root=fragment().requireView() as ViewGroup
        fun bounds(i: Int)=requireNotNull(list.findViewHolderForAdapterPosition(i)).itemView.let {v->Rect(0,0,v.width,v.height).apply {root.offsetDescendantRectToMyCoords(v,this)}}
        val top=bounds(start).top;val bottom=bounds(end).bottom;assertTrue(top>=0 && bottom>top && bottom<=root.height)
        assertEquals(width,root.width);val bitmap=Bitmap.createBitmap(root.width,bottom-top,Bitmap.Config.ARGB_8888);try {bitmap.eraseColor(MaterialColors.getColor(root,com.google.android.material.R.attr.colorSurface));root.draw(Canvas(bitmap).apply {translate(0f,-top.toFloat())});val folder=File("build/reports/stereo-width").apply {mkdirs()};File(folder,"$name.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{bitmap.recycle()}
    }
    class Host: FragmentActivity() {
        override fun onCreate(state: Bundle?) {setTheme(R.style.Theme_RootlessJamesDSP);super.onCreate(state);setContentView(FrameLayout(this).apply {addView(MaterialCardView(context).apply {addView(FragmentContainerView(context).apply {id=WIDTH_ID},ViewGroup.LayoutParams(-1,-1))},FrameLayout.LayoutParams(-1,-1))});if(state==null)supportFragmentManager.beginTransaction().add(WIDTH_ID,PreferenceGroupFragment.newInstance(WidthParameters.NAMESPACE,R.xml.dsp_stereowide_preferences),"width").commitNow()}
        companion object {const val WIDTH_ID=0x745145}
    }
}
