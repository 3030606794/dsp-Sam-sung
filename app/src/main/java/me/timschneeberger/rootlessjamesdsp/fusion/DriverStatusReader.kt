package me.timschneeberger.rootlessjamesdsp.fusion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/** A read-only snapshot of existing effects; unavailable values are never replaced by defaults. */
class DriverStatusReader(private val sampleWindow: suspend () -> Unit = { delay(200) }) {
    interface Probe {
        fun integer(parameter: Int): Int?
        fun enabled(): Boolean?
        fun hasControl(): Boolean?
        fun descriptor(): Descriptor?
    }
    data class Descriptor(val name: String?, val implementor: String?)
    data class Source(val sessionId: Int, val packageName: String?, val uid: Int, val probe: Probe?)
    enum class Processing { ACTIVE, IDLE, UNKNOWN }
    data class Session(
        val source: Source, val connected: Boolean?, val enabled: Boolean?, val control: Boolean?,
        val processing: Processing, val values: Map<Int, Int?>, val descriptor: Descriptor?,
    )
    data class Snapshot(val output: String?, val serviceRunning: Boolean?, val sessions: List<Session>?) {
        val processing: Processing get() = when {
            sessions == null -> Processing.UNKNOWN
            sessions.any { it.processing == Processing.ACTIVE } -> Processing.ACTIVE
            sessions.any { it.processing == Processing.UNKNOWN } -> Processing.UNKNOWN
            else -> Processing.IDLE
        }
    }
    private data class Sample(val enabled: Boolean?, val control: Boolean?,
        val values: Map<Int, Int?>, val descriptor: Descriptor?)

    suspend fun read(output: String?, serviceRunning: Boolean?, sources: List<Source>?): Snapshot =
        withContext(Dispatchers.IO) {
            if (sources == null) return@withContext Snapshot(output, serviceRunning, null)
            val before = sources.map(::sample)
            if (sources.any { it.probe != null }) sampleWindow()
            val sessions = sources.mapIndexed { index, source ->
                val previous = before[index]
                val current = sample(source)
                val connected = when {
                    source.probe == null -> false
                    current.enabled != null || current.control != null || current.values.values.any { it != null } -> true
                    else -> null
                }
                val rate = current.values[HOST_RATE]
                val frames = current.values[PROCESSED_FRAMES]
                val previousFrames = previous.values[PROCESSED_FRAMES]
                val processing = when {
                    source.probe == null || current.enabled == false -> Processing.IDLE
                    current.enabled != true || previous.enabled != true || rate == null ||
                        previous.values[HOST_RATE] != rate || frames == null || previousFrames == null -> Processing.UNKNOWN
                    frames > previousFrames -> Processing.ACTIVE
                    frames == previousFrames -> Processing.IDLE
                    else -> Processing.UNKNOWN // A reset or wrap is not evidence of active audio.
                }
                Session(source, connected, current.enabled, current.control, processing, current.values, current.descriptor)
            }
            Snapshot(output, serviceRunning, sessions)
        }

    private fun sample(source: Source): Sample {
        val probe = source.probe ?: return Sample(null, null, emptyMap(), null)
        fun <T> safe(read: () -> T): T? = try { read() } catch (_: Exception) { null }
        val values = READ_ONLY_PARAMETERS.associateWith { parameter ->
            safe { probe.integer(parameter) }?.takeIf { value -> when (parameter) {
                HOST_RATE, INTERNAL_RATE -> value in 8000..768000
                PROTOCOL, PID -> value > 0
                PROCESSED_FRAMES, BUFFER_FRAMES, MAX_BUFFER_FRAMES -> value >= 0
                else -> true
            } }
        }
        return Sample(safe { probe.enabled() }, safe { probe.hasControl() }, values, safe { probe.descriptor() })
    }

    companion object {
        const val BUFFER_FRAMES = 19999
        const val MAX_BUFFER_FRAMES = 20000
        const val INTERNAL_RATE = 20001
        const val PID = 20002
        const val PROTOCOL = 131080
        const val CAPABILITIES = 131081
        const val HOST_RATE = 131082
        const val PROCESSED_FRAMES = 131083
        const val EXTENDED_CAPABILITIES = 131234
        // 19998 changes initialization state when read; it must never be queried here.
        val READ_ONLY_PARAMETERS = setOf(BUFFER_FRAMES, MAX_BUFFER_FRAMES, INTERNAL_RATE, PID,
            PROTOCOL, CAPABILITIES, HOST_RATE, PROCESSED_FRAMES, EXTENDED_CAPABILITIES)
    }
}

object DriverStatusText {
    private const val UNKNOWN = "未知 / 未提供"
    private fun text(value: String?) = value?.takeIf { it.isNotBlank() } ?: UNKNOWN
    private fun flag(value: Boolean?, yes: String, no: String) = when (value) { true -> yes; false -> no; null -> UNKNOWN }
    private fun number(value: Int?, suffix: String = "") = value?.let { "$it$suffix" } ?: UNKNOWN
    private fun bits(value: Int?) = value?.let { "0x${Integer.toHexString(it).uppercase(Locale.ROOT).padStart(8, '0')}" } ?: UNKNOWN
    private fun processing(value: DriverStatusReader.Processing) = when (value) {
        DriverStatusReader.Processing.ACTIVE -> "处理中"
        DriverStatusReader.Processing.IDLE -> "未处理"
        DriverStatusReader.Processing.UNKNOWN -> UNKNOWN
    }
    private fun rate(value: Int?) = value?.let {
        String.format(Locale.ROOT, "%.3f", it / 1000.0).trimEnd('0').trimEnd('.') + " kHz"
    } ?: UNKNOWN

    fun format(
        status: DriverStatusReader.Snapshot,
        installation: DriverInstallationInfo? = null,
        applicationVersion: String? = null,
    ): String = buildString {
        appendLine("驱动架构：${text(installation?.architecture)}")
        appendLine("驱动版本：${text(installation?.driverVersion)}")
        appendLine("应用版本：${text(applicationVersion)}")
        appendLine("当前输出：${text(status.output)}")
        appendLine("音效服务：${flag(status.serviceRunning, "运行中", "未运行")}")
        val sessions = status.sessions
        val connection = when {
            sessions == null -> null
            sessions.any { it.connected == true } -> true
            sessions.any { it.connected == null } -> null
            else -> false
        }
        appendLine("驱动连接：${flag(connection, "已连接", "未连接")}")
        appendLine("连接会话：${sessions?.count { it.connected == true } ?: UNKNOWN} / ${sessions?.size ?: UNKNOWN}")
        appendLine("实际处理：${processing(status.processing)}")
        val activeRates = sessions.orEmpty().filter { it.processing == DriverStatusReader.Processing.ACTIVE }
            .mapNotNull { it.values[DriverStatusReader.HOST_RATE] }.distinct().sorted()
        appendLine("处理采样率：${if (activeRates.isNotEmpty()) activeRates.joinToString(" / ") { rate(it) }
            else if (status.processing == DriverStatusReader.Processing.IDLE) "未处理" else UNKNOWN}")
        if (sessions.isNullOrEmpty()) {
            append(if (sessions == null) "会话详情：$UNKNOWN" else "当前没有已连接的音效会话。")
        }
        sessions.orEmpty().forEachIndexed { index, session ->
            val values = session.values
            appendLine()
            appendLine("会话 ${index + 1} · ID ${session.source.sessionId}")
            appendLine("来源应用：${text(session.source.packageName)}")
            appendLine("应用 UID：${number(session.source.uid.takeIf { it >= 0 })}")
            appendLine("连接：${flag(session.connected, "已连接", "未连接")}")
            appendLine("音效启用：${flag(session.enabled, "已启用", "未启用")}")
            appendLine("控制权：${flag(session.control, "已获得", "未获得")}")
            appendLine("实际处理：${processing(session.processing)}")
            appendLine("宿主采样率：${rate(values[DriverStatusReader.HOST_RATE])}")
            appendLine("引擎内部采样率：${rate(values[DriverStatusReader.INTERNAL_RATE])}")
            appendLine("处理帧计数：${number(values[DriverStatusReader.PROCESSED_FRAMES])}")
            appendLine("协议：${number(values[DriverStatusReader.PROTOCOL])}")
            appendLine("基础能力：${bits(values[DriverStatusReader.CAPABILITIES])}")
            appendLine("扩展能力：${bits(values[DriverStatusReader.EXTENDED_CAPABILITIES])}")
            appendLine("音频服务 PID：${number(values[DriverStatusReader.PID])}")
            appendLine("缓冲帧：${number(values[DriverStatusReader.BUFFER_FRAMES])}")
            appendLine("最大缓冲帧：${number(values[DriverStatusReader.MAX_BUFFER_FRAMES])}")
            appendLine("描述符名称：${text(session.descriptor?.name)}")
            appendLine("实现者：酷安vico耀")
        }
    }.trimEnd()
}
