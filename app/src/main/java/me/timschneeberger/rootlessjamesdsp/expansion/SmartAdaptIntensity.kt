package me.timschneeberger.rootlessjamesdsp.expansion

/** Shared display/wire definitions; native telemetry remains authoritative for the actual limits. */
object SmartAdaptIntensity {
    const val KEY = "p16"
    const val DEFAULT = 3000
    const val WORD_INDEX = 18
    data class Level(val value: Int, val label: String, val multiplier: Double)
    val levels = listOf(
        Level(0, "轻柔", 1.0), Level(1000, "标准", 1.75), Level(2000, "增强", 2.75),
        Level(3000, "强劲", 4.0), Level(4000, "极强", 6.0),
    )
    fun isValid(value: Int) = levels.any { it.value == value }
    fun level(value: Int): Level = levels.firstOrNull { it.value == value } ?: levels[3]
    fun level(values: Map<String, *>): Level = level(values[KEY] as? Int ?: DEFAULT)
}
