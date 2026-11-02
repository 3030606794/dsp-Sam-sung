package me.timschneeberger.rootlessjamesdsp.liveprog

import timber.log.Timber
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max
import kotlin.math.min

open class EelNumberRangeProperty<T:Number>(
    key: String,
    description: String,
    var default: T?,
    var value: T,
    val minimum: T,
    val maximum: T,
    val step: T
) : EelBaseProperty(key, description) {
    init {
        if(minimum.toDouble() >= maximum.toDouble()) {
            throw NumberFormatException("Minimum must be smaller than the maximum (key=$key)")
        }
        if (!step.toDouble().isFinite() || step.toDouble() <= 0.0 ||
            !minimum.toDouble().isFinite() || !maximum.toDouble().isFinite()) {
            throw NumberFormatException("Range and positive step must be finite (key=$key)")
        }

        value = validateRange(value)
        if (default?.toDouble()?.isFinite() == false) throw NumberFormatException("Default must be finite (key=$key)")
    }

    @Suppress("UNCHECKED_CAST")
    fun validateRange(input: T): T {
        if (!input.toDouble().isFinite()) throw NumberFormatException("Parameter must be finite (key=$key)")
        return when (input) {
            is Double -> min(max(minimum.toDouble(), input.toDouble()), maximum.toDouble()) as T
            is Int -> min(max(minimum.toInt(), input.toInt()), maximum.toInt()) as T
            is Float -> min(max(minimum.toFloat(), input.toFloat()), maximum.toFloat()) as T
            else -> throw IllegalArgumentException("unsupported type")
        }
    }

    fun handleAsInt(): Boolean {
        return precision == 0
    }

    /** The declaration controls precision, including fractional range offsets. */
    val precision: Int
        get() = maxOf(decimal(step).scale(), decimal(minimum).scale(), decimal(maximum).scale(), decimal(value).scale(), 0)

    fun normalize(input: Float): Float {
        require(input.isFinite()) { "Parameter must be finite" }
        val lower = decimal(minimum)
        val upper = decimal(maximum)
        val increment = decimal(step)
        val requested = BigDecimal(input.toString()).max(lower).min(upper)
        val ticks = requested.subtract(lower).divide(increment, 0, RoundingMode.HALF_UP)
        val lastTick = upper.subtract(lower).divide(increment, 0, RoundingMode.FLOOR)
        return lower.add(increment.multiply(ticks.min(lastTick))).toFloat()
    }

    fun landsOnStep(input: Float): Boolean = input.isFinite() &&
        kotlin.math.abs(normalize(input).toDouble() - input.toDouble()) <= maxOf(1e-7, kotlin.math.abs(input.toDouble()) * 1e-7)

    private fun decimal(number: Number) = BigDecimal(number.toString()).stripTrailingZeros()

    override fun hasDefault(): Boolean {
        return default != null
    }

    override fun isDefault(): Boolean {
        if (!hasDefault())
            return false

        return default!!.toDouble() == value.toDouble()
    }

    override fun restoreDefaults() {
        default?.let { value = it }
    }

    override fun valueAsString(): String {
        require(value.toDouble().isFinite()) { "Parameter must be finite" }
        return decimal(value).toPlainString()
    }

    override fun manipulateProperty(contents: String): String? {
        return replaceVariable(key, valueAsString(), contents)
    }

    override fun toString(): String {
        return "key=$key; desc=$description; value=$value; handleAsInt=${handleAsInt()}; default=$default; min=$minimum; max=$maximum; step=$step"
    }

    companion object : IPropertyCompanion {
        private fun matchVariable(key: String, contents: String): MatchResult? {
            val regex = """(?<![\w.])${Regex.escape(key)}\s*=\s*($NUMBER)\s*;""".toRegex()
            return regex.find(maskComments(contents))
        }

        private fun maskComments(source: String): String {
            val masked = source.toCharArray()
            var index = 0
            while (index < source.length) {
                val line = source.startsWith("//", index)
                val block = source.startsWith("/*", index)
                val string = source[index] == '"'
                if (!line && !block && !string) { index++; continue }
                var end = index + if (string) 1 else 2
                if (line) {
                    while (end < source.length && source[end] != '\n') end++
                } else if (block) {
                    while (end < source.length && !source.startsWith("*/", end)) end++
                    end = minOf(source.length, end + 2)
                } else {
                    while (end < source.length) {
                        if (source[end] == '\\') { end += 2; continue }
                        if (source[end++] == '"') break
                    }
                    end = minOf(source.length, end)
                }
                for (position in index until end) if (masked[position] != '\n' && masked[position] != '\r') masked[position] = ' '
                index = end
            }
            return String(masked)
        }

        fun findVariable(key: String, contents: String): Float? {
            val match = matchVariable(key, contents)
            return match?.groups?.get(1)?.value?.toFloatOrNull()
        }

        fun replaceVariable(key: String, replacement: String, contents: String): String? {
            val match = matchVariable(key, contents)
            match ?: return null

            return match.groups[1]?.range?.let {
                contents.replaceRange(it, replacement)
            }
        }

        private const val NUMBER = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?"
        override val definitionRegex =
            """(?<var>[A-Za-z_][A-Za-z_0-9]*):(?<def>$NUMBER)?<(?<min>$NUMBER),(?<max>$NUMBER),?(?<step>$NUMBER)?>(?<desc>[^\n]+)""".toRegex()

        override fun parse(line: String, contents: String): EelBaseProperty? {
            val matchRange = definitionRegex.find(line)
            val groupsRange = matchRange?.groups
            groupsRange ?: return null

            val key = groupsRange[1]?.value
            val def = groupsRange[2]?.value
            val min = groupsRange[3]?.value
            val max = groupsRange[4]?.value
            val step = groupsRange[5]?.value ?: "0.1"
            val desc = groupsRange[6]?.value?.trim()

            if (key == null || desc == null || min == null || max == null) {
                return null
            }

            val current = findVariable(key, contents)
            if (current == null) {
                Timber.e("Failed to find current value of number range parameter (key=$key)")
                return null
            }

            try {
                return EelNumberRangeProperty(
                    key,
                    desc,
                    def?.toFloatOrNull(),
                    current,
                    min.toFloat(),
                    max.toFloat(),
                    step.toFloatOrNull() ?: 0.1f
                ).also { Timber.d("Found number range property: $it") }
            } catch (ex: NumberFormatException) {
                Timber.e("Failed to parse number range parameter (key=$key)")
                Timber.e(ex)
            }
            return null
        }
    }
}
