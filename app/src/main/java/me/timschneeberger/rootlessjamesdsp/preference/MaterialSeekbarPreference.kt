package me.timschneeberger.rootlessjamesdsp.preference

import me.timschneeberger.rootlessjamesdsp.ui.glass.setGlassLabelFormatter
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.EditText
import android.text.InputType
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.core.widget.TextViewCompat
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder as MaterialAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.ui.SliderPreferenceUi
import me.timschneeberger.rootlessjamesdsp.ui.SliderAppearance
import me.timschneeberger.rootlessjamesdsp.utils.SliderDoubleTapReset
import timber.log.Timber
import java.math.BigDecimal
import java.math.MathContext
import java.util.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.pow

class MaterialSeekbarPreference : Preference, PreferenceValueSync {
    var mSeekBarValue/* synthetic access */ = 0f
    var mMin/* synthetic access */ = 0f
    private var mMax = 0f
    private var mSeekBarIncrement = 0f
    var mTrackingTouch/* synthetic access */ = false
    lateinit var mSeekBar: /* synthetic access */Slider
    private var mSeekBarValueTextView: TextView? = null

    var mUnit: String = ""
    var mPrecision: Int = 2
    var mLabelMinWidth: Int = 0

    // Whether the SeekBar should respond to the left/right keys
    var mAdjustable/* synthetic access */ = false

    // Whether to show the SeekBar value TextView next to the bar
    private var mShowSeekBarValue = false

    // Whether the SeekBarPreference should continuously save the Seekbar value while it is being
    // dragged.
    var mUpdatesContinuously/* synthetic access */ = false

    var valueLabelOverride: ((Float) -> String)? = null
    /** Optional declaration-based rounding; existing controls retain their current behavior. */
    var valueNormalizer: ((Float) -> Float)? = null
    /** DSP declaration default for this thumb only; null preserves the existing reset behavior. */
    var thumbResetValueOverride: Float? = null
    /** Explicit presentation opt-in for the professional DSP list; audio/value handling is shared. */
    var useDspCompactLayout: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            layoutResource = if (value) R.layout.preference_liveprog_compact_slider
                else R.layout.preference_materialslider
            notifyChanged()
        }
    private var applyingInitialValue = false
    private var defaultSliderValue = 0f
    private val valueObserver = PreferenceValueObserver(this, ::syncFromPreferences)

    override fun syncFromPreferences() {
        if (!isPersistent || key == null || mTrackingTouch) return
        val dataStore = preferenceDataStore
        val stored = if (dataStore != null) {
            runCatching { dataStore.getFloat(key, mSeekBarValue) }.getOrNull()
        } else sharedPreferences?.let {
            runCatching { it.getFloat(key, defaultSliderValue) }.getOrNull()
        }
        refreshDisplayedValue(stored?.takeIf { it.isFinite() } ?: return)
    }

    /** Another editor's committed value; never persists it or fires a user-change callback. */
    fun refreshDisplayedValue(value: Float) {
        if (mTrackingTouch || !value.isFinite()) return
        val stored = value.coerceIn(mMin, mMax)
        val next = validateValue(stored.coerceIn(mMin, mMax))
        if (mSeekBarValue == next) return
        mSeekBarValue = next
        if (::mSeekBar.isInitialized && mSeekBar.getTag(R.id.seekbar) === this) {
            if (mSeekBar.value != next) mSeekBar.value = next
            updateLabelValue(next)
        }
    }

    override fun onAttached() {
        super.onAttached()
        valueObserver.attach()
    }

    override fun finishInteraction() {
        mTrackingTouch = false
        syncFromPreferences()
        if (::mSeekBar.isInitialized && mSeekBar.getTag(R.id.seekbar) === this) {
            val next = validateValue(mSeekBarValue)
            if (mSeekBar.value != next) mSeekBar.value = next
            updateLabelValue(next)
        }
    }

    override fun onDetached() {
        valueObserver.detach()
        mTrackingTouch = false
        mSeekBarValueTextView = null
        super.onDetached()
    }

    override fun releaseBoundView() {
        mTrackingTouch = false
        mSeekBarValueTextView = null
    }

    /**
     * Listener reacting to the [SeekBar] changing value by the user
     */
    private val mSeekBarChangeListener =
        Slider.OnChangeListener { slider, value, fromUser ->
            if (fromUser && (mUpdatesContinuously || !mTrackingTouch)) {
                syncValueInternal(slider)
            } else {
                // We always want to update the text while the seekbar is being dragged
                updateLabelValue(value)
            }
        }
    private val mSeekBarTouchListener = object :  Slider.OnSliderTouchListener {
        override fun onStartTrackingTouch(seekBar: Slider) {
            mTrackingTouch = true
        }

        override fun onStopTrackingTouch(seekBar: Slider) {
            mTrackingTouch = false
            if (seekBar.value != mSeekBarValue) {
                syncValueInternal(seekBar)
            }
            syncFromPreferences()
        }
    }

    /**
     * Listener reacting to the user pressing DPAD left/right keys if `adjustable` attribute is set to true; it transfers the key presses to the [SeekBar]
     * to be handled accordingly.
     */
    private val mSeekBarKeyListener =
        View.OnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@OnKeyListener false
            }
            if (!mAdjustable && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            ) {
                // Right or left keys are pressed when in non-adjustable mode; Skip the keys.
                return@OnKeyListener false
            }

            // We don't want to propagate the click keys down to the SeekBar view since it will
            // create the ripple effect for the thumb.
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                return@OnKeyListener false
            }
            mSeekBar.onKeyDown(keyCode, event)
        }

    constructor(
        context: Context, attrs: AttributeSet?, defStyleAttr: Int,
        defStyleRes: Int,
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        layoutResource = R.layout.preference_materialslider

        val a = context.obtainStyledAttributes(
            attrs, R.styleable.MaterialSeekbarPreference, defStyleAttr, defStyleRes
        )

        // The ordering of these two statements are important. If we want to set max first, we need
        // to perform the same steps by changing min/max to max/min as following:
        // mMax = a.getInt(...) and setMin(...).
        mMin = a.getFloat(R.styleable.MaterialSeekbarPreference_minValue, 0f)
        setMax(a.getFloat(R.styleable.MaterialSeekbarPreference_maxValue, 100f))
        setSeekBarIncrement(a.getFloat(R.styleable.MaterialSeekbarPreference_seekBarIncrement, 0f))
        mShowSeekBarValue = a.getBoolean(R.styleable.MaterialSeekbarPreference_showSeekBarValue, false)
        mUpdatesContinuously = a.getBoolean(
            R.styleable.MaterialSeekbarPreference_updatesContinuously,
            false
        )

        mLabelMinWidth = a.getDimensionPixelSize(R.styleable.MaterialSeekbarPreference_labelMinWidth, 0)
        mUnit = a.getString(R.styleable.MaterialSeekbarPreference_unit) ?: ""
        mPrecision = a.getInt(R.styleable.MaterialSeekbarPreference_precision, 2)

        a.recycle()
    }

    constructor(
        context: Context, attrs: AttributeSet?,
        defStyleAttr: Int,
    ) : this(context, attrs, defStyleAttr, 0)

    constructor(
        context: Context, attrs: AttributeSet?,
    ) : this(context, attrs, R.attr.seekBarStyle)

    constructor(
        context: Context,
    ) : this(context, null)

    private fun validateValue(value: Float): Float {
        if (mSeekBarIncrement > 0 && !valueLandsOnTick(value)) {
            val newValue = mMin + mSeekBarIncrement * (((value - mMin) / mSeekBarIncrement).roundToInt())
            Timber.w("setValueInternal: value corrected $value to $newValue")
            return newValue
        }
        return value
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        syncFromPreferences()
        super.onBindViewHolder(holder)
        mSeekBar = holder.findViewById(R.id.seekbar) as Slider
        mSeekBar.setTag(R.id.seekbar, this)
        mSeekBar.isSaveEnabled = false
        holder.itemView.isSaveFromParentEnabled = false
        SliderAppearance.apply(mSeekBar)
        mSeekBarValueTextView = holder.findViewById(R.id.seekbar_value) as TextView
        holder.itemView.setOnKeyListener(mSeekBarKeyListener)

        if(mLabelMinWidth > 0) {
            mSeekBarValueTextView!!.minWidth = mLabelMinWidth
        }

        mSeekBarValueTextView!!.visibility = View.VISIBLE

        mSeekBar.clearOnChangeListeners()
        mSeekBar.clearOnSliderTouchListeners()
        // A recycled slider must not publish its previous row's value while it is rebound.
        mSeekBar.stepSize = 0f
        mSeekBar.valueFrom = mMin
        mSeekBar.valueTo = mMax
        // Ignore: If the increment is not zero, use that. Otherwise, use the default mKeyProgressIncrement
        // in AbsSeekBar when it's zero. This default increment value is set by AbsSeekBar
        // after calling setMax. That's why it's important to call setKeyProgressIncrement after
        // calling setMax() since setMax() can change the increment value.
        mSeekBar.stepSize = mSeekBarIncrement

        mSeekBar.value = validateValue(mSeekBarValue)
        mSeekBar.setGlassLabelFormatter { formatLabelValue(it) }
        updateLabelValue(mSeekBarValue)
        mSeekBar.isEnabled = isEnabled
        mSeekBar.addOnChangeListener(mSeekBarChangeListener)
        mSeekBar.addOnSliderTouchListener(mSeekBarTouchListener)

        val step = if (mSeekBarIncrement > 0) mSeekBarIncrement else 10f.pow(-mPrecision)
        holder.findViewById(R.id.slider_decrease)?.apply {
            isEnabled = this@MaterialSeekbarPreference.isEnabled
            contentDescription = "${title}减少 $step$mUnit"
            setOnClickListener { requestUserValue(validateValue((mSeekBarValue - step).coerceIn(mMin, mMax))) }
        }
        holder.findViewById(R.id.slider_increase)?.apply {
            isEnabled = this@MaterialSeekbarPreference.isEnabled
            contentDescription = "${title}增加 $step$mUnit"
            setOnClickListener { requestUserValue(validateValue((mSeekBarValue + step).coerceIn(mMin, mMax))) }
        }
        mSeekBarValueTextView!!.isEnabled = isEnabled
        mSeekBarValueTextView!!.setOnClickListener { showValueInput() }
        setOnPreferenceClickListener { showValueInput(); true }
        val explicitThumbResetValue = thumbResetValueOverride?.takeIf { it.isFinite() }?.let { declared ->
            val bounded = declared.coerceIn(mMin, mMax)
            val normalized = valueNormalizer?.invoke(bounded)?.takeIf { it.isFinite() } ?: bounded
            validateValue(normalized.coerceIn(mMin, mMax)).coerceIn(mMin, mMax)
        }
        SliderDoubleTapReset.install(mSeekBar,
            explicitThumbResetValue ?: validateValue(defaultSliderValue).coerceIn(mMin, mMax),
            onCancel = ::finishInteraction,
            resetLabel = "恢复默认值", thumbOnly = true) {
            requestUserValue(explicitThumbResetValue ?: validateValue(defaultSliderValue.coerceIn(mMin, mMax)))
            mSeekBar.value = validateValue(mSeekBarValue)
        }
        SliderPreferenceUi.bind(holder, title, summary,
            "范围：$mMin～$mMax $mUnit；默认：${explicitThumbResetValue ?: defaultSliderValue} $mUnit；步长：$step $mUnit")
        if (useDspCompactLayout) bindDspCompactAppearance(holder)
    }

    private fun bindDspCompactAppearance(holder: PreferenceViewHolder) {
        (holder.findViewById(android.R.id.title) as? TextView)?.apply {
            TextViewCompat.setAutoSizeTextTypeWithDefaults(this, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isSingleLine = false
            maxLines = Int.MAX_VALUE
            ellipsize = null
        }
        (holder.findViewById(android.R.id.summary) as? TextView)?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = this@MaterialSeekbarPreference.summary
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        mSeekBarValueTextView?.apply {
            // A long imported value wraps in the same fixed-width field instead of shrinking.
            TextViewCompat.setAutoSizeTextTypeWithDefaults(this, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isSingleLine = false
            maxLines = Int.MAX_VALUE
            ellipsize = null
        }
    }

    private fun requestUserValue(candidate: Float): Boolean {
        if (!isEnabled || !candidate.isFinite() || candidate !in mMin..mMax ||
            (mSeekBarIncrement > 0 && !valueLandsOnTick(candidate))) return false
        if (candidate != mSeekBarValue && !callChangeListener(candidate)) {
            if (::mSeekBar.isInitialized && mSeekBar.getTag(R.id.seekbar) === this)
                mSeekBar.value = validateValue(mSeekBarValue)
            return false
        }
        setValueInternal(candidate, false)
        if (::mSeekBar.isInitialized && mSeekBar.getTag(R.id.seekbar) === this && mSeekBar.value != mSeekBarValue)
            mSeekBar.value = validateValue(mSeekBarValue)
        updateLabelValue(mSeekBarValue)
        return true
    }

    private fun showValueInput() {
        if (!isEnabled) return
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (useDspCompactLayout) setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setSingleLine(); setText(mSeekBarValue.toString()); selectAll()
        }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(title).setView(input)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entered = input.text.toString().trim().toFloatOrNull()
                if (entered == null || !requestUserValue(entered))
                    input.error = "请输入 $mMin～$mMax $mUnit" + if (mSeekBarIncrement > 0) "，步长 $mSeekBarIncrement" else ""
                else dialog.dismiss()
            }
        }
        dialog.show()
    }
    override fun onSetInitialValue(defaultValue: Any?) {
        defaultSliderValue = (defaultValue as? Number)?.toFloat() ?: defaultSliderValue
        if(key in listOf("compander_timeconstant","compander_granularity") && sharedPreferences?.contains(key)==true) {
            val original=sharedPreferences?.all?.get(key) as? Float ?: (defaultValue as? Float ?: 0f)
            val persistent=isPersistent;isPersistent=false
            setValue(original.takeIf {it.isFinite()} ?: (defaultValue as? Float ?: 0f))
            isPersistent=persistent
            return
        }
        applyingInitialValue = true
        try {
            setValue(getPersistedFloat(defaultSliderValue))
        } finally {
            applyingInitialValue = false
        }
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return validateValue(a.getFloat(index, 0f))
    }

    /**
     * Gets the lower bound set on the [SeekBar].
     *
     * @return The lower bound set
     */
    fun getMin(): Float {
        return mMin
    }

    fun setRange(minimum: Float, maximum: Float) {
        require(minimum.isFinite() && maximum.isFinite() && minimum < maximum)
        if (mMin == minimum && mMax == maximum) return
        mMin = minimum
        mMax = maximum
        notifyChanged()
    }

    /**
     * Sets the lower bound on the [SeekBar].
     *
     * @param _min The lower bound to set
     */
    fun setMin(_min: Float) {
        var min = _min
        if (min > mMax) {
            min = mMax
        }
        if (min != mMin) {
            mMin = min
            notifyChanged()
        }
    }

    /**
     * Returns the amount of increment change via each arrow key click. This value is derived from
     * user's specified increment value if it's not zero. Otherwise, the default value is picked
     * from the default mKeyProgressIncrement value in [android.widget.AbsSeekBar].
     *
     * @return The amount of increment on the [SeekBar] performed after each user's arrow
     * key press
     */
    fun getSeekBarIncrement(): Float {
        return mSeekBarIncrement
    }

    /**
     * Sets the increment amount on the [SeekBar] for each arrow key press.
     *
     * @param seekBarIncrement The amount to increment or decrement when the user presses an
     * arrow key.
     */
    fun setSeekBarIncrement(seekBarIncrement: Float) {
        if (seekBarIncrement != mSeekBarIncrement) {
            mSeekBarIncrement = min(mMax - mMin, abs(seekBarIncrement))
            notifyChanged()
        }
    }

    /**
     * Gets the upper bound set on the [SeekBar].
     *
     * @return The upper bound set
     */
    fun getMax(): Float {
        return mMax
    }

    /**
     * Sets the upper bound on the [SeekBar].
     *
     * @param _max The upper bound to set
     */
    fun setMax(_max: Float) {
        var max = _max
        if (max < mMin) {
            max = mMin
        }
        if (max != mMax) {
            mMax = max
            notifyChanged()
        }
    }

    /**
     * Gets whether the [SeekBar] should respond to the left/right keys.
     *
     * @return Whether the [SeekBar] should respond to the left/right keys
     */
    fun isAdjustable(): Boolean {
        return mAdjustable
    }

    /**
     * Sets whether the [SeekBar] should respond to the left/right keys.
     *
     * @param adjustable Whether the [SeekBar] should respond to the left/right keys
     */
    fun setAdjustable(adjustable: Boolean) {
        mAdjustable = adjustable
    }

    /**
     * Gets whether the [SeekBarPreference] should continuously save the [SeekBar] value
     * while it is being dragged. Note that when the value is true,
     * [Preference.OnPreferenceChangeListener] will be called continuously as well.
     *
     * @return Whether the [SeekBarPreference] should continuously save the [SeekBar]
     * value while it is being dragged
     * @see .setUpdatesContinuously
     */
    fun getUpdatesContinuously(): Boolean {
        return mUpdatesContinuously
    }

    /**
     * Sets whether the [SeekBarPreference] should continuously save the [SeekBar] value
     * while it is being dragged.
     *
     * @param updatesContinuously Whether the [SeekBarPreference] should continuously save
     * the [SeekBar] value while it is being dragged
     * @see .getUpdatesContinuously
     */
    fun setUpdatesContinuously(updatesContinuously: Boolean) {
        mUpdatesContinuously = updatesContinuously
    }

    /**
     * Gets whether the current [SeekBar] value is displayed to the user.
     *
     * @return Whether the current [SeekBar] value is displayed to the user
     * @see .setShowSeekBarValue
     */
    fun getShowSeekBarValue(): Boolean {
        return mShowSeekBarValue
    }

    /**
     * Sets whether the current [SeekBar] value is displayed to the user.
     *
     * @param showSeekBarValue Whether the current [SeekBar] value is displayed to the user
     * @see .getShowSeekBarValue
     */
    fun setShowSeekBarValue(showSeekBarValue: Boolean) {
        mShowSeekBarValue = showSeekBarValue
        notifyChanged()
    }

    private fun setValueInternal(_seekBarValue: Float, notifyChanged: Boolean) {
        if (!_seekBarValue.isFinite()) return
        var seekBarValue = _seekBarValue
        if (seekBarValue < mMin) {
            seekBarValue = mMin
        }
        if (seekBarValue > mMax) {
            seekBarValue = mMax
        }

        seekBarValue = validateValue(seekBarValue)
        if (!applyingInitialValue) seekBarValue = valueNormalizer?.invoke(seekBarValue) ?: seekBarValue
        if (mSeekBarIncrement > 0 && !valueLandsOnTick(seekBarValue)) {
            seekBarValue = mMin + mSeekBarIncrement * (((seekBarValue - mMin) / mSeekBarIncrement).roundToInt())
            Timber.w("setValueInternal: value corrected $_seekBarValue to $seekBarValue")
        }

        if (seekBarValue != mSeekBarValue) {
            mSeekBarValue = seekBarValue
            updateLabelValue(mSeekBarValue)
            persistFloat(seekBarValue)
            if (notifyChanged && !mTrackingTouch) {
                notifyChanged()
            }
        }
    }

    /**
     * Gets the current progress of the [SeekBar].
     *
     * @return The current progress of the [SeekBar]
     */
    fun getValue(): Float {
        return mSeekBarValue
    }

    /**
     * Sets the current progress of the [SeekBar].
     *
     * @param seekBarValue The current progress of the [SeekBar]
     */
    fun setValue(seekBarValue: Float) {
        setValueInternal(seekBarValue, true)
    }

    /**
     * Persist the [SeekBar]'s SeekBar value if callChangeListener returns true, otherwise
     * set the [SeekBar]'s value to the stored value.
     */
    fun  /* synthetic access */syncValueInternal(seekBar: Slider) {
        val seekBarValue = seekBar.value
        if (seekBarValue != mSeekBarValue) {
            if (callChangeListener(seekBarValue)) {
                setValueInternal(seekBarValue, false)
                if (valueNormalizer != null && seekBar.value != mSeekBarValue) seekBar.value = mSeekBarValue
            } else {
                seekBar.value = validateValue(mSeekBarValue)
                updateLabelValue(mSeekBarValue)
            }
        }
    }

    /**
     * Attempts to update the TextView label that displays the current value.
     *
     * @param value the value to display next to the [SeekBar]
     */
    @SuppressLint("SetTextI18n")
    fun  /* synthetic access */updateLabelValue(value: Float) {
        if (!::mSeekBar.isInitialized || mSeekBar.getTag(R.id.seekbar) !== this) return
        val next = formatLabelValue(value)
        mSeekBarValueTextView?.takeIf { it.text.toString() != next }?.text = next
    }

    private fun formatLabelValue(value: Float): String = valueLabelOverride?.invoke(value)
        // Units are literal text; a percent unit must not become a format token.
        ?: ("%.${mPrecision}f".format(Locale.ROOT, value) + mUnit)

    private fun valueLandsOnTick(value: Float): Boolean {
        // Check that the value is a multiple of stepSize given the offset of valueFrom.
        return isMultipleOfStepSize(value - mMin)
    }

    private fun isMultipleOfStepSize(value: Float): Boolean {
        // We're using BigDecimal here to avoid floating point rounding errors.
        val result = BigDecimal(value.toString())
            .divide(BigDecimal(mSeekBarIncrement.toString()), MathContext.DECIMAL64)
            .toDouble()

        // If the result is a whole number, it means the value is a multiple of stepSize.
        return abs(result.roundToInt() - result) < 1.0E-4
    }
}

