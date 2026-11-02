package me.timschneeberger.rootlessjamesdsp.expansion

import android.content.Context
import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.fusion.BassNumericPreference
import me.timschneeberger.rootlessjamesdsp.fusion.BassParameters

internal object ExpansionEffectsRows {
    fun numeric(context: Context, kind: ExpansionEffectsKind, control: BassParameters.Control, preferences: SharedPreferences): BassNumericPreference =
        BassNumericPreference(context, control, ExpansionEffectsParameters.value(preferences.all, control),
            ExpansionEffectsParameters.options(kind, control.key).keys.sorted().takeIf { it.isNotEmpty() }).apply {
            val metadata = ExpansionEffectsParameters.metadata(kind).first { it.control.key == control.key }
            val options = ExpansionEffectsParameters.options(kind, control.key)
            if (options.isNotEmpty()) valueFormatter = { value -> options[value] ?: control.label(value) }
            else {
                sliderRange = control.min..control.max
                sliderStep = metadata.step
                stepAction = { direction -> requestValue((value.toLong() + direction * metadata.step.toLong()).coerceIn(control.min.toLong(), control.max.toLong()).toInt()) }
                stepLabel = { control.label(metadata.step) }
            }
            summary = ExpansionEffectsParameters.help(kind, control.key)
            if (kind == ExpansionEffectsKind.SMART_ADAPT && control.key in setOf("p0", SmartAdaptIntensity.KEY)) {
                setOnPreferenceChangeListener { _, next ->
                    if (next != value) SmartAdaptState.clear(preferences)
                    true
                }
            }
            val linked = ExpansionEffectsParameters.linkedRange(kind)
            if (linked != null && metadata.logicalKey in setOf(linked.first, linked.second)) {
                val lower = ExpansionEffectsParameters.control(kind, linked.first)
                val upper = ExpansionEffectsParameters.control(kind, linked.second)
                rejectedValueMessage = "${lower.title}须低于${upper.title}，至少间隔 ${linked.third / 1000} Hz"
                valueTransform = { candidate ->
                    if (metadata.logicalKey == linked.first) candidate.coerceAtMost(ExpansionEffectsParameters.value(preferences.all, upper) - linked.third).coerceAtLeast(control.min)
                    else candidate.coerceAtLeast(ExpansionEffectsParameters.value(preferences.all, lower) + linked.third).coerceAtMost(control.max)
                }
            }
        }
}
