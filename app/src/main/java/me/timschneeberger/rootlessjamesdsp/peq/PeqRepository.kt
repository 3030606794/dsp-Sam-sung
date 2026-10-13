package me.timschneeberger.rootlessjamesdsp.peq

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.*
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import java.security.MessageDigest

data class PeqLoadResult(
    val state: PeqState,
    val hasSavedState: Boolean,
    val corrupt: Boolean,
    val error: String? = null,
    val oldGeneratedDistribution: Boolean = false,
    val originalBandCount: Int? = null,
    val bandLimitBackupSaved: Boolean = true
)

internal data class PeqDecodedState(val state: PeqState, val originalBandCount: Int)

/** Stored with existing graphic EQ presets, without replacing its legacy nodes or enable key. */
class PeqRepository(
    private val preferences: SharedPreferences,
    private val recoveryPreferences: SharedPreferences? = null
) {
    private var lastVerifiedCurrentJson: String? = null

    @Suppress("DEPRECATION")
    constructor(context: Context) : this(
        context.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_MULTI_PROCESS),
        context.getSharedPreferences(BAND_LIMIT_BACKUP_PREFERENCES, Context.MODE_PRIVATE))

    fun hasSavedState(): Boolean = preferences.contains(KEY_STATE)

    fun load(): PeqState = loadResult().state

    fun loadResult(): PeqLoadResult = synchronized(storageLock) {
        if (!hasSavedState()) return@synchronized PeqLoadResult(PeqModel.defaultState(), false, false)
        val encoded: String
        val decoded: PeqDecodedState
        try {
            encoded = requireNotNull(preferences.getString(KEY_STATE, null)) { "Missing PEQ state" }
            decoded = decodeLocal(encoded)
        } catch (failure: RuntimeException) {
            // Keep invalid data available for diagnosis or manual recovery.
            return@synchronized PeqLoadResult(PeqModel.defaultState(), true, true, failure.message ?: "Invalid PEQ state")
        }
        val state = decoded.state
        val reduced = decoded.originalBandCount > PeqModel.MAX_BANDS
        if (!reduced) lastVerifiedCurrentJson = encoded
        var pendingCount: Int? = if (reduced) decoded.originalBandCount else null
        val persisted = try {
            pendingCount = if (reduced) decoded.originalBandCount else
                preferences.getInt(KEY_LIMIT_PENDING, 0).takeIf { it > PeqModel.MAX_BANDS }
            if (reduced) {
                // Recovery is outside device/global preset namespaces and must reach disk first.
                persistBandLimitBackup(encoded) && preferences.edit()
                        .putString(KEY_STATE, encode(state))
                        .putInt(KEY_LIMIT_PENDING, decoded.originalBandCount)
                        .commit()
            } else if (pendingCount != null) {
                // A failed commit still updates SharedPreferences in memory. Retry the pending
                // migration before claiming that its backup has reached persistent storage.
                preferences.edit().putInt(KEY_LIMIT_PENDING, pendingCount!!).commit()
            } else true
        } catch (_: RuntimeException) { false }
        // Storage failure must not turn a valid legacy bank into an apparent corrupt/default bank.
        return@synchronized PeqLoadResult(state, true, false, oldGeneratedDistribution = PeqModel.isOldGeneratedDistribution(state),
            originalBandCount = pendingCount, bandLimitBackupSaved = persisted)
    }

    fun save(state: PeqState): Boolean = synchronized(storageLock) {
        val encoded = exportJson(state)
        val edit = preferences.edit()
        // A write can arrive before the UI reads the old bank. Back it up before replacement too.
        val old = runCatching { preferences.getString(KEY_STATE, null) }.getOrNull()
        val oldCount = old?.takeUnless { it == lastVerifiedCurrentJson }
            ?.let { runCatching { decodeLocal(it).originalBandCount }.getOrNull() }
        if (old != null && oldCount != null && oldCount > PeqModel.MAX_BANDS) {
            if (!runCatching { persistBandLimitBackup(old) }.getOrDefault(false)) return@synchronized false
            edit.putInt(KEY_LIMIT_PENDING, oldCount)
        }
        return@synchronized edit.putString(KEY_STATE, encoded).commit().also { saved ->
            if (saved) lastVerifiedCurrentJson = encoded
        }
    }

    fun acknowledgeBandLimitMigration(): Boolean = synchronized(storageLock) {
        preferences.edit().remove(KEY_LIMIT_PENDING).commit()
    }

    private fun persistBandLimitBackup(encoded: String): Boolean {
        // Injected repositories also need an independent recovery store before trimming data.
        val recovery = recoveryPreferences ?: return false
        val hash = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return recovery.edit().putString("$KEY_LIMIT_BACKUP.$hash", encoded)
            .putString(KEY_LIMIT_BACKUP, encoded).commit()
    }

    fun importJson(json: String): PeqState = decode(json)
    fun exportJson(state: PeqState): String = encode(state)

    companion object {
        // Loads can migrate data, so serialize their read/backup/write with saves across instances.
        private val storageLock = Any()
        const val KEY_STATE = "fusion_peq_state"
        const val BAND_LIMIT_BACKUP_PREFERENCES = "fusion_peq_band_limit_recovery"
        const val KEY_LIMIT_BACKUP = "fusion_peq_state_before_130"
        private const val KEY_LIMIT_PENDING = "fusion_peq_limit_130_pending_count"
        internal const val LEGACY_MAX_BANDS = 170
        // Schema 2 gives low/high-pass, notch and band-pass gain an audible meaning.
        const val SCHEMA = 2
        const val MAX_JSON_LENGTH = 256 * 1024

        fun encode(state: PeqState): String {
            PeqModel.validate(state)
            return buildJsonObject {
                put("schema", SCHEMA)
                put("gainRange", state.gainRange)
                put("gainStep", state.gainStep)
                put("qStep", state.qStep)
                put("qRange", state.qRange)
                put("frequencyStep", state.frequencyStep)
                put("pinResponse", state.pinResponse)
                put("showQSlider", state.showQSlider)
                put("showFrequencySlider", state.showFrequencySlider)
                put("bands", buildJsonArray {
                    state.bands.forEach { band -> add(buildJsonObject {
                        put("id", band.id)
                        put("type", band.type.name)
                        put("enabled", band.enabled)
                        put("frequency", band.frequency)
                        put("gain", band.gain)
                        put("q", band.q)
                        put("qualityDisplay", band.qualityDisplay.name)
                    }) }
                })
            }.toString()
        }

        fun decode(json: String): PeqState = decodeDocument(json, allowLegacyCount = false).state

        internal fun decodeLocal(json: String): PeqDecodedState = decodeDocument(json, allowLegacyCount = true)

        private fun decodeDocument(json: String, allowLegacyCount: Boolean): PeqDecodedState {
            require(json.length <= MAX_JSON_LENGTH) { "PEQ JSON is too large" }
            val root = Json.parseToJsonElement(json) as? JsonObject
                ?: throw IllegalArgumentException("PEQ JSON must be an object")
            val schema = root.number("schema")
            require(schema == 1.0 || schema == SCHEMA.toDouble()) { "Unsupported PEQ schema" }
            val bands = root["bands"] as? JsonArray
                ?: throw IllegalArgumentException("bands must be an array")
            require(bands.size <= if (allowLegacyCount) LEGACY_MAX_BANDS else PeqModel.MAX_BANDS) {
                "最多支持 ${PeqModel.MAX_BANDS} 个频段"
            }
            val decoded = PeqState(
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
                    val qualityDisplay = if (band.containsKey("qualityDisplay")) {
                        val name = band.text("qualityDisplay")
                        PeqQualityDisplay.entries.firstOrNull { it.name == name }
                            ?: throw IllegalArgumentException("Unknown quality display: $name")
                    } else PeqQualityDisplay.Q
                    val stored = PeqModel.validateBand(PeqBand(band.text("id"), type, enabled.boolean,
                        band.number("frequency"), band.number("gain"), band.number("q"), qualityDisplay))
                    // Older versions retained an inactive gain when switching filter types.
                    // Keep their audible response on upgrade instead of activating that hidden value.
                    if (schema == 1.0 && !type.isUnityAtZeroGain) stored.copy(gain = 0.0) else stored
                },
                gainRange = root.number("gainRange"),
                gainStep = root.number("gainStep"),
                qStep = if (root.containsKey("qStep")) root.number("qStep") else 0.01,
                qRange = if (root.containsKey("qRange")) root.number("qRange") else PeqModel.MAX_Q,
                frequencyStep = if (root.containsKey("frequencyStep")) root.number("frequencyStep") else 1.0,
                showQSlider = root.optionalBoolean("showQSlider", false),
                showFrequencySlider = root.optionalBoolean("showFrequencySlider", false),
                pinResponse = root.optionalBoolean("pinResponse", true)
            )
            // Validate the complete legacy bank, including discarded tail IDs and parameters.
            require(decoded.bands.map { it.id }.toSet().size == decoded.bands.size) { "Band ids must be unique" }
            return PeqDecodedState(PeqModel.validate(decoded.copy(bands = decoded.bands.take(PeqModel.MAX_BANDS))), bands.size)
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

        private fun JsonObject.optionalBoolean(key: String, default: Boolean): Boolean {
            if (!containsKey(key)) return default
            val value = this[key] as? JsonPrimitive
            require(value != null && !value.isString && value.booleanOrNull != null) { "$key must be a boolean" }
            return value.boolean
        }
    }
}
