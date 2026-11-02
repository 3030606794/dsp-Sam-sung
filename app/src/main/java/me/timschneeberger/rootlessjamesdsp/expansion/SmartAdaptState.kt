package me.timschneeberger.rootlessjamesdsp.expansion

import android.content.SharedPreferences
import kotlin.math.roundToInt

/** Only a verified native full-strength target is persisted. Analysis history and routing are not. */
object SmartAdaptState {
    const val SCHEMA_KEY = "smart_adapt_snapshot_schema"
    const val TARGET_KEY = "smart_adapt_snapshot_target"
    const val LOW_KEY = "smart_adapt_snapshot_low"
    const val MID_KEY = "smart_adapt_snapshot_mid"
    const val HIGH_KEY = "smart_adapt_snapshot_high"
    const val WIDTH_KEY = "smart_adapt_snapshot_width"
    const val INTENSITY_KEY = "smart_adapt_snapshot_intensity"
    private val LEGACY_KEYS = setOf(SCHEMA_KEY, TARGET_KEY, LOW_KEY, MID_KEY, HIGH_KEY, WIDTH_KEY)
    val PREFERENCE_KEYS = LEGACY_KEYS + INTENSITY_KEY

    data class Snapshot(val target: Int, val low: Int, val mid: Int, val high: Int, val width: Int,
        val intensity: Int = SmartAdaptIntensity.DEFAULT) {
        fun valid() = target in setOf(0, 1000, 2000, 3000) &&
            low in -12000..12000 && mid in -12000..12000 && high in -12000..12000 && width in 0..600 &&
            SmartAdaptIntensity.isValid(intensity)
    }

    private fun currentTarget(values: Map<String, *>): Int =
        (values["p0"] as? Int)?.takeIf { it in setOf(0, 1000, 2000, 3000) } ?: 0

    /** Old targets already include the old device limits: never multiply them during upgrade. */
    private fun legacySnapshot(values: Map<String, *>): Snapshot? {
        if (values[SCHEMA_KEY] != 1 || INTENSITY_KEY in values ||
            values[SmartAdaptIntensity.KEY]?.let { it != 0 } == true || !LEGACY_KEYS.all(values::containsKey)) return null
        val snapshot = Snapshot(values[TARGET_KEY] as? Int ?: return null,
            values[LOW_KEY] as? Int ?: return null, values[MID_KEY] as? Int ?: return null,
            values[HIGH_KEY] as? Int ?: return null, values[WIDTH_KEY] as? Int ?: return null, 0)
        return snapshot.takeIf { it.valid() && it.target == currentTarget(values) &&
            listOf(it.low, it.mid, it.high).all { gain -> gain in -6000..6000 } }
    }

    fun read(values: Map<String, *>): Snapshot? {
        if (values[SCHEMA_KEY] == 1) return legacySnapshot(values)
        if (values[SCHEMA_KEY] != SmartAdaptProtocol.SNAPSHOT_SCHEMA) return null
        val snapshot = Snapshot(values[TARGET_KEY] as? Int ?: return null,
            values[LOW_KEY] as? Int ?: return null, values[MID_KEY] as? Int ?: return null,
            values[HIGH_KEY] as? Int ?: return null, values[WIDTH_KEY] as? Int ?: return null,
            values[INTENSITY_KEY] as? Int ?: return null)
        return snapshot.takeIf { it.valid() && it.target == currentTarget(values) &&
            it.intensity == SmartAdaptIntensity.level(values).value }
    }

    private fun SharedPreferences.Editor.writeSnapshot(snapshot: Snapshot): SharedPreferences.Editor =
        putInt(SCHEMA_KEY, SmartAdaptProtocol.SNAPSHOT_SCHEMA).putInt(TARGET_KEY, snapshot.target)
            .putInt(LOW_KEY, snapshot.low).putInt(MID_KEY, snapshot.mid).putInt(HIGH_KEY, snapshot.high)
            .putInt(WIDTH_KEY, snapshot.width).putInt(INTENSITY_KEY, snapshot.intensity)

    /** Only add the new level; retain every existing control and enable choice. */
    @Synchronized fun migrate(preferences: SharedPreferences) {
        val values = preferences.all
        val legacy = legacySnapshot(values)
        if (SmartAdaptIntensity.KEY in values && legacy == null) return
        val edit = preferences.edit()
        if (SmartAdaptIntensity.KEY !in values) edit.putInt(SmartAdaptIntensity.KEY, legacy?.intensity ?: SmartAdaptIntensity.DEFAULT)
        if (legacy != null) edit.writeSnapshot(legacy)
        edit.apply()
    }

    /** Normalize complete old profile snapshots while retaining all other imported choices. */
    fun upgradeImported(values: Map<String, Any>): Map<String, Any> {
        val result = values.toMutableMap()
        val legacy = legacySnapshot(values)
        if (SmartAdaptIntensity.KEY !in result) result[SmartAdaptIntensity.KEY] = legacy?.intensity ?: SmartAdaptIntensity.DEFAULT
        if (legacy != null) {
            result[SCHEMA_KEY] = SmartAdaptProtocol.SNAPSHOT_SCHEMA
            result[INTENSITY_KEY] = legacy.intensity
        }
        return result
    }

    fun patch(values: Map<String, *>, packet: IntArray) {
        read(values)?.let {
            packet[110] = 1
            packet[111] = it.low; packet[112] = it.mid; packet[113] = it.high
            packet[114] = it.width; packet[115] = SmartAdaptProtocol.SNAPSHOT_SCHEMA; packet[116] = it.target
            packet[117] = it.intensity; packet[SmartAdaptIntensity.WORD_INDEX] = it.intensity
        }
        packet[SmartAdaptProtocol.ROUTE_INDEX] = 0
    }

    fun save(preferences: SharedPreferences, snapshot: Snapshot) {
        require(snapshot.valid())
        preferences.edit().writeSnapshot(snapshot).apply()
    }

    fun clear(preferences: SharedPreferences) {
        if (PREFERENCE_KEYS.none { preferences.contains(it) }) return
        preferences.edit().apply { PREFERENCE_KEYS.forEach(::remove) }.apply()
    }

    fun clearInvalid(preferences: SharedPreferences) {
        if (read(preferences.all) == null) clear(preferences)
    }

    fun snapshot(runtime: ExpansionEffectsRuntime, expected: IntArray): Snapshot? {
        if (expected.size != SmartAdaptProtocol.WORDS || expected[1] != 1 || expected[3] <= 0 ||
            runtime.kind != ExpansionEffectsKind.SMART_ADAPT || !runtime.enabled || !runtime.engineEnabled ||
            runtime.parameterHash != ExpansionEffectsParameters.hash(ExpansionEffectsKind.SMART_ADAPT, expected) ||
            runtime.meters.size < 30 || runtime.meter(23) != 1f || runtime.meter(26) != 1f ||
            runtime.meter(0) !in listOf(3f, 4f, 6f) ||
            runtime.meter(27) != expected[2] / 1000f ||
            runtime.meter(28) != expected[SmartAdaptIntensity.WORD_INDEX] / 1000f ||
            runtime.meter(29) != SmartAdaptIntensity.level(expected[SmartAdaptIntensity.WORD_INDEX]).multiplier.toFloat() ||
            runtime.meter(22) != expected[SmartAdaptProtocol.ROUTE_INDEX] / 1000f) return null
        val targets = (7..10).map(runtime::meter)
        if (targets.any { !it.isFinite() } || targets.take(3).any { it !in -12f..12f } || targets[3] !in 0f..0.6f) return null
        return Snapshot(expected[2], (targets[0] * 1000).roundToInt(), (targets[1] * 1000).roundToInt(),
            (targets[2] * 1000).roundToInt(), (targets[3] * 1000).roundToInt(), expected[SmartAdaptIntensity.WORD_INDEX]).takeIf { it.valid() }
    }
}
