package me.timschneeberger.rootlessjamesdsp.session.root

data class ActivePlaybackSession(
    val sessionId: Int,
    val uid: Int,
    val pid: Int,
    val packageName: String
)

/** Pure parsing of live output-track tables; session references and effect chains are not playback. */
object ActivePlaybackSessionParser {
    data class Track(val sessionId: Int, val pid: Int, val streamType: Int, val usage: Int)
    data class ProcessIdentity(val pid: Int, val uid: Int, val processName: String)

    private val outputThread = Regex("^\\s*Output thread .+type \\d+.*", RegexOption.IGNORE_CASE)
    private val otherThread = Regex("^\\s*(?:Input|Record|Mmap|Playback)?\\s*[Tt]hread .+type \\d+.*")
    private val trackCount = Regex("^\\s*\\d+ [Tt]racks of which (\\d+) are active\\s*$")
    private val hexadecimalUsage = Regex("^(?:0[xX])?[0-9a-fA-F]+$")

    /** null means an unavailable/truncated/unrecognized dump; empty means no active media tracks. */
    fun tracks(dump: String): List<Track>? {
        var sawOutput = false
        var inOutput = false
        var standby = true
        var expectedActive = 0
        var parsedActive = 0
        var columns: List<String>? = null
        val found = mutableListOf<Track>()

        fun completeTable(): Boolean = expectedActive == 0 || parsedActive >= expectedActive

        for (line in dump.lineSequence()) {
            if (outputThread.matches(line) || otherThread.matches(line)) {
                if (!completeTable()) return null
                inOutput = outputThread.matches(line)
                sawOutput = sawOutput || inOutput
                standby = true
                expectedActive = 0
                parsedActive = 0
                columns = null
                continue
            }
            if (!inOutput) continue
            if (line.trim().startsWith("Standby:", true)) {
                standby = !line.substringAfter(':').trim().equals("no", true)
                continue
            }
            trackCount.matchEntire(line)?.let {
                if (!completeTable()) return null
                expectedActive = if (standby) 0 else it.groupValues[1].toIntOrNull() ?: return null
                parsedActive = 0
                columns = null
            }
            if (line.contains("Active") && line.contains("Client") && line.contains("Session") &&
                line.contains("SRate") && line.contains("Usg")) {
                columns = line.trim().replace(Regex("Port\\s+Id"), "PortId")
                    .replace(Regex("Chn\\s+mask"), "ChnMask").split(Regex("\\s+"))
                continue
            }
            val header = columns ?: continue
            val cells = line.trim().split(Regex("\\s+"))
            val activeIndex = cells.indexOfFirst { it.equals("yes", true) || it.equals("no", true) }
            // Type may be absent, a single token, or Vivo's two-token FAST prefix (F1 S).
            if (activeIndex !in 1..3) {
                if (line.isNotBlank()) columns = null
                continue
            }
            val shift = activeIndex - header.indexOf("Active")
            fun value(column: String): String? {
                val index = header.indexOf(column)
                return if (index >= 0) cells.getOrNull(index + shift) else null
            }
            fun integer(column: String): Int? = value(column)?.toIntOrNull()
            if (!cells[activeIndex].equals("yes", true) || standby) continue
            parsedActive++
            val id = integer("Id") ?: return null
            val sid = integer("Session") ?: return null
            val pid = integer("Client") ?: return null
            val stream = integer("ST") ?: return null
            val usageText = value("Usg") ?: return null
            // Preserve decimal dumps while accepting the alphabetic hex usages emitted by Vivo.
            val usage = usageText.toIntOrNull() ?: usageText
                .takeIf { hexadecimalUsage.matches(it) }
                ?.removePrefix("0x")?.removePrefix("0X")?.toIntOrNull(16) ?: return null
            if (id <= 0 || sid <= 0 || pid <= 0 || stream < 0) continue
            // Music, media/game usage, and unknown-usage music are eligible. Never notifications/calls.
            if (usage != 1 && usage != 14 && !(usage == 0 && stream == 3)) continue
            found.add(Track(sid, pid, stream, usage))
        }
        return if (sawOutput && completeTable()) found.distinct() else null
    }

    fun processes(dump: String): Map<Int, ProcessIdentity>? {
        val lines = dump.lineSequence().filter { it.isNotBlank() }.toList()
        val header = lines.firstOrNull()?.trim()?.split(Regex("\\s+")) ?: return null
        val pidIndex = header.indexOf("PID")
        val uidIndex = header.indexOf("UID")
        val nameIndex = header.indexOf("NAME")
        if (pidIndex < 0 || uidIndex < 0 || nameIndex < 0) return null
        val result = mutableMapOf<Int, ProcessIdentity>()
        for (line in lines.drop(1)) {
            val fields = line.trim().split(Regex("\\s+"))
            val pid = fields.getOrNull(pidIndex)?.toIntOrNull() ?: continue
            val uid = fields.getOrNull(uidIndex)?.toIntOrNull() ?: continue
            val name = fields.getOrNull(nameIndex) ?: continue
            if (pid > 0 && uid >= 0) result[pid] = ProcessIdentity(pid, uid, name)
        }
        return result
    }

    fun sessions(
        audioFlinger: String,
        processDump: String,
        ownUid: Int,
        ownPackageName: String,
        packageName: (ProcessIdentity) -> String = { it.processName.substringBefore(':') }
    ): List<ActivePlaybackSession>? {
        val active = tracks(audioFlinger) ?: return null
        if (active.isEmpty()) return emptyList()
        val identities = processes(processDump) ?: return null
        val result = mutableMapOf<Int, ActivePlaybackSession>()
        for (track in active) {
            // A vanished PID is a racing snapshot, not proof that the player became idle.
            val process = identities[track.pid] ?: return null
            if (process.uid == ownUid) continue
            val pkg = packageName(process)
            if (pkg == ownPackageName) continue
            val entry = ActivePlaybackSession(track.sessionId, process.uid, track.pid, pkg)
            val previous = result[track.sessionId]
            if (previous != null && previous.uid != entry.uid) return null
            result[track.sessionId] = entry
        }
        return result.values.toList()
    }
}
