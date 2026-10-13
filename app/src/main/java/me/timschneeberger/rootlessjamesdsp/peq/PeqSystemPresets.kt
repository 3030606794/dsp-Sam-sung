package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil

/** Original numerical definitions are retained in the asset; driver adaptations are explicit. */
data class PeqSystemPreset(
    val id: String,
    val name: String,
    val category: String,
    val bands: List<PeqBand>,
    val sourceBandwidths: List<Double?>,
    val sourcePreampDb: Double
) {
    val adaptedFrequencyCount get() = bands.count { it.frequency < PeqModel.MIN_FREQUENCY }

    /** Loading a template creates a new bank, while retaining the user's editor settings. */
    fun applyTo(current: PeqState): PeqState {
        val next = bands.mapIndexed { index, band ->
            val frequency = band.frequency.coerceAtLeast(PeqModel.MIN_FREQUENCY)
            val q = sourceBandwidths[index]?.let {
                PeqResponse.qFromBandwidth(it, frequency, PeqResponse.DEFAULT_SAMPLE_RATE)
            } ?: band.q
            band.copy(id = UUID.randomUUID().toString(), frequency = frequency, q = q)
        }
        return PeqModel.validate(current.copy(bands = next,
            gainRange = maxOf(current.gainRange, ceil(next.maxOf { abs(it.gain) })),
            qRange = maxOf(current.qRange, ceil(next.maxOf { it.q }))))
    }
}

/** Built-in templates do not consume or overwrite entries in the personal preset library. */
object PeqSystemPresets {
    const val ASSET = "peq/neutron-default-presets.json"

    fun load(context: Context): List<PeqSystemPreset> =
        context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { decode(it.readText()) }

    internal fun decode(json: String): List<PeqSystemPreset> {
        val root = Json.parseToJsonElement(json).jsonObject
        require(root["format"]?.jsonPrimitive?.content == "neutron-default-presets-extraction")
        require(root["version"]?.jsonPrimitive?.int == 1)
        val presets = root.getValue("presets").jsonArray.map { element ->
            val entry = element.jsonObject
            val definitions = entry.getValue("bands").jsonArray.map { it.jsonObject }
            val bands = definitions.map { band ->
                PeqBand(type = PeqFilterType.valueOf(band.getValue("type").jsonPrimitive.content),
                    frequency = band.number("frequency"), gain = band.number("gain"), q = band.number("q"),
                    enabled = band.getValue("enabled").jsonPrimitive.boolean,
                    qualityDisplay = PeqQualityDisplay.valueOf(band.getValue("qualityDisplay").jsonPrimitive.content))
            }
            require(bands.size == entry.getValue("sourceBandCount").jsonPrimitive.int)
            require(bands.isNotEmpty() && bands.size <= PeqModel.MAX_BANDS)
            require(bands.all { it.frequency > 0 && it.frequency <= PeqModel.MAX_FREQUENCY })
            PeqSystemPreset(entry.getValue("id").jsonPrimitive.content,
                entry.getValue("name").jsonPrimitive.content,
                entry.getValue("category").jsonPrimitive.content, bands,
                definitions.map { it["sourceBandwidthOctaves"]?.jsonPrimitive?.double?.also { bw ->
                    require(bw.isFinite() && bw > 0)
                } }, entry.number("sourcePreampDb")).also {
                require(it.category == "parametric" || it.category == "graphic")
                it.applyTo(PeqModel.defaultState(0)) // Validate every imported template before exposing any.
            }
        }
        require(presets.size == root.getValue("source").jsonObject.getValue("total").jsonPrimitive.int)
        require(presets.map { it.id }.toSet().size == presets.size)
        return presets
    }

    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.double.also { require(it.isFinite()) }
}
