package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants

/** Presentation only. Existing effect keys, colors and audio state are independent. */
object EffectIconStyle {
    const val KEY = "appearance_effect_icon_style"
    const val INSTRUMENT = "instrument"
    const val SOLID = "solid"
    const val ORIGINAL = "original"

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)

    fun read(context: Context): String = try {
        preferences(context).getString(KEY, INSTRUMENT)?.takeIf { it == INSTRUMENT || it == SOLID || it == ORIGINAL } ?: INSTRUMENT
    } catch (_: ClassCastException) {
        INSTRUMENT
    }

    /** Repair this key before ListPreference inflation, preserving every other setting. */
    fun normalize(context: Context): String {
        val prefs = preferences(context)
        val value = prefs.all[KEY]
        val normalized = (value as? String)?.takeIf { it == INSTRUMENT || it == SOLID || it == ORIGINAL } ?: INSTRUMENT
        if (prefs.contains(KEY) && value != normalized) prefs.edit().putString(KEY, normalized).apply()
        return normalized
    }

    @DrawableRes
    fun resolve(context: Context, @DrawableRes base: Int): Int {
        val style = read(context)
        if (style == ORIGINAL) return base
        val selected = variants[base] ?: return base
        return if (style == SOLID) selected.second else selected.first
    }

    @DrawableRes
    fun baseForEffect(key: String?): Int? = when (key) {
        "eq_enable" -> R.drawable.ic_effect_curve_equalizer_24
        "geq_enable" -> R.drawable.ic_effect_parametric_equalizer_24
        "dynamic_peq_enable" -> R.drawable.ic_dynamic_peq
        "v_bass" -> R.drawable.ic_effect_bass_shape_24
        "bass_enable" -> R.drawable.ic_effect_adaptive_bass_24
        "v_clarity" -> R.drawable.ic_effect_clarity_24
        "v_spectrum" -> R.drawable.ic_effect_harmonic_exciter_24
        "v_field" -> R.drawable.ic_effect_stereo_shape_24
        "v_diff" -> R.drawable.ic_effect_channel_delay_24
        "stereowide_enable" -> R.drawable.ic_effect_stereo_width_24
        "surround_enabled" -> R.drawable.ic_effect_crosstalk_cancel_24
        "v_cure" -> R.drawable.ic_effect_headphone_crossfeed_24
        "bs2b_crossfeed_enable" -> R.drawable.ic_effect_channel_crossfeed_24
        "v_reverb" -> R.drawable.ic_effect_viper_reverb_24
        "reverb_enable" -> R.drawable.ic_effect_room_reverb_24
        "granular_enable" -> R.drawable.ic_effect_granular_24
        "freeze_enable" -> R.drawable.ic_effect_freeze_24
        "shimmer_enable" -> R.drawable.ic_effect_shimmer_24
        "v_tube" -> R.drawable.ic_effect_tube_softening_24
        "v_analog" -> R.drawable.ic_effect_harmonic_color_24
        "tube_enable" -> R.drawable.ic_effect_multiband_color_24
        "v_dynamic" -> R.drawable.ic_effect_dynamic_bass_shape_24
        "compander_enable" -> R.drawable.ic_effect_compander_24
        "fusion_tone_enabled" -> R.drawable.ic_effect_perceptual_tone_24
        "ddc_enable" -> R.drawable.ic_effect_ddc_correction_24
        "convolver_enable" -> R.drawable.ic_effect_impulse_convolver_24
        "liveprog_enable" -> R.drawable.ic_effect_dsp_script_24
        "expansion_microshift_enable" -> R.drawable.ic_expansion_microshift
        "expansion_formant_enable" -> R.drawable.ic_expansion_formant
        "expansion_frequency_shift_enable" -> R.drawable.ic_expansion_frequency_shift
        "expansion_vocoder_enable" -> R.drawable.ic_expansion_vocoder
        "expansion_gated_reverb_enable" -> R.drawable.ic_expansion_gated_reverb
        "expansion_pingpong_enable" -> R.drawable.ic_expansion_pingpong
        "expansion_auto_wah_enable" -> R.drawable.ic_expansion_auto_wah
        "expansion_autopan_enable" -> R.drawable.ic_expansion_autopan
        "expansion_low_phase_enable" -> R.drawable.ic_expansion_low_phase
        "expansion_tape_enable" -> R.drawable.ic_expansion_tape
        "expansion_ms_eq_enable" -> R.drawable.ic_expansion_ms_eq
        "expansion_match_eq_enable" -> R.drawable.ic_expansion_match_eq
        "expansion_transient_enable" -> R.drawable.ic_expansion_transient
        "expansion_resonance_enable" -> R.drawable.ic_expansion_resonance
        "expansion_sub_bass_enable" -> R.drawable.ic_expansion_sub_bass
        "expansion_loudness_enable" -> R.drawable.ic_expansion_loudness
        "expansion_dereverb_enable" -> R.drawable.ic_expansion_dereverb
        "expansion_declick_enable" -> R.drawable.ic_expansion_declick
        "expansion_dehum_enable" -> R.drawable.ic_expansion_dehum
        "expansion_headtrack_enable" -> R.drawable.ic_expansion_headtrack
        "expansion_vocal_enable" -> R.drawable.ic_expansion_vocal
        "expansion_speaker_optimization_enable" -> R.drawable.ic_expansion_speaker_optimization
        "expansion_smart_adapt_enable" -> R.drawable.ic_expansion_smart_adapt
        else -> null
    }

    private val variants = mapOf(
        R.drawable.ic_expansion_smart_adapt to (R.drawable.ic_expansion_instrument_smart_adapt to R.drawable.ic_expansion_solid_smart_adapt),
        R.drawable.ic_expansion_speaker_optimization to (R.drawable.ic_expansion_instrument_speaker_optimization to R.drawable.ic_expansion_solid_speaker_optimization),
        R.drawable.ic_expansion_microshift to (R.drawable.ic_expansion_instrument_microshift to R.drawable.ic_expansion_solid_microshift),
        R.drawable.ic_expansion_formant to (R.drawable.ic_expansion_instrument_formant to R.drawable.ic_expansion_solid_formant),
        R.drawable.ic_expansion_frequency_shift to (R.drawable.ic_expansion_instrument_frequency_shift to R.drawable.ic_expansion_solid_frequency_shift),
        R.drawable.ic_expansion_vocoder to (R.drawable.ic_expansion_instrument_vocoder to R.drawable.ic_expansion_solid_vocoder),
        R.drawable.ic_expansion_gated_reverb to (R.drawable.ic_expansion_instrument_gated_reverb to R.drawable.ic_expansion_solid_gated_reverb),
        R.drawable.ic_expansion_pingpong to (R.drawable.ic_expansion_instrument_pingpong to R.drawable.ic_expansion_solid_pingpong),
        R.drawable.ic_expansion_auto_wah to (R.drawable.ic_expansion_instrument_auto_wah to R.drawable.ic_expansion_solid_auto_wah),
        R.drawable.ic_expansion_autopan to (R.drawable.ic_expansion_instrument_autopan to R.drawable.ic_expansion_solid_autopan),
        R.drawable.ic_expansion_low_phase to (R.drawable.ic_expansion_instrument_low_phase to R.drawable.ic_expansion_solid_low_phase),
        R.drawable.ic_expansion_tape to (R.drawable.ic_expansion_instrument_tape to R.drawable.ic_expansion_solid_tape),
        R.drawable.ic_expansion_ms_eq to (R.drawable.ic_expansion_instrument_ms_eq to R.drawable.ic_expansion_solid_ms_eq),
        R.drawable.ic_expansion_match_eq to (R.drawable.ic_expansion_instrument_match_eq to R.drawable.ic_expansion_solid_match_eq),
        R.drawable.ic_expansion_transient to (R.drawable.ic_expansion_instrument_transient to R.drawable.ic_expansion_solid_transient),
        R.drawable.ic_expansion_resonance to (R.drawable.ic_expansion_instrument_resonance to R.drawable.ic_expansion_solid_resonance),
        R.drawable.ic_expansion_sub_bass to (R.drawable.ic_expansion_instrument_sub_bass to R.drawable.ic_expansion_solid_sub_bass),
        R.drawable.ic_expansion_loudness to (R.drawable.ic_expansion_instrument_loudness to R.drawable.ic_expansion_solid_loudness),
        R.drawable.ic_expansion_dereverb to (R.drawable.ic_expansion_instrument_dereverb to R.drawable.ic_expansion_solid_dereverb),
        R.drawable.ic_expansion_declick to (R.drawable.ic_expansion_instrument_declick to R.drawable.ic_expansion_solid_declick),
        R.drawable.ic_expansion_dehum to (R.drawable.ic_expansion_instrument_dehum to R.drawable.ic_expansion_solid_dehum),
        R.drawable.ic_expansion_headtrack to (R.drawable.ic_expansion_instrument_headtrack to R.drawable.ic_expansion_solid_headtrack),
        R.drawable.ic_expansion_vocal to (R.drawable.ic_expansion_instrument_vocal to R.drawable.ic_expansion_solid_vocal),

        R.drawable.ic_effect_curve_equalizer_24 to (R.drawable.ic_effect_instrument_curve_equalizer_24 to R.drawable.ic_effect_solid_curve_equalizer_24),
        R.drawable.ic_effect_parametric_equalizer_24 to (R.drawable.ic_effect_instrument_parametric_equalizer_24 to R.drawable.ic_effect_solid_parametric_equalizer_24),
        R.drawable.ic_dynamic_peq to (R.drawable.ic_effect_instrument_dynamic_equalizer_24 to R.drawable.ic_effect_solid_dynamic_equalizer_24),
        R.drawable.ic_effect_bass_shape_24 to (R.drawable.ic_effect_instrument_bass_shape_24 to R.drawable.ic_effect_solid_bass_shape_24),
        R.drawable.ic_effect_adaptive_bass_24 to (R.drawable.ic_effect_instrument_adaptive_bass_24 to R.drawable.ic_effect_solid_adaptive_bass_24),
        R.drawable.ic_effect_clarity_24 to (R.drawable.ic_effect_instrument_clarity_24 to R.drawable.ic_effect_solid_clarity_24),
        R.drawable.ic_effect_harmonic_exciter_24 to (R.drawable.ic_effect_instrument_harmonic_exciter_24 to R.drawable.ic_effect_solid_harmonic_exciter_24),
        R.drawable.ic_effect_stereo_shape_24 to (R.drawable.ic_effect_instrument_stereo_shape_24 to R.drawable.ic_effect_solid_stereo_shape_24),
        R.drawable.ic_effect_channel_delay_24 to (R.drawable.ic_effect_instrument_channel_delay_24 to R.drawable.ic_effect_solid_channel_delay_24),
        R.drawable.ic_effect_stereo_width_24 to (R.drawable.ic_effect_instrument_stereo_width_24 to R.drawable.ic_effect_solid_stereo_width_24),
        R.drawable.ic_effect_crosstalk_cancel_24 to (R.drawable.ic_effect_instrument_crosstalk_cancel_24 to R.drawable.ic_effect_solid_crosstalk_cancel_24),
        R.drawable.ic_effect_headphone_crossfeed_24 to (R.drawable.ic_effect_instrument_headphone_crossfeed_24 to R.drawable.ic_effect_solid_headphone_crossfeed_24),
        R.drawable.ic_effect_channel_crossfeed_24 to (R.drawable.ic_effect_instrument_channel_crossfeed_24 to R.drawable.ic_effect_solid_channel_crossfeed_24),
        R.drawable.ic_effect_viper_reverb_24 to (R.drawable.ic_effect_instrument_viper_reverb_24 to R.drawable.ic_effect_solid_viper_reverb_24),
        R.drawable.ic_effect_room_reverb_24 to (R.drawable.ic_effect_instrument_room_reverb_24 to R.drawable.ic_effect_solid_room_reverb_24),
        R.drawable.ic_effect_granular_24 to (R.drawable.ic_effect_instrument_granular_24 to R.drawable.ic_effect_solid_granular_24),
        R.drawable.ic_effect_freeze_24 to (R.drawable.ic_effect_instrument_freeze_24 to R.drawable.ic_effect_solid_freeze_24),
        R.drawable.ic_effect_shimmer_24 to (R.drawable.ic_effect_instrument_shimmer_24 to R.drawable.ic_effect_solid_shimmer_24),
        R.drawable.ic_effect_tube_softening_24 to (R.drawable.ic_effect_instrument_tube_softening_24 to R.drawable.ic_effect_solid_tube_softening_24),
        R.drawable.ic_effect_harmonic_color_24 to (R.drawable.ic_effect_instrument_harmonic_color_24 to R.drawable.ic_effect_solid_harmonic_color_24),
        R.drawable.ic_effect_multiband_color_24 to (R.drawable.ic_effect_instrument_multiband_color_24 to R.drawable.ic_effect_solid_multiband_color_24),
        R.drawable.ic_effect_dynamic_bass_shape_24 to (R.drawable.ic_effect_instrument_dynamic_bass_shape_24 to R.drawable.ic_effect_solid_dynamic_bass_shape_24),
        R.drawable.ic_effect_compander_24 to (R.drawable.ic_effect_instrument_compander_24 to R.drawable.ic_effect_solid_compander_24),
        R.drawable.ic_effect_perceptual_tone_24 to (R.drawable.ic_effect_instrument_perceptual_tone_24 to R.drawable.ic_effect_solid_perceptual_tone_24),
        R.drawable.ic_effect_ddc_correction_24 to (R.drawable.ic_effect_instrument_ddc_correction_24 to R.drawable.ic_effect_solid_ddc_correction_24),
        R.drawable.ic_effect_impulse_convolver_24 to (R.drawable.ic_effect_instrument_impulse_convolver_24 to R.drawable.ic_effect_solid_impulse_convolver_24),
        R.drawable.ic_effect_dsp_script_24 to (R.drawable.ic_effect_instrument_dsp_script_24 to R.drawable.ic_effect_solid_dsp_script_24),
        R.drawable.ic_effect_settings_24 to (R.drawable.ic_effect_instrument_settings_24 to R.drawable.ic_effect_solid_settings_24),
    )
}
