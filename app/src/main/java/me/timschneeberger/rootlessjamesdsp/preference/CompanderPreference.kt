package me.timschneeberger.rootlessjamesdsp.preference

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import androidx.preference.DialogPreference
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.PreferenceCompanderBinding
import me.timschneeberger.rootlessjamesdsp.view.CompanderSurface
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderParameters

class CompanderPreference : DialogPreference {

    private var companderView: CompanderSurface? = null
    var initialValue: String = ""
    /** Keep the original dialog and storage while using a compact text entry in the professional list. */
    var showInlineGraph: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            companderView = null
            layoutResource = if (value) R.layout.preference_compander else androidx.preference.R.layout.preference
            notifyChanged()
        }

    constructor(
        context: Context, attrs: AttributeSet?,
        defStyleAttr: Int
    ) : this(context, attrs, defStyleAttr, 0)

    constructor(
        context: Context, attrs: AttributeSet?
    ) : this(context, attrs, androidx.preference.R.attr.preferenceStyle)

    constructor(
        context: Context
    ) : this(context, null)

    constructor(
        context: Context, attrs: AttributeSet?, defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        layoutResource = R.layout.preference_compander
        dialogLayoutResource = R.layout.preference_compander_dialog

        this.positiveButtonText = context.getString(android.R.string.ok)
        this.negativeButtonText = context.getString(android.R.string.cancel)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        initialValue = sharedPreferences?.all?.get(key) as? String ?: (defaultValue as? String ?: "")
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getString(index).toString()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        if (!showInlineGraph) {
            companderView = null
            return
        }
        companderView = PreferenceCompanderBinding.bind(holder.itemView).layoutEqualizer
        companderView?.alpha=if(isEnabled)1f else .38f
        setEqualizerViewValues(initialValue)
    }

    fun updateFromPreferences() {
        initialValue = sharedPreferences?.all?.get(key) as? String ?: initialValue
        setEqualizerViewValues(initialValue)
    }

    private fun setEqualizerViewValues(value: String) {
        val curve=CompanderParameters.parseCurve(value) ?: return
        companderView?.setFrequencies(curve.frequencies.toDoubleArray())
        curve.coefficients.forEachIndexed {index,n->companderView?.setBand(index,n)}
    }
}
