package me.timschneeberger.rootlessjamesdsp.activity

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ActivityGraphicEqBinding
import me.timschneeberger.rootlessjamesdsp.peq.PeqResponseView
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.view.EqPreview
import me.timschneeberger.rootlessjamesdsp.view.EqualizerSurface
import java.util.Locale

/** Full-screen editor for the existing 15-band EQ namespace. */
class EqualizerActivity : BaseActivity() {
    private lateinit var settings: SharedPreferences
    private lateinit var chart: PeqResponseView
    private var levels = DoubleArray(15)
    private val sliders = mutableListOf<Slider>()
    private var refreshing = false
    private lateinit var body: LinearLayout
    private lateinit var pinnedArea: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var filterButton: MaterialButton
    private lateinit var interpolationButton: MaterialButton
    private var pinResponse = false
    private val pinMenuId = 0x451001

    internal fun setPinned(value: Boolean) {
        pinResponse = value
        (chart.parent as? android.view.ViewGroup)?.removeView(chart)
        if (value) pinnedArea.addView(chart, LinearLayout.LayoutParams(-1, dp(220)))
        else body.addView(chart, 0, LinearLayout.LayoutParams(-1, dp(220)))
        prefsApp.preferences.edit().putBoolean("eq_pin_response", value).apply()
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, pinMenuId, 0, "固定曲线").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        menu.findItem(pinMenuId)?.apply {
            title = if (pinResponse) getString(R.string.peq_unpin_response) else getString(R.string.peq_pin_response)
            setIcon(if (pinResponse) R.drawable.ic_peq_pin else R.drawable.ic_peq_pin_off)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == pinMenuId) { setPinned(!pinResponse); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun chooseSetting(keyRes: Int, titleRes: Int, labelsRes: Int, valuesRes: Int) {
        val key = getString(keyRes)
        val labels = resources.getStringArray(labelsRes)
        val values = resources.getStringArray(valuesRes)
        val selected = values.indexOf(settings.getString(key, "0")).coerceAtLeast(0)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(titleRes).setSingleChoiceItems(labels, selected) { dialog, index ->
                settings.edit().putString(key, values[index]).apply()
                sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
                dialog.dismiss()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
    private val bandKey get() = getString(R.string.key_eq_bands)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val binding = ActivityGraphicEqBinding.inflate(layoutInflater)
        binding.peqToolbarSummaryRow.visibility = View.GONE
        binding.toolbar.layoutParams = binding.toolbar.layoutParams.apply { height = dp(64) }
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "EQ"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        settings = getSharedPreferences(Constants.PREF_EQ, Context.MODE_PRIVATE)
        levels = EqPreview.parse(settings.getString(bandKey, EqPreview.defaultValue) ?: EqPreview.defaultValue)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        chart = PeqResponseView(this).apply { id = R.id.layout_peq }
        body.addView(chart, LinearLayout.LayoutParams(-1, dp(220)))
        filterButton = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setOnClickListener { chooseSetting(R.string.key_eq_filter_type, R.string.eq_filter_type, R.array.eq_filter_types, R.array.eq_filter_types_values) }
        }
        interpolationButton = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setOnClickListener { chooseSetting(R.string.key_eq_interpolation, R.string.eq_interpolator, R.array.eq_interpolators, R.array.eq_interpolators_values) }
        }
        body.addView(filterButton)
        body.addView(interpolationButton)
        body.addView(TextView(this).apply { text = "15 个频段 · 实时保存"; setPadding(0, dp(12), 0, dp(12)) })
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = resources.getStringArray(R.array.equalizer_preset_modes)
        val values = resources.getStringArray(R.array.equalizer_preset_values)
        labels.forEachIndexed { i, label ->
            presets.addView(MaterialButton(this).apply {
                text = label
                setOnClickListener {
                    val next = values[i].split(';').filter(String::isNotBlank).map { it.toDoubleOrNull() ?: 0.0 }
                    levels = DoubleArray(15) { next.getOrElse(it) { 0.0 }.coerceIn(-12.0, 12.0) }
                    persist()
                }
            })
        }
        body.addView(android.widget.HorizontalScrollView(this).apply { addView(presets) })
        EqualizerSurface.SCALE.forEachIndexed { index, frequency ->
            body.addView(TextView(this).apply {
                text = "${frequency.toInt()} Hz"
                setPadding(0, dp(12), 0, 0)
            })
            val slider = Slider(this).apply {
                valueFrom = -12f
                valueTo = 12f
                stepSize = 0.1f
                value = (kotlin.math.round(levels[index].coerceIn(-12.0, 12.0) * 10) / 10).toFloat()
                contentDescription = "${frequency.toInt()} Hz"
                setLabelFormatter { String.format(Locale.ROOT, "%.1f dB", it) }
                addOnChangeListener { _, value, fromUser ->
                    if (fromUser && !refreshing) setBandGain(index, value.toDouble())
                }
            }
            sliders += slider
            body.addView(slider)
        }
        body.addView(MaterialButton(this).apply {
            text = "重置增益"
            setOnClickListener { levels = DoubleArray(15); persist() }
        })
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pinnedArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(body) }
        page.addView(pinnedArea, LinearLayout.LayoutParams(-1, -2))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        binding.params.addView(page)
        pinResponse = prefsApp.preferences.getBoolean("eq_pin_response", false)
        if (pinResponse) {
            body.removeView(chart)
            pinnedArea.addView(chart, LinearLayout.LayoutParams(-1, dp(220)))
        }
        settings.registerOnSharedPreferenceChangeListener(listener)
        refresh()
    }

    internal fun setBandGain(index: Int, gain: Double) {
        require(index in 0..14 && gain.isFinite())
        levels[index] = gain.coerceIn(-12.0, 12.0)
        persist()
    }

    private fun persist() {
        settings.edit().putString(bandKey, EqPreview.encode(levels)).apply()
        sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
        refresh()
    }

    private fun refresh() {
        if (!::chart.isInitialized) return
        val value = settings.getString(bandKey, EqPreview.defaultValue) ?: EqPreview.defaultValue
        levels = EqPreview.parse(value)
        val types = resources.getStringArray(R.array.eq_filter_types)
        val typeValues = resources.getStringArray(R.array.eq_filter_types_values)
        val typeIndex = typeValues.indexOf(settings.getString(getString(R.string.key_eq_filter_type), "0")).coerceAtLeast(0)
        filterButton.text = "${getString(R.string.eq_filter_type)}\n${types[typeIndex]}"
        val interpolators = resources.getStringArray(R.array.eq_interpolators)
        val interpolationValues = resources.getStringArray(R.array.eq_interpolators_values)
        val interpolationIndex = interpolationValues.indexOf(settings.getString(getString(R.string.key_eq_interpolation), "0")).coerceAtLeast(0)
        interpolationButton.text = "${getString(R.string.eq_interpolator)}\n${interpolators[interpolationIndex]}"
        interpolationButton.isEnabled = typeIndex == 0
        refreshing = true
        sliders.forEachIndexed { i, slider ->
            slider.value = (kotlin.math.round(levels[i].coerceIn(-12.0, 12.0) * 10) / 10).toFloat()
        }
        refreshing = false
        EqPreview.render(this, chart, value)
    }

    override fun onDestroy() {
        if (::settings.isInitialized) settings.unregisterOnSharedPreferenceChangeListener(listener)
        super.onDestroy()
    }
}
