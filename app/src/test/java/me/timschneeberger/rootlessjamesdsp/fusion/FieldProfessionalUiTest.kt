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
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FieldProfessionalUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val preferences get() = FieldController.preferences(app)
    private val p = FieldParameters
    private var controller: ActivityController<Host>? = null
    private var width = 320
    private fun fragment() = controller!!.get().supportFragmentManager.findFragmentByTag("field") as ViperEffectPreferenceFragment
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun runtime(frames: Int = 0, correlation: Int = 10000, mid: Int = -600, side: Int = Int.MIN_VALUE,
        peak: Int = -300, ready: Boolean = true, actualAudition: Int = 0, rate: Int = 48000, band: List<Int> = listOf(150,16000,120)) {
        FieldController.publishRuntime(app,listOf(FieldController.Reading("stream",true,rate,frames,band,correlation,mid,side,peak,actualAudition,ready)))
        idle()
    }
    @Before fun prepare() {
        RuntimeEnvironment.setFontScale(1f); preferences.edit().clear().commit()
        FieldController.stopAudition(app); FieldController.publishRuntime(app,emptyList()); FieldController.publishCapabilities(app,511)
        runtime()
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy(); controller = null; idle()
        FieldController.stopAudition(app); FieldController.publishRuntime(app,emptyList())
        RuntimeEnvironment.setFontScale(1f)
    }
    private fun launch(expanded: Boolean = true) {
        controller = Robolectric.buildActivity(Host::class.java).setup().visible()
        fragment().effectHeader.setExpanded(expanded); layout()
    }
    private fun layout() {
        repeat(2) {
            idle(); controller!!.get().findViewById<View>(android.R.id.content).apply {
                measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
                layout(0,0,width,2400)
            }
        }; idle()
    }
    @Suppress("RestrictedApi")
    private fun row(key: String): View {
        val list = fragment().listView; val adapter = list.adapter as PreferenceGroupAdapter
        val position = adapter.getPreferenceAdapterPosition(key); assertTrue("Visible row $key",position >= 0)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position,0); layout()
        return requireNotNull(list.findViewHolderForAdapterPosition(position)).itemView
    }
    private fun number(control: BassParameters.Control) = fragment().findPreference<BassNumericPreference>(control.key)!!
    private fun mode(value: Int) { assertTrue(fragment().findPreference<FieldChoicePreference>(p.MODE)!!.choose(value)); layout() }
    private fun dispatchScroll() {
        android.view.ViewTreeObserver::class.java.getDeclaredMethod("dispatchOnScrollChanged").apply { isAccessible = true }
            .invoke(fragment().requireView().viewTreeObserver)
        idle()
    }
    @Test fun renamedCardStartsCollapsedAndOriginalNumbersHaveHonestIntegerPrecision() {
        launch(false)
        assertEquals("立体声成像",fragment().effectHeader.title)
        assertFalse(fragment().effectHeader.isExpanded); assertEquals(1,fragment().listView.adapter!!.itemCount)
        row("v_field").performClick(); layout()
        assertTrue(fragment().effectHeader.isExpanded); assertFalse(fragment().effectHeader.isChecked)
        row(p.legacyWidth.key).findViewById<View>(R.id.bass_increment).performClick(); assertEquals(1,preferences.getInt(p.legacyWidth.key,-1))
        row(p.legacyCenter.key).findViewById<View>(R.id.bass_decrement).performClick(); assertEquals(149,preferences.getInt(p.legacyCenter.key,-1))
        row(p.depth.key).findViewById<View>(R.id.seekbar_value).performClick(); idle()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val input = dialog.findViewById<EditText>(android.R.id.text1)!!
        input.setText("200.5"); dialog.getButton(-1).performClick(); assertTrue(dialog.isShowing)
        input.setText("555"); dialog.getButton(-1).performClick(); assertFalse(dialog.isShowing)
        assertEquals(555,preferences.getInt(p.depth.key,-1)); assertFalse(number(p.depth).summary.toString().contains("ms"))
        assertFalse(fragment().effectHeader.isChecked)
    }
    @Test fun modeChangesRetainLegacyValuesAndSeparateDepthSwitchesAcrossFoldAndRecreation() {
        preferences.edit().putInt(p.legacyWidth.key,37).putInt(p.legacyCenter.key,163).putInt(p.depth.key,555).commit()
        launch()
        assertTrue(fragment().findPreference<SwitchPreferenceCompat>(p.LEGACY_DEPTH_ENABLED)!!.isChecked)
        fragment().findPreference<SwitchPreferenceCompat>(p.LEGACY_DEPTH_ENABLED)!!.apply {
            assertTrue(callChangeListener(false)); isChecked = false
        }
        mode(1)
        assertNull(fragment().findPreference<Preference>(p.legacyWidth.key)); assertNull(fragment().findPreference<Preference>(p.legacyCenter.key))
        assertNotNull(fragment().findPreference<Preference>(p.midTrim.key)); assertEquals(555,number(p.depth).value)
        assertFalse(fragment().findPreference<SwitchPreferenceCompat>(p.MS_DEPTH_ENABLED)!!.isChecked)
        fragment().findPreference<SwitchPreferenceCompat>(p.MS_DEPTH_ENABLED)!!.apply {
            assertTrue(callChangeListener(true)); isChecked = true
        }
        repeat(2) { fragment().effectHeader.setExpanded(false); layout(); fragment().effectHeader.setExpanded(true); layout() }
        assertNull(fragment().findPreference<Preference>(p.legacyWidth.key))
        mode(0); assertEquals(37,number(p.legacyWidth).value); assertEquals(163,number(p.legacyCenter).value)
        assertFalse(fragment().findPreference<SwitchPreferenceCompat>(p.LEGACY_DEPTH_ENABLED)!!.isChecked)
        mode(1); controller!!.recreate(); layout()
        assertTrue(fragment().findPreference<SwitchPreferenceCompat>(p.MS_DEPTH_ENABLED)!!.isChecked)
        assertFalse(fragment().effectHeader.isChecked)
    }
    @Test fun allNumericControlsStepAndResetWithoutEnablingParentOrFrequencyProcessing() {
        launch()
        (p.legacy + p.advanced.filter { it != p.width }).forEach { control ->
            val delta = if (control.default == control.max) -1 else 1
            assertTrue(number(control).step(delta)); assertEquals(control.default+delta,preferences.getInt(control.key,-1))
            assertTrue(number(control).resetToDefault()); assertEquals(control.default,preferences.getInt(control.key,-1))
        }
        mode(1)
        assertTrue(number(p.width).step(1)); assertEquals(1001,preferences.getInt(p.width.key,-1))
        assertTrue(number(p.width).resetToDefault()); assertEquals(1000,preferences.getInt(p.width.key,-1))
        assertFalse(fragment().effectHeader.isChecked)
        assertFalse(preferences.getBoolean(p.BAND_ENABLED,true)); assertFalse(preferences.getBoolean(p.FOCUS_ENABLED,true))
    }
    @Test fun frequencyCrossingInputAndButtonsAreRejectedAndBrokenImportsAreCanonical() {
        preferences.edit().putInt(p.bandLow.key,2000).putInt(p.bandHigh.key,2000).putFloat(p.width.key,1000.5f).commit()
        launch()
        assertEquals(p.packet(preferences.all)[4],number(p.bandLow).value)
        assertEquals(p.packet(preferences.all)[5],number(p.bandHigh).value)
        assertTrue(number(p.bandLow).requestValue(1999)); assertTrue(number(p.bandHigh).requestValue(2000)); layout()
        row(p.bandLow.key).findViewById<View>(R.id.bass_increment).performClick(); idle()
        assertEquals(1999,preferences.getInt(p.bandLow.key,-1)); assertTrue(ShadowToast.getTextOfLatestToast().contains("先调高上限"))
        row(p.bandLow.key).findViewById<View>(R.id.seekbar_value).performClick(); idle()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val input = dialog.findViewById<EditText>(android.R.id.text1)!!
        for (invalid in listOf("2000","1999.5","NaN","99")) {
            input.setText(invalid); dialog.getButton(-1).performClick(); assertTrue(dialog.isShowing)
        }
        input.setText("150"); dialog.getButton(-1).performClick(); assertFalse(dialog.isShowing)
        assertTrue(number(p.bandHigh).resetToDefault()); assertEquals(16000,number(p.bandHigh).value)
    }
    @Test fun oldDriverExposesOnlyOriginalIntegerControlsAndKeepsSavedProfessionalMode() {
        FieldController.publishCapabilities(app,255)
        preferences.edit().putInt(p.MODE,1).putInt(p.width.key,1234).putBoolean(p.LEGACY_DEPTH_ENABLED,false).commit()
        launch()
        assertEquals(0,fragment().findPreference<FieldChoicePreference>(p.MODE)!!.value)
        assertFalse(fragment().findPreference<FieldChoicePreference>(p.MODE)!!.isEnabled)
        assertTrue(number(p.legacyWidth).isEnabled); assertTrue(number(p.legacyCenter).isEnabled); assertTrue(number(p.depth).isEnabled)
        assertFalse(number(p.midTrim).isEnabled); assertFalse(fragment().findPreference<SwitchPreferenceCompat>(p.BAND_ENABLED)!!.isEnabled)
        assertTrue(fragment().findPreference<SwitchPreferenceCompat>(p.LEGACY_DEPTH_ENABLED)!!.isChecked)
        assertEquals(1,preferences.getInt(p.MODE,-1)); assertEquals(1234,preferences.getInt(p.width.key,-1))
        FieldController.publishCapabilities(app,511); layout()
        assertEquals(1,fragment().findPreference<FieldChoicePreference>(p.MODE)!!.value)
        assertEquals(1234,number(p.width).value); assertNull(fragment().findPreference<Preference>(p.legacyWidth.key))
    }
    @Test fun combinedMonoMidAndSideAuditionClearOnFoldScrollModuleOffPauseAndRebuild() {
        launch()
        val audition = fragment().findPreference<FieldChoicePreference>("field_audition")!!
        assertFalse(audition.isEnabled)
        row("v_field").findViewById<MaterialSwitch>(R.id.switchWidget).isChecked = true; layout()
        fun start(side: Boolean = false) {
            assertTrue(fragment().findPreference<FieldChoicePreference>("field_audition")!!.choose(if (side) 2 else 1)); idle()
            assertEquals(if (side) 3 else 1,FieldController.audition)
            assertTrue(preferences.all.keys.none { it.contains("audition") })
        }
        start(); start(true)
        fragment().effectHeader.setExpanded(false); idle(); assertEquals(0,FieldController.audition)
        fragment().effectHeader.setExpanded(true); layout(); start()
        fragment().requireView().visibility = View.INVISIBLE
        assertFalse(fragment().requireView().isShown)
        dispatchScroll(); assertEquals(0,FieldController.audition)
        fragment().requireView().visibility = View.VISIBLE; layout(); start()
        val viewport = controller!!.get().findViewById<ViewGroup>(android.R.id.content)
        viewport.scrollTo(0,viewport.height+1)
        assertTrue("Scrolled cards retain VISIBLE state",fragment().requireView().isShown)
        assertFalse("The whole card is clipped outside its parent viewport",fragment().requireView().getGlobalVisibleRect(Rect()))
        dispatchScroll(); assertEquals(0,FieldController.audition)
        viewport.scrollTo(0,0); layout()
        assertTrue(fragment().requireView().getGlobalVisibleRect(Rect())); start()
        preferences.edit().putBoolean("v_field",false).commit(); idle(); assertEquals(0,FieldController.audition)
        preferences.edit().putBoolean("v_field",true).commit(); idle(); start()
        controller!!.recreate(); layout(); assertEquals(0,FieldController.audition)
        start(); controller!!.pause(); idle(); assertEquals(0,FieldController.audition); controller!!.resume()
    }
    @Test fun correlationSilenceSingleChannelIncompleteWindowAndIdleAreNeverReportedAsZero() {
        preferences.edit().putBoolean("v_field",true).commit(); launch()
        fun status() = fragment().findPreference<Preference>("field_actual")!!.summary.toString()
        val negativeCue = "单声道合并可能减弱部分成分，可试听检查"
        runtime(64); assertTrue(status().contains("相关度 +1.0000")); assertTrue(status().contains("Side RMS −∞"))
        assertFalse(status().contains(negativeCue))
        assertTrue(status().contains("选频关闭（全频宽度）")); assertTrue(status().contains("低频聚焦关闭"))
        assertFalse(status().contains("成像 150–16000 Hz")); assertFalse(status().contains("聚焦 120 Hz"))
        preferences.edit().putBoolean(p.BAND_ENABLED,true).commit(); idle()
        assertTrue(status().contains("成像 150–16000 Hz")); assertTrue(status().contains("低频聚焦关闭"))
        preferences.edit().putBoolean(p.FOCUS_ENABLED,true).commit(); idle()
        assertTrue(status().contains("聚焦 120 Hz"))
        preferences.edit().putBoolean(p.BAND_ENABLED,false).putBoolean(p.FOCUS_ENABLED,false).commit(); idle()
        assertTrue(status().contains("选频关闭（全频宽度）")); assertTrue(status().contains("低频聚焦关闭"))
        runtime(128,correlation=-10000,mid=Int.MIN_VALUE,side=-600); assertTrue(status().contains("相关度 -1.0000"))
        assertTrue(status().contains(negativeCue))
        runtime(192,correlation=Int.MIN_VALUE,mid=-1200,side=-1200); assertTrue(status().contains("相关度 未定义"))
        assertFalse(status().contains(negativeCue))
        runtime(256,correlation=Int.MIN_VALUE,mid=Int.MIN_VALUE,side=Int.MIN_VALUE,peak=Int.MIN_VALUE)
        assertTrue(status().contains("相关度 未定义")); assertTrue(status().contains("sample-peak −∞")); assertFalse(status().contains("+0.0000"))
        assertFalse(status().contains(negativeCue))
        runtime(320,ready=false); assertTrue(status().contains("等待完整 100 ms"))
        runtime(320,correlation=-10000); assertTrue(status().contains("当前未处理音频")); assertFalse(status().contains("相关度 +1.0000"))
        assertFalse(status().contains(negativeCue))
        preferences.edit().putBoolean(p.BAND_ENABLED,true).commit(); idle()
        runtime(384,rate=8000,band=listOf(0,0,120)); assertTrue(status().contains("成像频带不可用"))
    }
    private fun touch(slider: Slider,action: Int,time: Long,fraction: Float) {
        assertTrue("The dragged slider remains attached",slider.isAttachedToWindow)
        assertTrue("The dragged slider remains enabled and visible",slider.isEnabled && slider.isShown && slider.getGlobalVisibleRect(Rect()))
        val event = MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,slider.trackSidePadding+slider.trackWidth*fraction,slider.height/2f,0)
        try { assertTrue("Touch action=$action fraction=$fraction track=${slider.trackWidth}",slider.dispatchTouchEvent(event)) } finally { event.recycle() }
    }
    @Test fun professionalWidthDraggingCommitsEachMoveWithoutRebindingOrDuplicateWrites() {
        launch(); mode(1)
        val bound = row(p.width.key)
        val slider = bound.findViewById<Slider>(R.id.seekbar)
        var writes = 0; var rebound = 0
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == p.width.key) writes++ }
        val observer = object : RecyclerView.AdapterDataObserver() { override fun onItemRangeChanged(positionStart: Int,itemCount: Int) { rebound += itemCount } }
        preferences.registerOnSharedPreferenceChangeListener(listener); fragment().listView.adapter!!.registerAdapterDataObserver(observer)
        try {
            val time = SystemClock.uptimeMillis()
            // The longer card now scrolls. Material waits for horizontal motion beyond touch slop
            // before starting a drag inside a scrolling parent; 5% of a narrow track is too little.
            val touchSlop = ViewConfiguration.get(slider.context).scaledTouchSlop
            val firstMove = .5f + maxOf(.1f,(touchSlop+1f)/slider.trackWidth)
            assertTrue("The first move exceeds touch slop and leaves room for a second move",firstMove < .85f)
            touch(slider,MotionEvent.ACTION_DOWN,time,.5f)
            touch(slider,MotionEvent.ACTION_MOVE,time,firstMove); idle()
            assertSame("Dragging retains its original row",bound,fragment().listView.findContainingItemView(slider))
            val first = slider.value.toInt(); assertEquals(first,preferences.getInt(p.width.key,-1))
            touch(slider,MotionEvent.ACTION_MOVE,time,.85f); idle(); assertTrue(slider.value.toInt() > first)
            assertSame("The second move retains its original row",bound,fragment().listView.findContainingItemView(slider))
            assertEquals(slider.value.toInt(),preferences.getInt(p.width.key,-1)); val count = writes
            repeat(3) { touch(slider,MotionEvent.ACTION_MOVE,time,.85f); idle() }; touch(slider,MotionEvent.ACTION_UP,time,.85f); idle()
            assertEquals(count,writes); assertEquals(0,rebound); assertFalse(fragment().effectHeader.isChecked)
        } finally {
            preferences.unregisterOnSharedPreferenceChangeListener(listener); fragment().listView.adapter!!.unregisterAdapterDataObserver(observer)
        }
    }
    @Test fun nativeRenderingAt320dpNormalAndLargeFonts() { renderGroups(320,1f); renderGroups(320,1.5f) }
    @Test @Config(qualifiers = "zh-rCN-w411dp-h891dp-port-mdpi")
    fun nativeRenderingAt411dpNormalAndLargeFonts() { renderGroups(411,1f); renderGroups(411,1.5f) }
    private fun renderGroups(width: Int,font: Float) {
        this.width = width; RuntimeEnvironment.setFontScale(font)
        preferences.edit().putInt(p.MODE,0).putBoolean("v_field",true).commit()
        try {
            launch(); runtime(64); runtime(128)
            verifyText(row("v_field").findViewById(android.R.id.title))
            p.all.filter { it != p.width }.forEach { verifyNumeric(row(it.key)) }
            capture("v_field",p.outputTrim.key,"legacy-$width-${(font*100).toInt()}")
            mode(1); verifyNumeric(row(p.width.key)); capture("v_field",p.outputTrim.key,"ms-$width-${(font*100).toInt()}")
            listOf(p.BAND_ENABLED,p.FOCUS_ENABLED).forEach { verifyText(row(it).findViewById(android.R.id.summary)) }
            capture(p.BAND_ENABLED,p.focusWidth.key,"bands-$width-${(font*100).toInt()}")
            // Keep both sub-switches at their off defaults: configured targets must not imply active filtering.
            listOf("field_help","field_actual").forEach { verifyText(row(it).findViewById(android.R.id.summary)) }
            capture("field_audition","field_actual","status-$width-${(font*100).toInt()}")
        } finally { controller?.pause()?.stop()?.destroy(); controller = null; idle() }
    }
    private fun verifyText(text: TextView) {
        val layout = requireNotNull(text.layout)
        repeat(layout.lineCount) {
            assertEquals(0,layout.getEllipsisCount(it))
            val available = text.width-text.compoundPaddingLeft-text.compoundPaddingRight
            // getLineWidth includes trailing whitespace after wrapping; getLineMax measures visible text.
            if (layout.getLineWidth(it) > available+1f) {
                println("Visible text bounds: lineWidth=${layout.getLineWidth(it)}, lineMax=${layout.getLineMax(it)}, available=$available, line=${text.text.subSequence(layout.getLineStart(it),layout.getLineEnd(it))}")
            }
            assertTrue("${text.text} fits its row",layout.getLineMax(it) <= available+1f)
        }
        assertTrue(layout.height <= text.height-text.totalPaddingTop-text.totalPaddingBottom)
        assertTrue("Complete text is laid out: ${text.text}",layout.lineCount > 0 && layout.getLineEnd(layout.lineCount-1) >= text.text.length)
    }
    private fun verifyNumeric(row: View) {
        val number = row.findViewById<TextView>(R.id.seekbar_value); val minus = row.findViewById<View>(R.id.bass_decrement)
        val plus = row.findViewById<View>(R.id.bass_increment); val reset = row.findViewById<TextView>(R.id.bass_reset)
        assertTrue(minus.right <= number.left && number.right <= plus.left && plus.right <= reset.left)
        listOf(row.findViewById<TextView>(android.R.id.title),number,reset).forEach(::verifyText)
    }
    @Suppress("RestrictedApi")
    private fun capture(firstKey: String,lastKey: String,name: String) {
        val list = fragment().listView; val adapter = list.adapter as PreferenceGroupAdapter
        val firstPosition = adapter.getPreferenceAdapterPosition(firstKey); val lastPosition = adapter.getPreferenceAdapterPosition(lastKey)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(firstPosition,0); layout()
        val root = fragment().requireView() as ViewGroup
        fun bounds(position: Int) = requireNotNull(list.findViewHolderForAdapterPosition(position)).itemView.let { view ->
            Rect(0,0,view.width,view.height).apply { root.offsetDescendantRectToMyCoords(view,this) }
        }
        val top = bounds(firstPosition).top; val bottom = bounds(lastPosition).bottom
        assertTrue(top >= 0 && bottom > top && bottom <= root.height)
        val bitmap = Bitmap.createBitmap(root.width,bottom-top,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(MaterialColors.getColor(root,com.google.android.material.R.attr.colorSurface))
            root.draw(Canvas(bitmap).apply { translate(0f,-top.toFloat()) })
            val folder = File("build/reports/field-professional").apply { mkdirs() }
            File(folder,"$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
    }
    class Host : FragmentActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP); super.onCreate(state)
            setContentView(FrameLayout(this).apply {
                addView(MaterialCardView(context).apply {
                    addView(FragmentContainerView(context).apply { id = FIELD_ID },ViewGroup.LayoutParams(-1,-1))
                },FrameLayout.LayoutParams(-1,-1))
            })
            if (state == null) supportFragmentManager.beginTransaction().add(FIELD_ID,ViperEffectPreferenceFragment.newInstance("v_field"),"field").commitNow()
        }
        companion object { const val FIELD_ID = 0x745132 }
    }
}
