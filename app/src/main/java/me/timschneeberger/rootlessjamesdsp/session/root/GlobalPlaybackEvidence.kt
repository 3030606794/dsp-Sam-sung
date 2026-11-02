package me.timschneeberger.rootlessjamesdsp.session.root

/** AudioFlinger evidence only; an advertised USB format or an OPEN broadcast is not playback. */
data class GlobalPlaybackEvidence(val outputs: List<Output>) {
    data class GlobalEffect(val id: Int, val enabled: Boolean?, val suspended: Boolean?, val active: Boolean? = null)
    data class Output(val identity: String, val activeMediaSessions: Set<Int>, val effects: List<GlobalEffect>)
    enum class Reason { SUSPENDED, DIFFERENT_OUTPUT }

    fun processingIssue(activeSessions: Set<Int>, globalEffectId: Int?): Reason? {
        if (globalEffectId == null || activeSessions.isEmpty()) return null
        val playing = outputs.filter { output -> output.activeMediaSessions.any { it in activeSessions } }
        if (playing.isEmpty()) return null
        val global = outputs.mapNotNull { output ->
            output.effects.firstOrNull { it.id == globalEffectId &&
                (it.enabled == true || it.active == true || it.suspended == true) }
                ?.let { output.identity to it }
        }
        // A missing or unrecognized chain is unknown, never permission to guess a fallback.
        if (global.isEmpty()) return null
        if (global.any { it.second.suspended == true }) return Reason.SUSPENDED
        if (global.any { it.second.suspended == null }) return null
        if (playing.any { output -> global.none { it.first == output.identity } })
            return Reason.DIFFERENT_OUTPUT
        return null
    }

    companion object {
        private const val FUSION_UUID = "30306067-9400-4d53-9000-000000000001"
        private val outputHeader = Regex("^\\s*Output thread (\\S+),.+type \\d+.*", RegexOption.IGNORE_CASE)
        private val threadHeader = Regex("^\\s*(?:Output|Input|Record|Mmap|Playback)?\\s*[Tt]hread .+type \\d+.*")
        private val chainHeader = Regex("^\\s*\\d+ effects for session (\\d+)\\s*$", RegexOption.IGNORE_CASE)
        private val effectHeader = Regex("^\\s*Effect ID (\\d+):\\s*$", RegexOption.IGNORE_CASE)
        private val uuidLine = Regex("^\\s*- UUID: ([a-f0-9-]+)\\s*$", RegexOption.IGNORE_CASE)

        fun parse(dump: String): GlobalPlaybackEvidence? {
            val outputs = mutableListOf<Output>()
            var identity: String? = null
            val section = StringBuilder()
            var failed = false
            fun finishOutput() {
                val id = identity ?: return
                val text = section.toString()
                val tracks = ActivePlaybackSessionParser.tracks(text)
                if (tracks == null) failed = true
                else outputs += Output(id, tracks.map { it.sessionId }.toSet(), globalEffects(text))
                identity = null
                section.setLength(0)
            }
            for (line in dump.lineSequence()) {
                // Deleted/historical threads must never authorize attachment to an old SID.
                if (line.trimStart().startsWith("Historical Thread Log", true)) {
                    finishOutput()
                    break
                }
                val output = outputHeader.matchEntire(line)
                if (output != null || threadHeader.matches(line)) {
                    finishOutput()
                    identity = output?.groupValues?.get(1)
                }
                if (identity != null) section.append(line).append('\n')
            }
            finishOutput()
            return if (failed || outputs.isEmpty()) null else GlobalPlaybackEvidence(outputs)
        }

        private fun globalEffects(section: String): List<GlobalEffect> {
            val effects = mutableListOf<GlobalEffect>()
            var chainSession: Int? = null
            var effectId: Int? = null
            var uuid: String? = null
            var stateSession: Int? = null
            var enabled: Boolean? = null
            var suspended: Boolean? = null
            var active: Boolean? = null
            var stateColumns: List<String>? = null
            fun finishEffect() {
                val id = effectId
                if (id != null && chainSession == 0 && stateSession == 0 && uuid.equals(FUSION_UUID, true))
                    effects += GlobalEffect(id, enabled, suspended, active)
                effectId = null; uuid = null; stateSession = null
                enabled = null; suspended = null; active = null; stateColumns = null
            }
            fun flag(value: String?) = when (value?.lowercase()) { "y" -> true; "n" -> false; else -> null }
            for (line in section.lineSequence()) {
                chainHeader.matchEntire(line)?.let {
                    finishEffect()
                    chainSession = it.groupValues[1].toIntOrNull()
                }
                effectHeader.matchEntire(line)?.let {
                    finishEffect()
                    effectId = it.groupValues[1].toIntOrNull()
                }
                if (effectId == null) continue
                uuidLine.matchEntire(line)?.let { uuid = it.groupValues[1] }
                val cells = line.trim().trimEnd(':').split(Regex("\\s+"))
                if (cells.firstOrNull() == "Session" && "Enabled" in cells && "Suspended" in cells) {
                    stateColumns = cells
                    continue
                }
                val columns = stateColumns ?: continue
                if (line.isBlank()) continue
                stateColumns = null
                fun cell(name: String) = columns.indexOf(name).takeIf { it >= 0 }?.let { cells.getOrNull(it) }
                stateSession = cell("Session")?.toIntOrNull()
                enabled = flag(cell("Enabled"))
                suspended = flag(cell("Suspended"))
                // Enabled is AudioFlinger's policy flag; state 003 independently means ACTIVE.
                active = cell("State")?.toIntOrNull()?.let { it == 3 }
            }
            finishEffect()
            return effects
        }
    }
}
