package me.timschneeberger.rootlessjamesdsp.liveprog

import android.content.Context

/**
 * Known stock scripts keep their original, editable files and fingerprints.
 * Their runtime copies separate coefficient updates from buffer initialization.
 * No script supplied or edited by the user is rewritten unless its stock code
 * identity still matches; saved parameter literals are the only permitted edits.
 */
internal object BuiltinLiveprogRuntime {
    private val adapted = setOf(
        "3bandSplitting.eel", "8bandSplitting.eel", "audioGlitchGenerator.eel",
        "autoWideness.eel", "delayChorus.eel", "depthsurround.eel",
        "fractionalDelayline.eel", "gainControl.eel", "hadamVerb.eel",
        "highpass.eel", "Joe0Bloggs DRX10K compander-HR.eel",
        "lofiDistortionMangler.eel", "lowpass.eel", "msCentreBoost.eel",
        "stereoFieldManipulator.eel", "stereoPhaseInvert.eel",
        "stftCentreBoost.eel", "stftFilter.eel", "swapChannels.eel",
        "timeAdjustment.eel", "viper_dynamicbass.eel", "viper_dynamicbass_preset.eel",
    )
    private const val NUMBER = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?"
    private val annotation = Regex("(?m)^([A-Za-z_][A-Za-z_0-9]*):[^\\r\\n<]*<")

    fun prepare(context: Context, name: String, source: String): String {
        if (name !in adapted || BuiltinLiveprogMetadata.identifiedTitle(name, source) == null)
            return source

        var runtime = context.assets.open("LiveprogRuntime/$name")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        for (key in annotation.findAll(source).map { it.groupValues[1] }.distinct()) {
            // Copy the literal text itself, preserving fine steps without a Float round trip.
            val assignment = Regex("(?<![\\w.])${Regex.escape(key)}\\s*=\\s*($NUMBER)\\s*;")
                .find(source) ?: return source
            runtime = EelNumberRangeProperty.replaceVariable(key, assignment.groupValues[1], runtime)
                ?: return source
        }
        return runtime
    }
}
