package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.MenuItem
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ListView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fragment.PreferenceGroupFragment
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.convolver.ConvolverLibrary
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
class ConvolutionUiTest {
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=ConvolutionController.preferences(app)
    private val p=ConvolutionParameters
    private var controller: ActivityController<Host>?=null
    private var width=320
    private fun fragment()=controller!!.get().supportFragmentManager.findFragmentByTag("convolution") as PreferenceGroupFragment
    private fun group()=fragment().findPreference<SwitchPreferenceGroup>(p.ENABLED)!!
    private fun idle()=shadowOf(Looper.getMainLooper()).idle()
    @Before fun prepare() {
        stopKoin();val preferences=Preferences(app);startKoin {modules(module {single {preferences.App()}})}
        RuntimeEnvironment.setFontScale(1f);prefs.edit().clear().commit()
        ConvolutionController.stopAudition(app);ConvolutionController.publishRuntime(app,emptyList());ConvolutionController.publishPrepared(app,null)
        ConvolutionController.publishCapabilities(app,8191);runtime()
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy();controller=null;idle();ConvolutionController.stopAudition(app)
        ConvolutionController.publishRuntime(app,emptyList());ConvolutionController.publishPrepared(app,null);RuntimeEnvironment.setFontScale(1f);File(app.getExternalFilesDir(null),"Convolver/ui-four-path.wav").delete();File(app.getExternalFilesDir(null),"Convolver/other-ui-four.wav").delete();stopKoin()
    }
    private fun runtime(frames: Int=0,data: IntArray=ConvolutionParametersTest.bulk()) {
        // Host advertises 48k, while the James convolution itself really processes 44.1k.
        ConvolutionController.publishRuntime(app,listOf(ConvolutionController.Reading("convolution-test-stream",true,48000,frames,ConvolutionRuntimeReader.decode(data))));idle()
    }
    private fun launch() {
        controller=Robolectric.buildActivity(Host::class.java).setup().visible();group().setExpanded(true);layout()
    }
    private fun layout() {
        repeat(2){idle();controller!!.get().findViewById<View>(android.R.id.content).apply {
            measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY));layout(0,0,width,2400)
        }};idle()
    }
    @Suppress("RestrictedApi") private fun row(key: String): View {
        val list=fragment().listView;val adapter=list.adapter as PreferenceGroupAdapter;val pos=adapter.getPreferenceAdapterPosition(key)
        assertTrue("Visible row $key",pos>=0);(list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos,0);layout()
        return requireNotNull(list.findViewHolderForAdapterPosition(pos)).itemView
    }
    private fun number(c: BassParameters.Control)=fragment().findPreference<BassNumericPreference>(c.key)!!
    private fun summary(key: String)=fragment().findPreference<Preference>(key)!!.summary.toString()
    @Test fun everyNewNumericHasInputPlusMinusAndSingleResetWithoutEnablingConvolution() {
        launch()
        assertFalse(row(p.start.key).findViewById<Slider>(R.id.seekbar).isEnabled)
        for(c in p.all) {
            val number=number(c);val entered=when {c==p.predelay->23;c in p.window->100;c.default==c.max->c.default-1;else->c.default+1}
            row(c.key).findViewById<View>(R.id.seekbar_value).performClick();idle()
            val dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog;val input=dialog.findViewById<EditText>(android.R.id.text1)!!
            input.setText("NaN");dialog.getButton(-1).performClick();assertTrue(dialog.isShowing)
            input.setText(c.number(entered));dialog.getButton(-1).performClick();assertFalse("Accept ${c.key}",dialog.isShowing);assertEquals(entered,number.value)
            row(c.key).findViewById<View>(R.id.bass_decrement).performClick();idle()
            assertEquals(when {c==p.predelay->0;c in p.window->0;else->entered-1},number.value)
            row(c.key).findViewById<View>(R.id.bass_increment).performClick();idle();assertEquals(entered,number.value)
            row(c.key).findViewById<View>(R.id.bass_reset).performClick();idle();assertEquals(c.default,number.value)
            assertFalse(group().isChecked);assertFalse(prefs.getBoolean(p.ENABLED,false))
        }
        assertTrue(summary("conv1_time_units").contains("44100 Hz"));assertFalse(summary("conv1_time_units").contains("48000 Hz"))
        row(p.predelay.key).findViewById<View>(R.id.seekbar_value).performClick();idle()
        val rounded=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        rounded.findViewById<EditText>(android.R.id.text1)!!.setText("0.500");rounded.getButton(-1).performClick()
        assertFalse(rounded.isShowing);assertEquals(499,number(p.predelay).value) // 22 samples at 44.1k
        runtime(64,ConvolutionParametersTest.bulk().apply {this[2]=48003;this[7]=9601;this[8]=200008})
        assertTrue(number(p.predelay).requestValue(200000));assertEquals(200000,number(p.predelay).value)
        assertTrue(summary("conv1_actual").contains("200.008 ms"))
    }
    @Test fun legacySixFieldsRoundTripAndMalformedOldTextIsRetained() {
        prefs.edit().putString(p.LEGACY_ADVANCED,"-70;-95;17;2;0;3").commit();launch()
        val raw=p.legacyText(prefs.all);val field=fragment().findPreference<BassNumericPreference>("conv1_legacy_field_2")!!
        assertEquals(17,field.value);assertTrue(field.step(1));assertEquals("-70;-95;18;2;0;3",p.legacyText(prefs.all))
        assertTrue(field.resetToDefault());assertEquals("-70;-95;0;2;0;3",p.legacyText(prefs.all));assertFalse(prefs.all.keys.any {it.startsWith("conv1_legacy_field_")})
        prefs.edit().putString(p.LEGACY_ADVANCED,"-70;-95;2147483647;2;0;3").commit();idle()
        assertTrue(field.isEnabled);assertEquals("-70;-95;2147483647;2;0;3",p.legacyText(prefs.all));assertTrue(field.summary.toString().contains("2147483647"))
        assertTrue(field.resetToDefault());assertEquals("-70;-95;0;2;0;3",p.legacyText(prefs.all))
        prefs.edit().putString(p.LEGACY_ADVANCED,"bad;old;text").commit();idle();assertFalse(field.isEnabled)
        assertEquals("bad;old;text",p.legacyText(prefs.all));assertTrue(summary("conv1_legacy_warning").contains("原文本原样保留"))
        prefs.edit().putString(p.LEGACY_ADVANCED,raw).commit();idle();assertTrue(field.isEnabled);assertEquals(17,field.value)
    }
    @Test fun channelLinkOnlyAffectsFutureEditsAndOldDriverKeepsSavedValues() {
        prefs.edit().putInt(p.left.key,-20).putInt(p.right.key,15).commit();launch()
        assertEquals(-20,number(p.left).value);assertEquals(15,number(p.right).value)
        assertTrue(number(p.left).resetToDefault());idle();assertEquals(0,number(p.left).value);assertEquals(15,number(p.right).value)
        assertTrue(number(p.left).requestValue(12));assertEquals(12,number(p.right).value)
        fragment().findPreference<SwitchPreferenceCompat>(p.CHANNEL_LINK)!!.isChecked=false;idle()
        assertTrue(number(p.left).requestValue(-14));assertEquals(12,number(p.right).value)
        ConvolutionController.publishCapabilities(app,4095);layout();assertFalse(number(p.left).isEnabled);assertEquals(-14,prefs.getInt(p.left.key,0))
        assertFalse(number(p.left).requestValue(0));assertNotNull(fragment().findPreference<Preference>("conv1_driver"))
        assertTrue(number(p.length).isEnabled);assertFalse(group().isChecked)
        ConvolutionController.publishCapabilities(app,8191);layout();assertTrue(number(p.left).isEnabled);assertEquals(-14,number(p.left).value)
        group().setExpanded(false);layout();assertEquals(1,fragment().listView.adapter!!.itemCount)
        group().setExpanded(true);layout();controller!!.recreate();layout();assertFalse(group().isChecked);assertEquals(-14,number(p.left).value)
        assertFalse(group().title.toString().contains("ViPER"))
    }
    @Test fun temporaryAuditionClearsOnFoldOffAndPauseAndReadingsRejectStaleSignals() {
        prefs.edit().putBoolean(p.ENABLED,true).commit();launch();runtime(64);runtime(128)
        val choice=fragment().findPreference<FieldChoicePreference>("conv1_audition")!!
        assertTrue(choice.choose(1));idle();assertEquals(1,ConvolutionController.audition)
        group().setExpanded(false);idle();assertEquals(0,ConvolutionController.audition);group().setExpanded(true);layout()
        assertTrue(choice.choose(2));idle();prefs.edit().putBoolean(p.ENABLED,false).commit();idle();assertEquals(0,ConvolutionController.audition)
        prefs.edit().putBoolean(p.ENABLED,true).commit();idle();assertTrue(choice.choose(3));idle();controller!!.pause();idle();assertEquals(0,ConvolutionController.audition);controller!!.resume()
        runtime(192);runtime(256);assertTrue(summary("conv1_actual").contains("正常混合输出：峰值"))
        runtime(320,ConvolutionParametersTest.bulk().apply {this[30]=ConvolutionRuntimeReader.FOLD_NEGATIVE_INFINITY});assertTrue(summary("conv1_actual").contains("−∞（零中央分量）"))
        runtime(320);assertTrue(summary("conv1_actual").contains("当前未处理音频"));assertFalse(summary("conv1_actual").contains("正常混合输出：峰值"))
        ConvolutionController.publishPrepared(app,prepared().copy(originalPeakDb=Double.NEGATIVE_INFINITY,workingPeakDb=Double.NaN));idle()
        assertTrue(summary("conv1_metadata").contains("窗口前峰值 −∞ dBFS"));assertTrue(summary("conv1_metadata").contains("工作核峰值 未知"))
        assertTrue(prefs.all.keys.none {it.contains("audition")})
    }
    @Test fun renderSixGroupsAt320dpNormalAndLargeFont(){renderGroups(320,1f);renderGroups(320,1.5f)}
    @Test @Config(qualifiers="zh-rCN-w411dp-h891dp-port-mdpi")
    fun renderSixGroupsAt411dpNormalAndLargeFont(){renderGroups(411,1f);renderGroups(411,1.5f)}
    @Test fun actualIrLibraryDialogAt320dpShowsDefaultAndProtectedMenu(){renderLibrary(320,1f);renderLibrary(320,1.5f)}
    @Test @Config(qualifiers="zh-rCN-w411dp-h891dp-port-mdpi")
    fun actualIrLibraryDialogAt411dpShowsDefaultAndProtectedMenu(){renderLibrary(411,1f);renderLibrary(411,1.5f)}
    @Suppress("DEPRECATION") private fun renderLibrary(width: Int,font: Float) {
        this.width=width;RuntimeEnvironment.setFontScale(font)
        var dialog: androidx.appcompat.app.AlertDialog?=null
        try {
            launch();val library=fragment().findPreference<FileLibraryPreference>(p.FILE)!!
            assertEquals(ConvolverLibrary.DEFAULT_PATH,library.value);assertEquals("立体声增强",library.summary.toString())
            row(p.FILE).performClick();idle()
            dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val decor=dialog.window!!.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(width-32,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1800,View.MeasureSpec.AT_MOST))
            decor.layout(0,0,decor.measuredWidth,decor.measuredHeight);idle()
            val list=dialog.listView
            val names=(0 until list.adapter.count).map {list.adapter.getItem(it).toString()}
            assertEquals(setOf("立体声增强","校正型交叉馈送（HRTF）","校正型环绕一（HRTF）","校正型环绕二（HRTF）"),names.toSet())
            assertTrue(names.none {it.contains("Church") || it.contains("SwapChannels")})
            val selected=names.indexOf("立体声增强");assertEquals(selected,list.checkedItemPosition)
            for(i in 0 until list.childCount)verifyText(list.getChildAt(i).findViewById(android.R.id.text1))
            val bitmap=Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888)
            try {decor.draw(Canvas(bitmap));val folder=File("build/reports/convolution-ui").apply {mkdirs()}
                File(folder,"library-$width-${(font*100).toInt()}.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
            }finally{bitmap.recycle()}
            val selectedRow=requireNotNull(list.getChildAt(selected-list.firstVisiblePosition))
            assertTrue(list.onItemLongClickListener.onItemLongClick(list,selectedRow,selected,list.adapter.getItemId(selected)));idle()
            val popup=requireNotNull(shadowOf(app).latestPopupWindow)
            fun findList(view: View): ListView? = if(view is ListView)view else if(view is ViewGroup)(0 until view.childCount).firstNotNullOfOrNull {findList(view.getChildAt(it))}else null
            val menuList=requireNotNull(findList(popup.contentView))
            val ids=(0 until menuList.adapter.count).mapNotNull {(menuList.adapter.getItem(it) as? MenuItem)?.itemId}
            assertTrue(ids.contains(R.id.resample_selection));assertTrue(ids.contains(R.id.share_selection))
            assertFalse(ids.contains(R.id.delete_selection));assertFalse(ids.contains(R.id.rename_selection));assertFalse(ids.contains(R.id.overwrite_selection))
            popup.dismiss();assertEquals(ConvolverLibrary.DEFAULT_PATH,library.value)
            assertEquals(ConvolverLibrary.DEFAULT_SHA256,ConvolverLibrary.sha256(File(app.getExternalFilesDir(null),ConvolverLibrary.DEFAULT_PATH)))
            assertFalse(group().isChecked)
        }finally{dialog?.dismiss();controller?.pause()?.stop()?.destroy();controller=null;idle()}
    }
    private fun secondRoundSource(): File {
        val directory=File(app.getExternalFilesDir(null),"Convolver").apply{mkdirs()}
        val file=File(directory,"ui-four-path.wav")
        val b=java.nio.ByteBuffer.allocate(60).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(52).put("WAVEfmt ".toByteArray()).putInt(16).putShort(3).putShort(4)
            .putInt(44100).putInt(44100*16).putShort(16).putShort(32).put("data".toByteArray()).putInt(16)
        repeat(4){b.putFloat((it+1)*.1f)};file.writeBytes(b.array())
        prefs.edit().putString(p.FILE,"Convolver/ui-four-path.wav").commit();return file
    }
    @Test fun secondRoundAllNumericInputsStepsAndIndependentResetsPreservePowerOff() {
        val a=ConvolutionAdvancedParameters;ConvolutionController.publishCapabilities(app,16383);secondRoundSource();launch()
        for(c in a.all) {
            val n=number(c);val step=if(c.divisor==1000 && c.decimals==1)100 else 1
            val value=if(c.default==c.max)c.default-step else c.default+step
            row(c.key).findViewById<View>(R.id.seekbar_value).performClick();idle()
            val dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            dialog.findViewById<EditText>(android.R.id.text1)!!.setText(c.number(value));dialog.getButton(-1).performClick()
            assertFalse(c.key,dialog.isShowing);assertEquals(value,n.value)
            row(c.key).findViewById<View>(R.id.bass_decrement).performClick();idle();assertEquals(value-step,n.value)
            row(c.key).findViewById<View>(R.id.bass_increment).performClick();idle();assertEquals(value,n.value)
            row(c.key).findViewById<View>(R.id.bass_reset).performClick();idle();assertEquals(c.default,n.value)
            assertFalse(group().isChecked);assertFalse(prefs.getBoolean(p.ENABLED,false))
        }
        prefs.edit().putBoolean(a.DIRECT_LINK,true).putInt(a.gain[0].key,-20).putInt(a.gain[3].key,15).commit();idle()
        row(a.gain[0].key).findViewById<View>(R.id.bass_reset).performClick();idle()
        assertEquals(0,prefs.getInt(a.gain[0].key,-1));assertEquals(15,prefs.getInt(a.gain[3].key,-1))
        assertTrue(number(a.gain[0]).requestValue(12));assertEquals(12,prefs.getInt(a.gain[3].key,-1))
        prefs.edit().putInt(a.hp.key,1000).putInt(a.lp.key,1000).putBoolean(a.enabledFilter(0),true).commit();idle()
        val lowpass=fragment().findPreference<SwitchPreferenceCompat>(a.enabledFilter(1))!!
        assertFalse(lowpass.callChangeListener(true));assertTrue(number(a.lp).requestValue(1001))
        assertTrue(lowpass.callChangeListener(true));lowpass.isChecked=true;idle();assertFalse(number(a.lp).requestValue(1000))
    }
    @Test fun secondRoundABSnapshotsApplyOnlyToSameIrAndTemporaryTrimClearsOnFold() {
        val a=ConvolutionAdvancedParameters;ConvolutionController.publishCapabilities(app,16383);val file=secondRoundSource();prefs.edit().putBoolean(p.ENABLED,true).commit();launch()
        row("conv2_capture_0").performClick();idle();assertTrue(number(a.gain[0]).requestValue(20))
        row("conv2_capture_1").performClick();idle()
        val choice=fragment().findPreference<FieldChoicePreference>("conv2_comparison")!!
        assertTrue(choice.callChangeListener(1));assertEquals(0,prefs.getInt(a.gain[0].key,-1))
        assertTrue(choice.callChangeListener(2));assertEquals(20,prefs.getInt(a.gain[0].key,-1))
        assertTrue(number(a.comparison).requestValue(37));assertEquals(37,ConvolutionController.comparisonTenths)
        row(a.comparison.key).findViewById<View>(R.id.seekbar_value).performClick();idle()
        val dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.findViewById<EditText>(android.R.id.text1)!!.setText("3.8");dialog.getButton(-1).performClick();assertFalse(dialog.isShowing)
        assertEquals(38,ConvolutionController.comparisonTenths)
        row(a.comparison.key).findViewById<View>(R.id.bass_decrement).performClick();idle();assertEquals(37,ConvolutionController.comparisonTenths)
        row(a.comparison.key).findViewById<View>(R.id.bass_increment).performClick();idle();assertEquals(38,ConvolutionController.comparisonTenths)
        row(a.comparison.key).findViewById<View>(R.id.bass_reset).performClick();idle();assertEquals(0,ConvolutionController.comparisonTenths)
        assertTrue(number(a.comparison).requestValue(37))
        assertFalse(prefs.contains(a.comparison.key));group().setExpanded(false);idle();assertEquals(0,ConvolutionController.comparisonTenths)
        assertEquals(0,number(a.comparison).value);assertEquals(20,prefs.getInt(a.gain[0].key,-1))
        group().setExpanded(true);idle()
        val other=File(file.parentFile,"other-ui-four.wav");file.copyTo(other,overwrite=true)
        prefs.edit().putString(p.FILE,"Convolver/other-ui-four.wav").commit();idle();assertFalse(choice.callChangeListener(1))
    }
    @Test fun secondRoundCapabilityGateKeepsFirstRoundAndCoherentNativeStatus() {
        val a=ConvolutionAdvancedParameters;secondRoundSource();launch()
        assertFalse(number(a.hp).isEnabled);assertTrue(number(p.mix).isEnabled)
        prefs.edit().putInt(a.hp.key,321).commit();ConvolutionController.publishCapabilities(app,16383);idle()
        assertTrue(number(a.hp).isEnabled);assertEquals(321,number(a.hp).value)
        val advanced=IntArray(20).apply{this[0]=1;this[1]=2;this[2]=44100;this[3]=2;this[4]=2;this[5]=2;this[7]=441;this[8]=4410;this[9]=4096;this[14]=16;this[18]=1234;this[19]=4410}
        ConvolutionController.publishRuntime(app,listOf(ConvolutionController.Reading("convolution-test-stream",true,48000,256,ConvolutionRuntimeReader.decode(ConvolutionParametersTest.bulk()),advanced=ConvolutionAdvancedRuntime.decode(advanced))));idle()
        assertTrue(summary("conv2_runtime").contains("实际 44100 Hz"));assertTrue(summary("conv2_runtime").contains("新增公共延迟 16 采样"))
        advanced[18]=99;ConvolutionController.publishRuntime(app,listOf(ConvolutionController.Reading("convolution-test-stream",true,48000,512,ConvolutionRuntimeReader.decode(ConvolutionParametersTest.bulk()),advanced=ConvolutionAdvancedRuntime.decode(advanced))));idle()
        assertTrue(summary("conv2_runtime").contains("等待实际驱动状态"))
    }
    @Test fun secondRoundAllGroupsAndGraphsRenderAtBothWidths() {for(w in listOf(320,411))renderSecondRound(w,1f)}
    @Test fun secondRoundAllGroupsAndGraphsRenderWithLargeFonts() {for(w in listOf(320,411))renderSecondRound(w,1.5f)}
    private fun renderSecondRound(width: Int,font: Float) {
        val a=ConvolutionAdvancedParameters;this.width=width;RuntimeEnvironment.setFontScale(font);ConvolutionController.publishCapabilities(app,16383)
        secondRoundSource();prefs.edit().putBoolean(p.ENABLED,true).putBoolean(a.ANALYSIS,true).commit()
        try {
            launch();runtime(64);runtime(128)
            val source=FloatArray(4410*4).apply{this[0]=.5f;this[1]=.2f;this[2]=.1f;this[3]=.3f;this[4400]=.05f}
            val analysis=me.timschneeberger.rootlessjamesdsp.interop.ConvolutionAdvancedAnalysis.analyze(source,4,source,4,44100)
            ConvolutionController.publishPrepared(app,prepared().copy(sourceChannels=4,advancedAnalysis=analysis));layout()
            a.all.forEach{verifyNumeric(row(it.key))}
            a.switches.forEach{verifyText(row(it).findViewById(android.R.id.title))}
            val sections=listOf("conv2_filter_help" to a.slope(1),a.enabledFilter(2) to a.highGain.key,
                "conv2_matrix_help" to a.CROSS_LINK,a.gain[0].key to a.invert(1),a.gain[2].key to a.invert(3),
                "conv2_fractional_help" to a.delay[3].key,"conv2_shape_help" to a.decay.key,
                "conv2_transition_help" to a.exitFade.key,"conv2_runtime" to "conv2_runtime",
                "conv2_analysis_help" to a.SMOOTH,"conv2_comparison_info" to a.comparison.key)
            sections.forEachIndexed{i,(first,last)->capture(first,last,"conv2-group${i+1}-$width-${(font*100).toInt()}")}
            repeat(4){mode->prefs.edit().putInt(a.GRAPH,mode).commit();layout();capture("conv2_response","conv2_response","conv2-graph$mode-$width-${(font*100).toInt()}")}
            listOf("conv2_filter_help","conv2_matrix_help","conv2_fractional_help","conv2_shape_help","conv2_transition_help","conv2_analysis_help","conv2_runtime").forEach {verifyText(row(it).findViewById(android.R.id.summary))}
        }finally{controller?.pause()?.stop()?.destroy();controller=null;idle()}
    }
    private fun renderGroups(width: Int,font: Float) {
        this.width=width;RuntimeEnvironment.setFontScale(font);prefs.edit().putBoolean(p.ENABLED,true).commit()
        try {
            launch();runtime(64);runtime(128)
            ConvolutionController.publishPrepared(app,prepared());layout()
            assertEquals(1000f,row(p.length.key).findViewById<Slider>(R.id.seekbar).valueTo,0f)
            assertTrue(row(p.length.key).findViewById<Slider>(R.id.seekbar).isEnabled)
            assertTrue(summary("conv1_metadata").contains("驱动核已确认"));assertTrue(summary("conv1_response").contains("不是音乐实测"))
            assertTrue(summary("conv1_metadata").contains("窗口前峰值 -6.00 dBFS；工作核峰值 -3.00 dBFS"))
            assertTrue(summary("conv1_metadata").contains("原版优化后、新时间窗前"))
            p.all.forEach {verifyNumeric(row(it.key))};repeat(6){verifyNumeric(row("conv1_legacy_field_$it"))}
            val groups=listOf("conv1_level_help" to p.output.key,"conv1_time_help" to "conv1_legacy_warning","conv1_window_help" to p.fadeOut.key,
                "conv1_norm_help" to p.NORM_ONLY_ATTENUATION,"conv1_channel_help" to p.SWAP,"conv1_analysis_help" to "conv1_response",
                "conv1_actual" to "conv1_audition")
            groups.forEachIndexed {i,(first,last)->capture(first,last,"group${i+1}-$width-${(font*100).toInt()}")}
            listOf("conv1_level_help","conv1_time_help","conv1_window_help","conv1_norm_help","conv1_channel_help","conv1_analysis_help","conv1_metadata","conv1_actual","conv1_response").forEach {verifyText(row(it).findViewById(android.R.id.summary))}
        }finally{controller?.pause()?.stop()?.destroy();controller=null;idle()}
    }
    private fun verifyText(text: TextView) {
        val layout=requireNotNull(text.layout)
        repeat(layout.lineCount){assertEquals(0,layout.getEllipsisCount(it));assertTrue("${text.text} fits",layout.getLineMax(it)<=text.width-text.compoundPaddingLeft-text.compoundPaddingRight+1f)}
        assertTrue(layout.height<=text.height-text.totalPaddingTop-text.totalPaddingBottom);assertTrue(layout.lineCount>0 && layout.getLineEnd(layout.lineCount-1)>=text.text.length)
    }
    private fun verifyNumeric(row: View) {
        val value=row.findViewById<TextView>(R.id.seekbar_value);val minus=row.findViewById<View>(R.id.bass_decrement);val plus=row.findViewById<View>(R.id.bass_increment);val reset=row.findViewById<TextView>(R.id.bass_reset)
        assertTrue(minus.right<=value.left && value.right<=plus.left && plus.right<=reset.left)
        listOf(row.findViewById<TextView>(android.R.id.title),value,reset).forEach(::verifyText)
    }
    @Suppress("RestrictedApi") private fun capture(first: String,last: String,name: String) {
        val list=fragment().listView;val adapter=list.adapter as PreferenceGroupAdapter;val start=adapter.getPreferenceAdapterPosition(first);val end=adapter.getPreferenceAdapterPosition(last)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(start,0);layout();val root=fragment().requireView() as ViewGroup
        fun bounds(i: Int)=requireNotNull(list.findViewHolderForAdapterPosition(i)).itemView.let {view->Rect(0,0,view.width,view.height).apply {root.offsetDescendantRectToMyCoords(view,this)}}
        val top=bounds(start).top;val bottom=bounds(end).bottom;assertTrue(top>=0 && bottom>top && bottom<=root.height)
        val bitmap=Bitmap.createBitmap(root.width,bottom-top,Bitmap.Config.ARGB_8888)
        try {bitmap.eraseColor(MaterialColors.getColor(root,com.google.android.material.R.attr.colorSurface));root.draw(Canvas(bitmap).apply {translate(0f,-top.toFloat())})
            val folder=File(if(name.startsWith("conv2-"))"build/reports/convolution-advanced"else"build/reports/convolution-ui").apply {mkdirs()};File(folder,"$name.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
        }finally{bitmap.recycle()}
    }
    companion object {
        fun prepared()=ConvolutionController.PreparedIr("Convolver/test.wav",48000,44100,2,4800,4410,1234,
            waveform=listOf(floatArrayOf(0f,.8f,.2f,-.3f,0f),floatArrayOf(0f,.6f,.1f,-.2f,0f)),frequencyHz=floatArrayOf(20f,100f,1000f,10000f,20000f),
            magnitudesDb=listOf(floatArrayOf(-2f,-1f,0f,-3f,-6f),floatArrayOf(-3f,-2f,-1f,-4f,-7f)),peakFrames=listOf(1,1),originalPeakDb=-6.0,workingPeakDb=-3.0)
    }
    class Host: FragmentActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP);super.onCreate(state)
            setContentView(FrameLayout(this).apply {addView(MaterialCardView(context).apply {addView(FragmentContainerView(context).apply {id=CONV_ID},ViewGroup.LayoutParams(-1,-1))},FrameLayout.LayoutParams(-1,-1))})
            if(state==null)supportFragmentManager.beginTransaction().add(CONV_ID,PreferenceGroupFragment.newInstance(ConvolutionParameters.NAMESPACE,R.xml.dsp_convolver_preferences),"convolution").commitNow()
        }
        companion object {const val CONV_ID=0x745144}
    }
}
