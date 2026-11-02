package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
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
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DiffAdvancedUiTest {
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=DiffController.preferences(app)
    private val p=DiffAdvancedParameters
    private var controller: ActivityController<Host>?=null
    private var width=320
    private fun fragment()=controller!!.get().supportFragmentManager.findFragmentByTag("diff") as ViperEffectPreferenceFragment
    private fun idle()=shadowOf(Looper.getMainLooper()).idle()
    private fun runtime(frames: Int=0, data: IntArray=DiffAdvancedParametersTest.bulk()) {
        DiffController.publishRuntime(app,listOf(DiffController.Reading("stream",true,48000,frames,DiffRuntimeReader.decodeBulk(data))));idle()
    }
    @Before fun prepare() {
        RuntimeEnvironment.setFontScale(1f);prefs.edit().clear().putInt(DiffParameters.MODE,1).commit()
        DiffController.stopAudition(app);DiffController.publishRuntime(app,emptyList());DiffController.publishCapabilities(app,4095);runtime()
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy();controller=null;idle();DiffController.stopAudition(app)
        DiffController.publishRuntime(app,emptyList());RuntimeEnvironment.setFontScale(1f)
    }
    private fun launch(expanded: Boolean=true) {
        controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        fragment().effectHeader.setExpanded(expanded); layout()
    }
    private fun layout() {
        repeat(2) { idle(); controller!!.get().findViewById<View>(android.R.id.content).apply {
            measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            layout(0,0,width,2400)
        } }; idle()
    }
    @Suppress("RestrictedApi") private fun row(key: String): View {
        val list=fragment().listView; val adapter=list.adapter as PreferenceGroupAdapter
        val pos=adapter.getPreferenceAdapterPosition(key); assertTrue("Visible row $key",pos>=0)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos,0); layout()
        return requireNotNull(list.findViewHolderForAdapterPosition(pos)).itemView
    }
    private fun number(c: BassParameters.Control)=fragment().findPreference<BassNumericPreference>(c.key)!!
    @Test fun everyNewNumericControlHasValidatedInputFineButtonsAndIndependentReset() {
        launch()
        for(c in p.all) {
            val number=number(c)
            val entered=if(c==p.window)80 else if(c.default==c.max)c.default-1 else c.default+1
            row(c.key).findViewById<View>(R.id.seekbar_value).performClick();idle()
            val dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val input=dialog.findViewById<EditText>(android.R.id.text1)!!
            input.setText(if(c==p.window)"60" else "NaN");dialog.getButton(-1).performClick();assertTrue(dialog.isShowing)
            input.setText(c.number(entered));dialog.getButton(-1).performClick();assertFalse(dialog.isShowing)
            assertEquals(entered,number.value)
            row(c.key).findViewById<View>(R.id.bass_decrement).performClick();idle()
            assertEquals(if(c==p.window)40 else entered-1,number.value)
            row(c.key).findViewById<View>(R.id.bass_increment).performClick();idle();assertEquals(entered,number.value)
            row(c.key).findViewById<View>(R.id.bass_reset).performClick();idle();assertEquals(c.default,number.value)
            assertFalse(fragment().effectHeader.isChecked)
            p.switches.forEach { assertFalse(prefs.getBoolean(it,false)) }
        }
        val choice=fragment().findPreference<FieldChoicePreference>(p.GUARD_MODE)!!
        assertEquals(2,choice.value);assertTrue(choice.choose(0))
        row(p.GUARD_MODE).performClick();idle()
        (ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog).getButton(-3).performClick();idle();assertEquals(2,choice.value)
    }
    @Test fun fineOverridePreservesFirstDelayAndSurvivesFoldRecreationAndDriverDowngrade() {
        prefs.edit().putInt(DiffParameters.delay.key,125).commit();launch()
        assertEquals(1250,number(p.fine).value)
        val fine=fragment().findPreference<SwitchPreferenceCompat>(p.FINE_ENABLED)!!
        fine.isChecked=true;idle();assertFalse(number(DiffParameters.delay).isEnabled)
        assertTrue(number(p.fine).requestValue(1234));fine.isChecked=false;idle()
        assertTrue(number(DiffParameters.delay).isEnabled);assertEquals(125,number(DiffParameters.delay).value)
        fine.isChecked=true;fragment().effectHeader.setExpanded(false);layout()
        assertEquals(1,fragment().listView.adapter!!.itemCount);fragment().effectHeader.setExpanded(true);layout()
        controller!!.recreate();layout();assertEquals("声道差分延时",fragment().effectHeader.title)
        assertEquals(1234,number(p.fine).value);assertFalse(fragment().effectHeader.isChecked)
        DiffController.publishCapabilities(app,2047);layout()
        assertFalse(number(p.fine).isEnabled);assertTrue(number(DiffParameters.delay).isEnabled)
        assertEquals(1234,prefs.getInt(p.fine.key,-1));assertNotNull(fragment().findPreference<Preference>("diff2_driver"))
        DiffController.publishCapabilities(app,4095);layout();assertEquals(1234,number(p.fine).value)
        assertFalse(number(DiffParameters.delay).isEnabled)
    }
    @Test fun extendedAuditionsClearOnFoldOffAndPauseAndTelemetryHidesStaleSignal() {
        prefs.edit().putBoolean("v_diff",true).commit();launch();runtime(64);runtime(128)
        val choice=fragment().findPreference<FieldChoicePreference>("diff_audition")!!
        assertTrue(choice.choose(3));idle();assertEquals(3,DiffController.audition)
        fragment().effectHeader.setExpanded(false);idle();assertEquals(0,DiffController.audition)
        fragment().effectHeader.setExpanded(true);layout();assertTrue(choice.choose(4));idle()
        prefs.edit().putBoolean("v_diff",false).commit();idle();assertEquals(0,DiffController.audition)
        prefs.edit().putBoolean("v_diff",true).commit();idle();assertTrue(choice.choose(4));idle()
        controller!!.pause();idle();assertEquals(0,DiffController.audition);controller!!.resume()
        fun status()=fragment().findPreference<Preference>("diff2_actual")!!.summary.toString()
        runtime(192);runtime(256);assertTrue(status().contains("低频 <200 Hz"));assertTrue(status().contains("实际50.0%"))
        runtime(320,DiffAdvancedParametersTest.bulk().apply {this[46]=Int.MIN_VALUE;this[47]=Int.MIN_VALUE;this[48]=Int.MIN_VALUE;this[49]=-900})
        assertTrue(status().contains("−∞（零Mid）"))
        runtime(320);assertTrue(status().contains("当前未处理音频"));assertFalse(status().contains("低频 <"))
        assertTrue(prefs.all.keys.none {it.contains("audition")})
    }
    @Test fun renderSixGroupsAt320dpNormalAndLargeFont() { renderGroups(320,1f);renderGroups(320,1.5f) }
    @Test @Config(qualifiers="zh-rCN-w411dp-h891dp-port-mdpi")
    fun renderSixGroupsAt411dpNormalAndLargeFont() { renderGroups(411,1f);renderGroups(411,1.5f) }
    private fun renderGroups(width: Int,font: Float) {
        this.width=width;RuntimeEnvironment.setFontScale(font);prefs.edit().putBoolean("v_diff",true).commit()
        try {
            launch();runtime(64);runtime(128)
            p.all.forEach { verifyNumeric(row(it.key)) }
            val groups=listOf(
                "diff2_quality_help" to "diff2_units",
                p.GUARD_ENABLED to p.release.key,
                p.TRANSIENT_ENABLED to p.recovery.key,
                "diff2_window_help" to p.window.key,
                "diff2_levels_help" to p.output.key,
                "diff2_monitor_help" to "diff2_actual")
            groups.forEachIndexed { i,(first,last)->
                verifyText(row(first).findViewById(android.R.id.title))
                capture(first,last,"group${i+1}-$width-${(font*100).toInt()}")
            }
            listOf("diff2_quality_help","diff2_window_help","diff2_levels_help",p.GUARD_ENABLED,p.TRANSIENT_ENABLED,"diff2_monitor_help","diff2_actual","diff2_response").forEach {
                verifyText(row(it).findViewById(android.R.id.summary))
            }
        } finally {controller?.pause()?.stop()?.destroy();controller=null;idle()}
    }
    private fun verifyText(text: TextView) {
        val layout=requireNotNull(text.layout)
        repeat(layout.lineCount) { assertEquals(0,layout.getEllipsisCount(it)); assertTrue("${text.text} fits",layout.getLineMax(it)<=text.width-text.compoundPaddingLeft-text.compoundPaddingRight+1f) }
        assertTrue(layout.height<=text.height-text.totalPaddingTop-text.totalPaddingBottom)
        assertTrue(layout.lineCount>0 && layout.getLineEnd(layout.lineCount-1)>=text.text.length)
    }
    private fun verifyNumeric(row: View) {
        val value=row.findViewById<TextView>(R.id.seekbar_value); val minus=row.findViewById<View>(R.id.bass_decrement)
        val plus=row.findViewById<View>(R.id.bass_increment); val reset=row.findViewById<TextView>(R.id.bass_reset)
        assertTrue(minus.right<=value.left && value.right<=plus.left && plus.right<=reset.left)
        listOf(row.findViewById<TextView>(android.R.id.title),value,reset).forEach(::verifyText)
    }
    @Suppress("RestrictedApi") private fun capture(first: String,last: String,name: String) {
        val list=fragment().listView; val adapter=list.adapter as PreferenceGroupAdapter
        val start=adapter.getPreferenceAdapterPosition(first); val end=adapter.getPreferenceAdapterPosition(last)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(start,0); layout()
        val root=fragment().requireView() as ViewGroup
        fun bounds(i: Int)=requireNotNull(list.findViewHolderForAdapterPosition(i)).itemView.let { view -> Rect(0,0,view.width,view.height).apply { root.offsetDescendantRectToMyCoords(view,this) } }
        val top=bounds(start).top; val bottom=bounds(end).bottom; assertTrue(top>=0 && bottom>top && bottom<=root.height)
        val bitmap=Bitmap.createBitmap(root.width,bottom-top,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(MaterialColors.getColor(root,com.google.android.material.R.attr.colorSurface)); root.draw(Canvas(bitmap).apply { translate(0f,-top.toFloat()) })
            val folder=File("build/reports/diff-advanced").apply { mkdirs() }
            File(folder,"$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
    }
    class Host: FragmentActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP); super.onCreate(state)
            setContentView(FrameLayout(this).apply { addView(MaterialCardView(context).apply {
                addView(FragmentContainerView(context).apply { id=DIFF_ID },ViewGroup.LayoutParams(-1,-1))
            },FrameLayout.LayoutParams(-1,-1)) })
            if(state==null)supportFragmentManager.beginTransaction().add(DIFF_ID,ViperEffectPreferenceFragment.newInstance("v_diff"),"diff").commitNow()
        }
        companion object { const val DIFF_ID=0x745133 }
    }
}
