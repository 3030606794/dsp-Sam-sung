package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.*
import me.timschneeberger.rootlessjamesdsp.utils.Constants

data class PeqLoadResult(
    val state: PeqState,
    val hasSavedState: Boolean,
    val corrupt: Boolean,
    val error: String? = null
)

/** Stored with existing graphic EQ presets, without replacing its legacy nodes or enable key. */
class PeqRepository(private val preferences: SharedPreferences) {
    @Suppress("DEPRECATION")
    constructor(context: Context) : this(context.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_MULTI_PROCESS))

    fun hasSavedState(): Boolean = preferences.contains(KEY_STATE)

    fun load(): PeqState = loadResult().state

    fun loadResult(): PeqLoadResult {
        if (!hasSavedState()) return PeqLoadResult(PeqModel.defaultState(), false, false)
        return try {
            val encoded = requireNotNull(preferences.getString(KEY_STATE, null)) { "Missing PEQ state" }
            PeqLoadResult(importJson(encoded), true, false)
        } catch (failure: RuntimeException) {
            // Keep the original payload available for diagnosis or manual recovery.
            PeqLoadResult(PeqModel.defaultState(), true, true, failure.message ?: "Invalid PEQ state")
        }
    }

    fun save(state: PeqState): Boolean = preferences.edit().putString(KEY_STATE, exportJson(state)).commit()

    fun importJson(json: String): PeqState = decode(json)
    fun exportJson(state: PeqState): String = encode(state)

    companion object {
        const val KEY_STATE = "fusion_peq_state"
        const val SCHEMA = 1
        const val MAX_JSON_LENGTH = 256 * 1024

        fun encode(state: PeqState): String {
            PeqModel.validate(state)
            return buildJsonObject {
                put("schema", SCHEMA)
                put("gainRange", state.gainRange)
                put("gainStep", state.gainStep)
                put("bands", buildJsonArray {
                    state.bands.forEach { band -> add(buildJsonObject {
                        put("id", band.id)
                        put("type", band.type.name)
                        put("enabled", band.enabled)
                        put("frequency", band.frequency)
                        put("gain", band.gain)
                        put("q", band.q)
                    }) }
                })
            }.toString()
        }

        fun decode(json: String): PeqState {
            require(json.length <= MAX_JSON_LENGTH) { "PEQ JSON is too large" }
            val root = Json.parseToJsonElement(json) as? JsonObject
                ?: throw IllegalArgumentException("PEQ JSON must be an object")
            val schema = root.number("schema")
            require(schema == SCHEMA.toDouble()) { "Unsupported PEQ schema" }
            val bands = root["bands"] as? JsonArray
                ?: throw IllegalArgumentException("bands must be an array")
            require(bands.size <= PeqModel.MAX_BANDS) { "At most 170 bands are supported" }
            return PeqModel.validate(PeqState(
                bands = bands.map { element ->
                    val band = element as? JsonObject
                        ?: throw IllegalArgumentException("Each band must be an object")
                    val typeName = band.text("type")
                    val type = PeqFilterType.entries.firstOrNull { it.name == typeName }
                        ?: throw IllegalArgumentException("Unknown filter type: $typeName")
                    val enabled = band["enabled"] as? JsonPrimitive
                    require(enabled != null && !enabled.isString && enabled.booleanOrNull != null) {
                        "enabled must be a boolean"
                    }
                    PeqBand(band.text("id"), type, enabled.boolean, band.number("frequency"),
                        band.number("gain"), band.number("q"))
                },
                gainRange = root.number("gainRange"),
                gainStep = root.number("gainStep")
            ))
        }

        private fun JsonObject.text(key: String): String {
            val value = this[key] as? JsonPrimitive
            require(value != null && value.isString) { "$key must be a string" }
            return value.content
        }

        private fun JsonObject.number(key: String): Double {
            val value = this[key] as? JsonPrimitive
            require(value != null && !value.isString) { "$key must be a number" }
            return requireNotNull(value.doubleOrNull?.takeIf { it.isFinite() }) { "$key must be a finite number" }
        }
    }
}
