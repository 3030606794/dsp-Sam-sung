package me.timschneeberger.rootlessjamesdsp.preference

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.TypedArray
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceViewHolder
import androidx.preference.children
import com.google.android.material.materialswitch.MaterialSwitch
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.extensions.animatedValueAs


@SuppressLint("PrivateResource")
class SwitchPreferenceGroup @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : PreferenceGroup(
    context, attrs, androidx.preference.R.attr.preferenceStyle,
    androidx.preference.R.style.Preference_SwitchPreferenceCompat_Material
) {
    private var expanded = false
    val isExpanded: Boolean get() = expanded
    val isChecked: Boolean get() = state
    private var switch: MaterialSwitch? = null
    private var itemView: View? = null
    private var bgAnimation: ValueAnimator? = null
    private var isIconVisible: Boolean = false
    private var state = false

    init {
        layoutResource = R.layout.preference_switchgroup
        widgetLayoutResource = R.layout.preference_materialswitch
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        setValueInternal(getPersistedBoolean((defaultValue as? Boolean) ?: false), true)
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any = a.getBoolean(index, false)

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        itemView = holder.itemView
        itemView?.background = ContextCompat.getDrawable(context, R.drawable.shape_rounded_highlight)
        itemView?.background?.alpha = 0

        bgAnimation?.cancel()
        setChildrenVisibility(isExpanded)
        itemView?.background?.alpha = if (isExpanded) TRANSITION_MAX else TRANSITION_MIN
        setIsIconVisible(isIconVisible)

        switch = (holder.findViewById(R.id.switchWidget) as MaterialSwitch).apply {
            // Apply initial state
            setOnCheckedChangeListener(null)
            isSaveEnabled = false
            isChecked = state
            isVisible = isSelectable
            contentDescription = title

            setOnCheckedChangeListener { _, isChecked ->
                if (callChangeListener(isChecked)) setValueInternal(isChecked, false)
                else this.isChecked = state
            }
        }

        holder.itemView.apply {
            setOnClickListener {
                setExpanded(!isExpanded)
            }
        }
    }

    override fun onPrepareAddPreference(preference: Preference): Boolean {
        preference.isVisible = isExpanded
        return super.onPrepareAddPreference(preference)
    }

    fun setIsIconVisible(value: Boolean) {
        isIconVisible = value
        itemView?.findViewById<View>(R.id.icon_frame)?.isVisible = value
    }

    fun setValue(value: Boolean) {
        setValueInternal(value, true)
    }

    /** Expansion is presentation only: opening or closing a card never changes sound. */
    fun setExpanded(value: Boolean) {
        if (isExpanded == value) return
        expanded = value
        setChildrenVisibility(value)
        animateHeaderState(value)
    }

    private fun setValueInternal(value: Boolean, notifyChanged: Boolean) {
        if (state != value) {
            state = value
            persistBoolean(state)
            if (notifyChanged) {
                notifyChanged()
            }
        }
    }

    private fun animateHeaderState(selected: Boolean) {
        val background = itemView?.background ?: return
        bgAnimation?.cancel()
        bgAnimation = ValueAnimator.ofInt(background.alpha,
            if (selected) TRANSITION_MAX else TRANSITION_MIN).apply {
            duration = 200
            addUpdateListener { background.alpha = it.animatedValueAs<Int>() ?: 0 }
            start()
        }
    }

    private fun setChildrenVisibility(visible: Boolean) {
        children.forEach { it.isVisible = visible }
    }

    override fun onSaveInstanceState(): Parcelable = ExpandedState(super.onSaveInstanceState()).also {
        it.expanded = isExpanded
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is ExpandedState) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.superState)
        setExpanded(state.expanded)
    }

    class ExpandedState : BaseSavedState {
        var expanded = false
        constructor(superState: Parcelable?) : super(superState)
        constructor(source: Parcel) : super(source) { expanded = source.readInt() != 0 }
        override fun writeToParcel(dest: Parcel, flags: Int) {
            super.writeToParcel(dest, flags)
            dest.writeInt(if (expanded) 1 else 0)
        }
        companion object {
            @JvmField val CREATOR = object : Parcelable.Creator<ExpandedState> {
                override fun createFromParcel(source: Parcel) = ExpandedState(source)
                override fun newArray(size: Int): Array<ExpandedState?> = arrayOfNulls(size)
            }
        }
    }

    companion object {
        private const val TRANSITION_MIN = 0
        private const val TRANSITION_MAX = 255
    }
}
