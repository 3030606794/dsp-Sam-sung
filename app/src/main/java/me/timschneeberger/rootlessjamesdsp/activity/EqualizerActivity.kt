package me.timschneeberger.rootlessjamesdsp.activity

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
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
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
    private val bandKey get() = getString(R.string.key_eq_bands)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val binding = ActivityGraphicEqBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "EQ"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        settings = getSharedPreferences(Constants.PREF_EQ, Context.MODE_PRIVATE)
        levels = EqPreview.parse(settings.getString(bandKey, EqPreview.defaultValue) ?: EqPreview.defaultValue)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        chart = PeqResponseView(this)
        body.addView(chart, LinearLayout.LayoutParams(-1, dp(220)))
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
        binding.params.addView(ScrollView(this).apply { addView(body) })
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
