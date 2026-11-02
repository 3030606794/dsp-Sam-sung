package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

/** Checks real control callbacks and wire precision, independently of rounded presentation text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqPrecisionUiTest {
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private lateinit var controller: ActivityController<HostActivity>
    private val host get()=controller.get()
    private fun idle()=shadowOf(Looper.getMainLooper()).idle()

    @Before fun prepare() {
        app.getSharedPreferences(Constants.PREF_GEQ,Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(PeqBandLockStore.PREFERENCES,Context.MODE_PRIVATE).edit().clear().commit()
        controller=Robolectric.buildActivity(HostActivity::class.java).setup().visible()
    }
    @After fun close() {controller.pause().stop().destroy();idle()}

    private fun layout() {
        idle()
        val content=host.findViewById<View>(android.R.id.content)
        val density=app.resources.displayMetrics.density
        val width=(411*density).toInt();val height=(820*density).toInt()
        content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        content.layout(0,0,width,height)
        idle()
    }

    private inner class Controls(initial: PeqState) {
        var state=initial
        val changes=mutableListOf<PeqBand>()
        val adapter: PeqBandAdapter
        val row: View
        init {
            PeqBandLockStore(app).setBandLocked(initial.bands.single().id,false)
            lateinit var live: PeqBandAdapter
            live=PeqBandAdapter(onBandChanged={next->
                state=state.copy(bands=listOf(next));changes+=next
                live.submitState(state)
                true
            },onEdit={})
            adapter=live
            adapter.submitState(state)
            val holder=adapter.onCreateViewHolder(host.findViewById(HostActivity.CONTENT),0)
            adapter.onBindViewHolder(holder,0)
            row=holder.itemView
            host.findViewById<FrameLayout>(HostActivity.CONTENT).apply {removeAllViews();addView(row)}
            layout()
        }
        val band get()=state.bands.single()
        fun press(id: Int) {assertTrue(row.findViewById<View>(id).performClick());idle()}
        fun text(id: Int)=row.findViewById<TextView>(id).text.toString()
    }

    @Test fun tinyBandwidthButtonsChangeCanonicalQAndTheDriverPayloadDespiteThreeDigitLabels() {
        val frequency=1000.0
        val original=PeqBand("bw-precise",frequency=frequency,
            q=PeqResponse.qFromBandwidth(1.2344,frequency),qualityDisplay=PeqQualityDisplay.BW)
        val controls=Controls(PeqState(listOf(original),qStep=.25,bwStep=.0001))
        val before=PeqProtocol.payload(controls.state,true)
        val bandwidth=PeqBandAdapter.displayedQuality(original)
        controls.press(R.id.peq_q_plus)
        assertEquals(bandwidth+.0001,PeqBandAdapter.displayedQuality(controls.band),1e-12)
        assertTrue(controls.band.q<original.q)
        val after=PeqProtocol.payload(controls.state,true)
        assertFalse(before.contentEquals(after))
        assertNotEquals(before[PeqProtocol.HEADER_INTS+4],after[PeqProtocol.HEADER_INTS+4])
        assertEquals("1.235",controls.text(R.id.peq_q_value))
        controls.press(R.id.peq_q_minus)
        assertEquals(bandwidth,PeqBandAdapter.displayedQuality(controls.band),1e-12)
        assertEquals(original.q, controls.band.q, 2e-15)
        assertEquals(2,controls.changes.size)
    }

    @Test fun qButtonsUseTheirOwnDecimalStepInsteadOfTheBandwidthSetting() {
        val original=PeqBand("q-precise",q=2.3456)
        val controls=Controls(PeqState(listOf(original),qStep=.0001,bwStep=.25))
        val before=PeqProtocol.payload(controls.state,true)
        controls.press(R.id.peq_q_plus)
        assertEquals(2.3457,controls.band.q,0.0)
        assertFalse(before.contentEquals(PeqProtocol.payload(controls.state,true)))
        controls.press(R.id.peq_q_minus)
        assertEquals(original.q,controls.band.q,0.0)
        assertArrayEquals(before,PeqProtocol.payload(controls.state,true))
    }

    @Test fun roundedCardAndRealInputDialogKeepFourDecimalQWithoutSavingTheLabel() {
        val original=PeqBand("input-precise",q=1.2345)
        PeqBandLockStore(app).setBandLocked(original.id,false)
        val repository=PeqRepository(app)
        assertTrue(repository.save(PeqState(listOf(original),qStep=.0001,bwStep=.0001)))
        val fragment=PeqFragment()
        host.supportFragmentManager.beginTransaction().add(HostActivity.CONTENT,fragment,"peq").commitNow()
        layout()
        val list=fragment.requireView().findViewById<RecyclerView>(R.id.peq_band_list)
        val row=requireNotNull(list.findViewHolderForAdapterPosition(0)).itemView
        assertEquals("1.235",row.findViewById<TextView>(R.id.peq_q_value).text.toString())
        row.findViewById<View>(R.id.peq_q_value).performClick();idle()
        val dialog=ShadowDialog.getLatestDialog() as AlertDialog
        val input=requireNotNull(dialog.findViewById<EditText>(R.id.peq_quality_input))
        assertEquals("1.2345",input.text.toString())
        input.setText("1.2346")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();layout()
        assertFalse(dialog.isShowing)
        assertEquals(1.2346,repository.load().bands.single().q,0.0)
        val words=PeqProtocol.payload(repository.load(),true)
        val bits=(words[PeqProtocol.HEADER_INTS+4].toLong() and 0xffffffffL) or
            (words[PeqProtocol.HEADER_INTS+5].toLong() shl 32)
        assertEquals(1.2346,Double.fromBits(bits),0.0)
    }

    @Test fun qualityEndpointsDisableOnlyTheOutwardButtonAndAllowAnInwardRoundTrip() {
        for(mode in PeqQualityDisplay.entries)for(upper in listOf(false,true)) {
            val seed=PeqBand("endpoint-$mode-$upper",q=2.0,qualityDisplay=mode)
            val bounds=PeqBandAdapter.qualityBounds(seed)
            val endpoint=PeqBandAdapter.withQuality(seed,if(upper)bounds.endInclusive else bounds.start)
            val controls=Controls(PeqState(listOf(endpoint),qStep=.0001,bwStep=.0001))
            val outward=if(upper)R.id.peq_q_plus else R.id.peq_q_minus
            val inward=if(upper)R.id.peq_q_minus else R.id.peq_q_plus
            assertFalse("$mode endpoint outward",controls.row.findViewById<View>(outward).isEnabled)
            assertTrue("$mode endpoint inward",controls.row.findViewById<View>(inward).isEnabled)
            controls.press(inward)
            assertNotEquals(endpoint.q,controls.band.q)
            assertTrue(controls.band.q in PeqModel.MIN_Q..PeqModel.MAX_Q)
            controls.press(outward)
            assertEquals("$mode endpoint round trip",endpoint.q,controls.band.q,1e-8)
            assertFalse(controls.row.findViewById<View>(outward).isEnabled)
        }
    }

    private fun dragTo(slider: Slider, position: Float) {
        assertTrue(slider.isEnabled)
        assertTrue("Slider must have a real laid-out track",slider.trackWidth>0)
        val start=(slider.value-slider.valueFrom)/(slider.valueTo-slider.valueFrom)
        val now=SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to start,MotionEvent.ACTION_MOVE to position,
            MotionEvent.ACTION_UP to position).forEachIndexed {index,(action,fraction)->
            val event=MotionEvent.obtain(now,now+index*20L,action,
                slider.trackSidePadding+fraction*slider.trackWidth,slider.height/2f,0)
            try {slider.dispatchTouchEvent(event)} finally {event.recycle()}
        }
        idle()
    }

    @Test fun draggingFrequencyAcrossTheWholeGraphPreservesFixedBandwidthAndThumbPosition() {
        val original=PeqBand("fixed-bandwidth",frequency=1.0,
            q=PeqResponse.qFromBandwidth(.014,1.0),qualityDisplay=PeqQualityDisplay.BW)
        val controls=Controls(PeqState(listOf(original),graphMinFrequency=1.0,graphMaxFrequency=20000.0))
        val quality=controls.row.findViewById<Slider>(R.id.peq_q_slider)
        val frequency=controls.row.findViewById<Slider>(R.id.peq_frequency_slider)
        val width=PeqBandAdapter.displayedQuality(controls.band)
        val thumb=quality.value
        val label=controls.text(R.id.peq_q_value)
        for(position in listOf(1f,0f)) {
            dragTo(frequency,position)
            assertEquals(if(position==1f)24000.0 else 1.0,controls.band.frequency,0.0)
            assertEquals(original.q,controls.band.q,0.0)
            assertEquals(width,PeqBandAdapter.displayedQuality(controls.band),0.0)
            assertEquals(thumb,quality.value,0f)
            assertEquals(label,controls.text(R.id.peq_q_value))
        }
        assertTrue(controls.changes.size>=2)
    }

    @Test fun graphSettingsCommitToCurveCodecAndFrequencyEndpointsButCancelDoesNotWriteProfile() {
        val original=PeqBand("graph-range",frequency=100.0,q=2.0)
        PeqBandLockStore(app).setBandLocked(original.id,false)
        val repository=PeqRepository(app)
        assertTrue(repository.save(PeqState(listOf(original),graphMinFrequency=1.0,graphMaxFrequency=20000.0)))
        val fragment=PeqFragment()
        host.supportFragmentManager.beginTransaction().add(HostActivity.CONTENT,fragment,"peq").commitNow()
        layout()
        val root=fragment.requireView()
        val curve=root.findViewById<PeqResponseView>(R.id.peq_curve)
        fun settings(): AlertDialog {
            assertTrue(root.findViewById<View>(R.id.peq_settings).performClick());idle()
            return ShadowDialog.getLatestDialog() as AlertDialog
        }
        val dialog=settings()
        requireNotNull(dialog.findViewById<EditText>(R.id.peq_graph_min)).setText("1")
        requireNotNull(dialog.findViewById<EditText>(R.id.peq_graph_max)).setText("200")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();layout()
        assertFalse(dialog.isShowing)
        assertEquals(1.0,requireNotNull(curve.requestedState).graphMinFrequency,0.0)
        assertEquals(200.0,requireNotNull(curve.requestedState).graphMaxFrequency,0.0)
        assertEquals(fragment.editorState,repository.load())
        val list=root.findViewById<RecyclerView>(R.id.peq_band_list)
        fun frequencySlider(): Slider {
            layout()
            return requireNotNull(list.findViewHolderForAdapterPosition(0)).itemView
                .findViewById(R.id.peq_frequency_slider)
        }
        assertEquals(PeqBandAdapter.logarithmicPosition(100.0,1.0,24000.0),frequencySlider().value,0f)
        dragTo(frequencySlider(),1f)
        assertEquals(24000.0,fragment.editorState.bands.single().frequency,0.0)
        dragTo(frequencySlider(),0f)
        assertEquals(1.0,fragment.editorState.bands.single().frequency,0.0)
        val saved=repository.load()
        val canceled=settings()
        requireNotNull(canceled.findViewById<EditText>(R.id.peq_graph_min)).setText("10")
        requireNotNull(canceled.findViewById<EditText>(R.id.peq_graph_max)).setText("100")
        canceled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();layout()
        assertEquals(saved,repository.load())
        assertEquals(1.0,requireNotNull(curve.requestedState).graphMinFrequency,0.0)
        assertEquals(200.0,requireNotNull(curve.requestedState).graphMaxFrequency,0.0)
    }

    class HostActivity: FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply {id=CONTENT})
        }
        companion object {const val CONTENT=0x6f32}
    }
}
