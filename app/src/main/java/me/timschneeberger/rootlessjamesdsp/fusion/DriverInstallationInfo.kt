package me.timschneeberger.rootlessjamesdsp.fusion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.flavor.RootShellImpl
import me.timschneeberger.rootlessjamesdsp.utils.RootAccessStatus

/** Optional, read-only installed metadata. Missing evidence always remains unknown. */
data class DriverInstallationInfo(
    val driverVersion: String? = null,
    val architecture: String? = null,
) {
    companion object {
        private const val MODULE_BEGIN = "__DSP_INSTALLED_MODULE_BEGIN__"
        private const val MODULE_END = "__DSP_INSTALLED_MODULE_END__"
        private const val ELF_BEGIN = "__DSP_LOADED_PROCESS_ELF__="
        private const val ELF_END = "__DSP_LOADED_PROCESS_ELF_END__"

        suspend fun read(snapshot: DriverStatusReader.Snapshot): DriverInstallationInfo = withContext(Dispatchers.IO) {
            // Viewing status must not initiate a new authorization request.
            if (RootAccessStatus.state.value != RootAccessStatus.State.AVAILABLE)
                return@withContext DriverInstallationInfo()
            val pids = snapshot.sessions.orEmpty().filter { it.connected == true }
                .mapNotNull { it.values[DriverStatusReader.PID]?.takeIf { pid -> pid > 0 } }
                .distinct().take(8)
            val script = buildString {
                appendLine("printf '$MODULE_BEGIN\n'")
                appendLine("if [ -r /data/adb/modules/dsp_fusion/module.prop ] && [ ! -e /data/adb/modules/dsp_fusion/remove ]; then")
                appendLine("  sed -n '1,40p' /data/adb/modules/dsp_fusion/module.prop")
                appendLine("fi")
                appendLine("printf '\n$MODULE_END\n'")
                for (pid in pids) {
                    // A positive PID came from the existing driver GET, never from user input.
                    appendLine("if grep -Eq '/libdspfusion(_aidl)?[.]so([[:space:]]+[(]deleted[)])?$' /proc/$pid/maps 2>/dev/null; then")
                    appendLine("  printf '$ELF_BEGIN$pid\n'")
                    // The mapped driver has the same ELF ABI as its actual hosting process.
                    appendLine("  od -An -v -tx1 -N20 /proc/$pid/exe 2>/dev/null")
                    appendLine("  printf '\n$ELF_END\n'")
                    appendLine("fi")
                }
            }
            try {
                // Without a system timeout we leave metadata unknown instead of running an
                // unbounded privileged command. The audio-status snapshot is already visible.
                val quoted = "'" + script.replace("'", "'\"'\"'") + "'"
                val result = RootShellImpl.execute(
                    "if command -v timeout >/dev/null 2>&1; then timeout 2 sh -c $quoted; else exit 127; fi",
                )
                if (result.success) parse(result.output) else DriverInstallationInfo()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DriverInstallationInfo()
            }
        }

        private fun parse(lines: List<String>): DriverInstallationInfo {
            val start = lines.indexOf(MODULE_BEGIN)
            val end = lines.indexOf(MODULE_END)
            val properties = if (start >= 0 && end > start) {
                lines.subList(start + 1, end).mapNotNull { line ->
                    if (line.startsWith('#') || '=' !in line) return@mapNotNull null
                    line.substringBefore('=').trim() to line.substringAfter('=').trim()
                }.toMap()
            } else emptyMap()
            val version = properties.takeIf { it["id"] == "dsp_fusion" }?.get("version")
                ?.takeIf { it.isNotBlank() && it.length <= 120 && it.none(Char::isISOControl) }
            val architectures = linkedSetOf<String>()
            var elf: MutableList<String>? = null
            for (line in lines) {
                when {
                    line.startsWith(ELF_BEGIN) -> elf = mutableListOf()
                    line == ELF_END -> {
                        elf?.let(::elfArchitecture)?.let(architectures::add)
                        elf = null
                    }
                    else -> elf?.add(line)
                }
            }
            return DriverInstallationInfo(version, architectures.takeIf { it.isNotEmpty() }?.joinToString(" / "))
        }

        private fun elfArchitecture(lines: List<String>): String? {
            val words = lines.flatMap { it.trim().split(Regex("\\s+")) }.filter { it.isNotEmpty() }
            if (words.size != 20 || words.any { it.length != 2 }) return null
            val bytes = words.map { it.toIntOrNull(16) ?: return null }
            if (bytes.take(4) != listOf(0x7f, 0x45, 0x4c, 0x46)) return null
            val bits = when (bytes[4]) { 1 -> 32; 2 -> 64; else -> return null }
            val machine = when (bytes[5]) {
                1 -> bytes[18] or (bytes[19] shl 8)
                2 -> (bytes[18] shl 8) or bytes[19]
                else -> return null
            }
            return when {
                machine == 183 && bits == 64 -> "arm64-v8a（64位）"
                machine == 40 && bits == 32 -> "armeabi-v7a（32位）"
                machine == 62 && bits == 64 -> "x86_64（64位）"
                machine == 3 && bits == 32 -> "x86（32位）"
                else -> null
            }
        }
    }
}
