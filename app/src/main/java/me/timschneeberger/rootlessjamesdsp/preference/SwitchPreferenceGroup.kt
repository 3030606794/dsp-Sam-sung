package me.timschneeberger.rootlessjamesdsp.preference

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.TypedArray
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.customview.view.AbsSavedState
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceViewHolder
import androidx.preference.children
import me.timschneeberger.rootlessjamesdsp.ui.SpringMaterialSwitch
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.ProfessionalEffectActivity
import me.timschneeberger.rootlessjamesdsp.ui.ParameterHelp
import me.timschneeberger.rootlessjamesdsp.ui.EffectIconStyle
import me.timschneeberger.rootlessjamesdsp.ui.HelpPreferences
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassThemeUi
import me.timschneeberger.rootlessjamesdsp.utils.extensions.animatedValueAs


@SuppressLint("PrivateResource")
class SwitchPreferenceGroup @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : PreferenceGroup(
    context, attrs, androidx.preference.R.attr.preferenceStyle,
    androidx.preference.R.style.Preference_SwitchPreferenceCompat_Material
) {
    private var inlineParameters = false
    private var headerVisible = true
    private var expanded = false
    val isExpanded: Boolean get() = expanded
    val isChecked: Boolean get() = state
    var onExpandedChanged: ((Boolean) -> Unit)? = null
    var onProfessionalClick: (() -> Unit)? = null
        set(value) { field = value; notifyChanged() }
    private var professionalMode = false
    val isProfessionalMode: Boolean get() = professionalMode
    private var professionalAnimating = false
    private var homeVisibleKeys: Set<String>? = null
    private var childVisibilityFilter: ((Preference) -> Boolean)? = null
    private var expandedHighlightEnabled = true
    private var switch: SpringMaterialSwitch? = null
    private var itemView: View? = null
    private var bgAnimation: ValueAnimator? = null
    private var isIconVisible: Boolean = false
    private var state = false
    private val iconPreferences by lazy { EffectIconStyle.preferences(context) }
    private var appliedIconResource = 0
    private val iconStyleListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == EffectIconStyle.KEY) {
            refreshEffectIcon()
            (itemView?.findViewById<View>(R.id.effect_professional) as? ImageView)?.let(::applySettingsIcon)
        }
        if (key == null || key == context.getString(R.string.key_appearance_show_icons)) {
            applyIconVisibility()
        }
    }

    init {
        layoutResource = R.layout.preference_switchgroup
        widgetLayoutResource = R.layout.preference_materialswitch
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        setValueInternal(getPersistedBoolean((defaultValue as? Boolean) ?: false), true)
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any = a.getBoolean(index, false)

    override fun onAttached() {
        super.onAttached()
        iconPreferences.registerOnSharedPreferenceChangeListener(iconStyleListener)
        refreshEffectIcon()
        applyIconVisibility()
    }

    override fun onDetached() {
        iconPreferences.unregisterOnSharedPreferenceChangeListener(iconStyleListener)
        itemView = null
        super.onDetached()
    }

    /** Only presentation resources change; the effect preferences remain untouched. */
    private fun refreshEffectIcon() {
        val base = EffectIconStyle.baseForEffect(key) ?: return
        val resource = EffectIconStyle.resolve(context, base)
        if (appliedIconResource == resource) return
        appliedIconResource = resource
        setIcon(resource)
        if (professionalMode) ProfessionalEffectActivity.fromContext(context)?.bindEffectHeader(this)
    }

    private fun applySettingsIcon(button: ImageView) {
        val resource = EffectIconStyle.resolve(context, R.drawable.ic_effect_settings_24)
        if (button.getTag(R.id.effect_professional) == resource) return
        button.setImageResource(resource)
        button.setTag(R.id.effect_professional, resource)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        refreshEffectIcon()
        super.onBindViewHolder(holder)

        itemView = holder.itemView
        if (professionalMode) ProfessionalEffectActivity.fromContext(context)?.bindEffectHeader(this)
        val homePadding = if (professionalMode) 0 else (6 * context.resources.displayMetrics.density).toInt()
        holder.itemView.setPadding(holder.itemView.paddingLeft, homePadding,
            holder.itemView.paddingRight, homePadding)
        holder.itemView.visibility = if (headerVisible) View.VISIBLE else View.GONE
        holder.itemView.minimumHeight = 0
        holder.itemView.layoutParams.height = if (headerVisible) android.view.ViewGroup.LayoutParams.WRAP_CONTENT else 0
        val glass = GlassThemeUi.isEnabled(context)
        itemView?.background = if (professionalMode || glass) null
            else ContextCompat.getDrawable(context, R.drawable.shape_rounded_highlight)
        if (professionalMode) itemView?.foreground = null
        itemView?.background?.alpha = 0

        bgAnimation?.cancel()
        setChildrenVisibility(isExpanded)
        itemView?.background?.alpha = if (isExpanded && expandedHighlightEnabled) TRANSITION_MAX else TRANSITION_MIN
        applyIconVisibility()

        switch = (holder.findViewById(R.id.switchWidget) as SpringMaterialSwitch).apply {
            // Apply initial state
            setOnCheckedChangeListener(null)
            isSaveEnabled = false
            springEnabled = !professionalMode
            val homeScale = if (professionalMode) 1f else 46f / 52f
            scaleX = homeScale
            scaleY = homeScale
            if (isChecked != state || (!springEnabled && !glassMotionEnabled)) setCheckedImmediately(state)
            isVisible = isSelectable
            contentDescription = title

            setOnCheckedChangeListener { _, isChecked ->
                if (callChangeListener(isChecked)) setValueInternal(isChecked, false)
                else setCheckedImmediately(state)
            }
        }

        holder.findViewById(R.id.effect_professional)?.apply {
            (this as? ImageView)?.let(::applySettingsIcon)
            isVisible = onProfessionalClick != null && !professionalMode
            contentDescription = "$title · 专业调节"
            setOnClickListener {
                val navigate = onProfessionalClick
                if (navigate != null && !professionalAnimating) {
                    professionalAnimating = true
                    animate().cancel()
                    rotation = 0f
                    animate().rotation(180f).setDuration(160L)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .withEndAction {
                            rotation = 0f
                            professionalAnimating = false
                            navigate()
                        }.start()
                }
            }
        }
        holder.findViewById(R.id.effect_help)?.apply {
            contentDescription = "$title · 参数说明"
            setOnClickListener { ParameterHelp.show(context, title?.toString().orEmpty()) }
        }
        (holder.findViewById(android.R.id.title) as? android.widget.TextView)?.let { label ->
            HelpPreferences.bindTitleHelp(label, holder.findViewById(R.id.effect_help)) {
                ParameterHelp.show(context, title?.toString().orEmpty())
            }
        }
        if (glass) GlassThemeUi.decorateSurface(holder.itemView, GlassThemeUi.Role.HEADER)
        (holder.findViewById(android.R.id.title) as? android.widget.TextView)?.let { label ->
            if (label.getTag(R.id.effect_help) != true) {
                label.setTag(R.id.effect_help, true)
                (label.parent as? View)?.addOnLayoutChangeListener { parent, _, _, _, _, _, _, _, _ ->
                    val maximum = (parent.width - parent.paddingLeft - parent.paddingRight -
                        (24 * context.resources.displayMetrics.density).toInt()).coerceAtLeast(1)
                    if (label.maxWidth != maximum) label.maxWidth = maximum
                }
            }
        }

        holder.itemView.apply {
            setOnClickListener {
                if (!inlineParameters) setExpanded(!isExpanded)
            }
        }
    }

    override fun onPrepareAddPreference(preference: Preference): Boolean {
        preference.isVisible = isExpanded && visibleAtHome(preference) && childAllowed(preference)
        return super.onPrepareAddPreference(preference)
    }

    /** Presentation flags only; neither the effect power nor any parameter is persisted. */
    fun setProfessionalMode(value: Boolean) {
        professionalMode = value
        if (value) {
            val host = ProfessionalEffectActivity.fromContext(context)
            showInlineParameters(showHeader = host == null)
            host?.bindEffectHeader(this)
        }
        setChildrenVisibility(isExpanded)
        notifyChanged()
    }

    fun setHomeVisibleKeys(keys: Set<String>?) {
        homeVisibleKeys = keys?.toSet()
        setChildrenVisibility(isExpanded)
        notifyChanged()
    }

    /** An optional owner-controlled UI filter, kept when this group rebinds or expands. */
    fun setChildVisibilityFilter(filter: ((Preference) -> Boolean)?) {
        childVisibilityFilter = filter
        setChildrenVisibility(isExpanded)
    }

    private fun childAllowed(preference: Preference) = childVisibilityFilter?.invoke(preference) != false

    /** Optional home-card appearance; expansion and all audio controls keep their original behavior. */
    fun setExpandedHighlightEnabled(value: Boolean) {
        if (expandedHighlightEnabled == value) return
        expandedHighlightEnabled = value
        bgAnimation?.cancel()
        itemView?.background?.alpha = if (isExpanded && value) TRANSITION_MAX else TRANSITION_MIN
        notifyChanged()
    }

    private fun visibleAtHome(preference: Preference) =
        professionalMode || homeVisibleKeys?.contains(preference.key) != false

    fun setIsIconVisible(value: Boolean) {
        isIconVisible = value
        applyIconVisibility()
    }

    /** Keep the caller's request so enabling category icons restores every icon style. */
    private fun applyIconVisibility() {
        val showCategoryIcons = iconPreferences.getBoolean(
            context.getString(R.string.key_appearance_show_icons),
            context.resources.getBoolean(R.bool.default_appearance_show_icons)
        )
        itemView?.findViewById<View>(R.id.icon_frame)?.isVisible = isIconVisible && showCategoryIcons
    }

    fun setValue(value: Boolean) {
        setValueInternal(value, true)
    }

    /** Refresh an externally edited setting without persisting it or firing a user change. */
    fun syncFromPreferences() {
        val stored = getPersistedBoolean(state)
        if (state == stored) return
        state = stored
        notifyChanged()
    }

    /** Expansion is presentation only: opening or closing a card never changes sound. */
    fun showInlineParameters(showHeader: Boolean = true) {
        inlineParameters = true
        headerVisible = showHeader
        setExpanded(true)
        notifyChanged()
    }

    fun setExpanded(value: Boolean) {
        val target = value || inlineParameters
        if (isExpanded == target) return
        expanded = target
        setChildrenVisibility(target)
        animateHeaderState(target)
        onExpandedChanged?.invoke(target)
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
        if (GlassThemeUi.isEnabled(context)) return
        val background = itemView?.background ?: return
        bgAnimation?.cancel()
        bgAnimation = ValueAnimator.ofInt(background.alpha,
            if (selected && expandedHighlightEnabled) TRANSITION_MAX else TRANSITION_MIN).apply {
            duration = 200
            addUpdateListener { background.alpha = it.animatedValueAs<Int>() ?: 0 }
            start()
        }
    }

    private fun setChildrenVisibility(visible: Boolean) {
        children.forEach { it.isVisible = visible && visibleAtHome(it) && childAllowed(it) }
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
        constructor(source: Parcel) : this(source, null)
        constructor(source: Parcel, loader: ClassLoader?) : super(
            // BaseSavedState(Parcel) restores its parent with the boot loader, which
            // cannot resolve androidx.preference.PreferenceGroup.SavedState.
            source.readParcelable<Parcelable>(ExpandedState::class.java.classLoader ?: loader)
                ?: AbsSavedState.EMPTY_STATE
        ) { expanded = source.readInt() != 0 }
        override fun writeToParcel(dest: Parcel, flags: Int) {
            super.writeToParcel(dest, flags)
            dest.writeInt(if (expanded) 1 else 0)
        }
        companion object {
            @JvmField val CREATOR = object : Parcelable.ClassLoaderCreator<ExpandedState> {
                override fun createFromParcel(source: Parcel) = ExpandedState(source)
                override fun createFromParcel(source: Parcel, loader: ClassLoader?) = ExpandedState(source, loader)
                override fun newArray(size: Int): Array<ExpandedState?> = arrayOfNulls(size)
            }
        }
    }

    companion object {
        private const val TRANSITION_MIN = 0
        private const val TRANSITION_MAX = 255
    }
}
