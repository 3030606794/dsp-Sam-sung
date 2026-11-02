package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueObserver
import me.timschneeberger.rootlessjamesdsp.preference.PreferenceValueSync
import me.timschneeberger.rootlessjamesdsp.ui.SliderPreferenceUi
import me.timschneeberger.rootlessjamesdsp.ui.SliderAppearance
import me.timschneeberger.rootlessjamesdsp.ui.glass.setGlassLabelFormatter
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.utils.SliderDoubleTapReset
import kotlin.math.roundToInt

/** The slider is an ordinal: only the three original String-backed ranges can be saved. */
class ToneRangePreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : Preference(context, attrs), PreferenceValueSync {
    var editGuard: () -> Boolean = { true }
    var range: Int = 100
        private set
    private var trackingTouch = false
    private var previewRange: Int? = null
    private var touchValues: Map<String, *>? = null
    private var renderBoundValue: (() -> Unit)? = null
    private val valueObserver = PreferenceValueObserver(this, ::syncFromPreferences)

    override fun syncFromPreferences() {
        if (!isPersistent) return
        val next = sharedPreferences?.let(ToneController::range) ?: return
        if (range != next) setRangeSilently(next, false)
    }

    override fun onAttached() { super.onAttached(); valueObserver.attach() }
    override fun onDetached() { valueObserver.detach(); releaseBoundView(); super.onDetached() }

    override fun finishInteraction() { trackingTouch=false; previewRange=null; touchValues=null; syncFromPreferences(); renderBoundValue?.invoke() }
    override fun releaseBoundView() { trackingTouch=false; previewRange=null; touchValues=null; renderBoundValue=null }

    init {
        layoutResource = R.layout.preference_fusion_tone_range
        isSelectable = false
        isIconSpaceReserved = false
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        range = (sharedPreferences?.all?.get(key) as? String)?.toIntOrNull()?.takeIf { it in ToneController.RANGES } ?: 100
    }

    fun selectRange(value: Int): Boolean {
        if (!isEnabled || !editGuard() || value !in ToneController.RANGES || value == range) return false
        if (!callChangeListener(value)) return false
        setRangeSilently(value)
        return true
    }

    fun setRangeSilently(value: Int, save: Boolean = true) {
        require(value in ToneController.RANGES)
        val changed = range != value
        range = value
        if(save) persistString(value.toString())
        if (changed) {
            if (renderBoundValue != null) renderBoundValue?.invoke() else notifyChanged()
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        syncFromPreferences()
        super.onBindViewHolder(holder)
        SliderPreferenceUi.bind(holder, title, summary, "可选范围：±20、±40、±100；默认：±100；步长：相邻合法档位")
        val slider=holder.findViewById(R.id.fusion_tone_range_slider) as Slider
        val value=holder.findViewById(R.id.fusion_tone_range_value) as MaterialButton
        slider.setTag(R.id.seekbar,this)
        slider.isSaveEnabled=false
        holder.itemView.isSaveFromParentEnabled=false
        SliderAppearance.apply(slider)
        fun render() {
            if(slider.getTag(R.id.seekbar)!==this)return
            val ordinal=ToneController.RANGES.indexOf(range).coerceAtLeast(0).toFloat()
            if(!trackingTouch && slider.value!=ordinal)slider.value=ordinal
            val shown=previewRange.takeIf {trackingTouch} ?: range
            value.text="±$shown"
            value.contentDescription="${title}：正负$shown，仅三档可选${if(trackingTouch)"，松手后应用" else ""}"
            value.isEnabled=isEnabled
        }
        renderBoundValue=::render
        slider.clearOnChangeListeners();slider.clearOnSliderTouchListeners()
        slider.valueFrom=0f;slider.valueTo=2f;slider.stepSize=1f
        slider.value=ToneController.RANGES.indexOf(range).coerceAtLeast(0).toFloat()
        slider.setGlassLabelFormatter { "±${ToneController.RANGES[it.roundToInt().coerceIn(0,2)]}" }
        slider.isEnabled=isEnabled;slider.contentDescription=title
        slider.addOnChangeListener {_,ordinal,fromUser->
            if(fromUser) {
                val selected=ToneController.RANGES[ordinal.roundToInt().coerceIn(0,2)]
                // Preview ordinal movement; only the final released range may truncate original values.
                if(trackingTouch) previewRange=selected
                else if(selected!=range && !selectRange(selected)) {
                    slider.value=ToneController.RANGES.indexOf(range).coerceAtLeast(0).toFloat()
                }
                render()
            }
        }
        slider.addOnSliderTouchListener(object:Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider:Slider){trackingTouch=true;previewRange=range;touchValues=sharedPreferences?.all?.toMap()}
            override fun onStopTrackingTouch(slider:Slider){
                val next=previewRange
                val unchanged=trackingTouch && touchValues!=null && touchValues==sharedPreferences?.all
                trackingTouch=false;previewRange=null;touchValues=null
                if(unchanged && next!=null)selectRange(next)
                finishInteraction()
            }
        })
        SliderDoubleTapReset.install(slider,2f,onCancel=::finishInteraction,resetLabel="恢复 ±100",thumbOnly=true) {
            finishInteraction();selectRange(100);render()
        }
        value.setOnClickListener {
            if(isEnabled && editGuard())GlassAlertDialogBuilder(context).setTitle(title)
                .setSingleChoiceItems(arrayOf("±20","±40","±100"),ToneController.RANGES.indexOf(range)) {dialog,index->
                    selectRange(ToneController.RANGES[index]);render();dialog.dismiss()
                }.setNegativeButton(android.R.string.cancel,null).show()
        }
        render()
    }
}
