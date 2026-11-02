package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspRemoteEngine
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import me.timschneeberger.rootlessjamesdsp.session.root.GlobalPlaybackEvidence

/** Collect only visible telemetry. Controller/native locks never belong to the UI thread. */
object ProfessionalRuntimeMonitor {
    enum class Module {
        BASS, HIGH_FREQUENCY, FIELD, DIFF, CONVOLUTION, WIDTH, CROSSFEED, PRESET_CROSSFEED,
        REVERB, ROOM_REVERB, TUBE, ANALOG, TIMBRE, DYNAMIC, COMPANDER, TONE, DDC, SURROUND;

        companion object {
            fun forPreferences(xml: Int): Set<Module> = when (xml) {
                R.xml.dsp_bass_preferences -> setOf(BASS)
                R.xml.dsp_tube_preferences -> setOf(TIMBRE)
                R.xml.dsp_reverb_preferences -> setOf(ROOM_REVERB)
                R.xml.dsp_crossfeed_preferences -> setOf(PRESET_CROSSFEED)
                R.xml.dsp_convolver_preferences -> setOf(CONVOLUTION)
                R.xml.dsp_compander_preferences -> setOf(COMPANDER)
                R.xml.dsp_stereowide_preferences -> setOf(WIDTH)
                R.xml.dsp_fusion_tone_preferences -> setOf(TONE)
                R.xml.dsp_ddc_preferences -> setOf(DDC)
                else -> emptySet()
            }

            fun forViper(key: String): Set<Module> = when (key) {
                "v_bass" -> setOf(BASS)
                "v_clarity", "v_spectrum" -> setOf(HIGH_FREQUENCY)
                "v_field" -> setOf(FIELD)
                "v_diff" -> setOf(DIFF)
                "v_cure" -> setOf(CROSSFEED)
                "v_reverb" -> setOf(REVERB)
                "v_tube" -> setOf(TUBE)
                "v_analog" -> setOf(ANALOG)
                "v_dynamic" -> setOf(DYNAMIC)
                else -> emptySet()
            }
        }
    }

    private val homeModules = setOf(Module.BASS, Module.HIGH_FREQUENCY)
    data class BassSample(val elapsedRealtimeMs: Long, val streamIdentity: Any?,
        val bass: BassRuntimeStatus.Snapshot, val output: OutputRuntimeStatus.Snapshot)
    data class ClaritySample(val elapsedRealtimeMs: Long, val streamIdentity: Any?,
        val reading: HighFrequencyController.Reading?, val output: OutputRuntimeStatus.Snapshot)
    private class BassMonitorClient(val onSample: (BassSample) -> Unit) {
        val tracker = BassRuntimeStatus.Tracker()
        var lastSampleTime: Long? = null
        fun isDue(now: Long) = lastSampleTime?.let { now - it >= BASS_MONITOR_INTERVAL_MS } ?: true
    }
    private data class Client(val modules: Set<Module>, val onOutput: (OutputRuntimeStatus.Snapshot) -> Unit = {},
        val bassMonitor: BassMonitorClient? = null)
    private data class TelemetrySnapshot(val output: OutputRuntimeStatus.Snapshot,
        val bass: List<BassRuntimeStatus.Reading>, val bassEnabled: Boolean, val elapsedRealtimeMs: Long)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clients = linkedMapOf<Any, Client>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val telemetryMutex = Mutex()
    @Volatile private var generation = 0L
    private var polling: Job? = null
    private var pendingStop: Job? = null
    private var lastOutput = OutputRuntimeStatus.Snapshot(false, OutputRuntimeStatus.Processing.UNKNOWN)
    // These trackers have a single writer under telemetryMutex on Dispatchers.IO.
    private val runtimeTracker = OutputRuntimeStatus.Tracker()
    private val bassRuntimeTracker = BassRuntimeStatus.Tracker()
    private const val BASS_MONITOR_INTERVAL_MS = 500L

    private data class RuntimeReadings(
        val output: OutputRuntimeStatus.Session,
        val bass: BassRuntimeStatus.Reading? = null,
        val highFrequency: HighFrequencyController.Reading? = null,
        val field: FieldController.Reading? = null,
        val diff: DiffController.Reading? = null,
        val convolution: ConvolutionController.Reading? = null,
        val width: WidthController.Reading? = null,
        val crossfeed: CrossfeedController.Reading? = null,
        val presetCrossfeed: PresetCrossfeedController.Reading? = null,
        val reverb: ReverbController.Reading? = null,
        val roomReverb: RoomReverbController.Reading? = null,
        val tube: TubeController.Reading? = null,
        val analog: AnalogController.Reading? = null,
        val timbre: TimbreController.Reading? = null,
        val dynamic: DynamicController.Reading? = null,
        val compander: CompanderController.Reading? = null,
        val toneAdvanced: ToneAdvancedController.Reading? = null,
        val ddc: DdcController.Reading? = null,
    )

    fun bind(owner: LifecycleOwner, context: Context,
        modules: Set<Module> = homeModules,
        onOutput: (OutputRuntimeStatus.Snapshot) -> Unit = {}) {
        val appContext = context.applicationContext
        val requested = modules.toSet()
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                pendingStop?.cancel()
                pendingStop = null
                clients[owner] = Client(requested, onOutput)
                generation++
                onOutput(lastOutput)
                wakeups.trySend(Unit)
                if (polling?.isActive != true) polling = scope.launch { poll(appContext) }
                try { awaitCancellation() }
                finally {
                    clients.remove(owner)
                    val stoppedGeneration = ++generation
                    wakeups.trySend(Unit)
                    if (clients.isEmpty()) pendingStop = scope.launch {
                        // Home pauses before the next activity resumes; retain that hand-off.
                        delay(250)
                        if (clients.isEmpty() && generation == stoppedGeneration) {
                            polling?.cancel()
                            polling = null
                            val cleared = withContext(Dispatchers.IO) {
                                telemetryMutex.withLock {
                                    if (generation != stoppedGeneration) false
                                    else { clearRuntime(appContext, stoppedGeneration); true }
                                }
                            }
                            if (cleared && generation == stoppedGeneration)
                                lastOutput = OutputRuntimeStatus.Snapshot(false, OutputRuntimeStatus.Processing.UNKNOWN)
                        }
                    }
                }
            }
        }
    }

    /** An optional page monitor, independent of both the effect switch and other telemetry users. */
    fun bindBassMonitor(owner: LifecycleOwner, context: Context, enabled: StateFlow<Boolean>,
        onSample: (BassSample) -> Unit) {
        val appContext = context.applicationContext
        val subscription = Any()
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                enabled.collectLatest { monitoring ->
                    if (!monitoring) return@collectLatest
                    // Every enable/resume starts with a new frame baseline, never a cached reading.
                    val monitor = BassMonitorClient(onSample)
                    pendingStop?.cancel()
                    pendingStop = null
                    clients[subscription] = Client(setOf(Module.BASS), bassMonitor = monitor)
                    generation++
                    wakeups.trySend(Unit)
                    if (polling?.isActive != true) polling = scope.launch { poll(appContext) }
                    try { awaitCancellation() }
                    finally {
                        clients.remove(subscription)
                        val stoppedGeneration = ++generation
                        wakeups.trySend(Unit)
                        if (clients.isEmpty()) pendingStop = scope.launch {
                            delay(250)
                            if (clients.isEmpty() && generation == stoppedGeneration) {
                                polling?.cancel()
                                polling = null
                                val cleared = withContext(Dispatchers.IO) {
                                    telemetryMutex.withLock {
                                        if (generation != stoppedGeneration) false
                                        else {
                                            // Stopping a monitor must not end another module's audition.
                                            runtimeTracker.clear()
                                            bassRuntimeTracker.clear()
                                            true
                                        }
                                    }
                                }
                                if (cleared && generation == stoppedGeneration)
                                    lastOutput = OutputRuntimeStatus.Snapshot(false, OutputRuntimeStatus.Processing.UNKNOWN)
                            }
                        }
                    }
                }
            }
        }
    }

    /** A one-off configuration read does not arm native monitoring or create a polling client. */
    suspend fun refreshHighFrequencyConfiguration(context: Context, publishCache: Boolean = true,
        isCurrent: () -> Boolean): List<HighFrequencyController.Reading> {
        val appContext = context.applicationContext
        val engines = withContext(Dispatchers.Main.immediate) {
            (appContext as? MainApplication)?.rootSessionDatabase?.sessionList?.toList()?.mapNotNull { (id, session) ->
                (session as? RemoteEffectSession)?.effect?.let { id to it }
            }.orEmpty()
        }
        return withContext(Dispatchers.IO) {
            telemetryMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return@withLock emptyList()
                val poll = HighFrequencyController.pollState()
                val readings = engines.mapNotNull { (id, engine) ->
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent()) return@withLock emptyList()
                    runCatching {
                        engine.outputRuntimeSession()?.copy(sessionId = id)?.let { engine.highFrequencyRuntimeReading(it) }
                    }.getOrNull()
                }
                currentCoroutineContext().ensureActive()
                if (isCurrent() && (if (publishCache) HighFrequencyController.publishPageRuntime(appContext, readings, poll)
                        else HighFrequencyController.pollState() == poll)) readings
                else emptyList()
            }
        }
    }

    /** Read-only page telemetry: ending this collector never clears controllers or auditions. */
    fun bindClarityMonitor(owner: LifecycleOwner, context: Context, enabled: StateFlow<Boolean>,
        spectrum: Boolean = false, publishCache: Boolean = true, onSample: (ClaritySample) -> Unit) {
        val appContext = context.applicationContext
        val effectKey = if (spectrum) "v_spectrum" else "v_clarity"
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                enabled.collectLatest { monitoring ->
                    if (!monitoring) return@collectLatest
                    val tracker = OutputRuntimeStatus.Tracker()
                    var previous = emptyMap<Any, OutputRuntimeStatus.Session>()
                    val ownerToken = Any()
                    val module = if (spectrum) NativeVisualizationTelemetry.Module.SPECTRUM
                        else NativeVisualizationTelemetry.Module.CLARITY
                    val registered = linkedSetOf<JamesDspRemoteEngine>()
                    try {
                      while (currentCoroutineContext().isActive && enabled.value) {
                        // Read the session map on Main; only its immutable snapshot reaches IO.
                        val application = appContext as? MainApplication
                        val mode = if (application?.isPlayerSessionMode == true)
                            OutputRuntimeStatus.Mode.PLAYER_SESSION else OutputRuntimeStatus.Mode.GLOBAL
                        val engines = application?.rootSessionDatabase?.sessionList?.toList()
                            ?.mapNotNull { (id, session) ->
                                (session as? RemoteEffectSession)?.effect?.let { id to it }
                            }.orEmpty()
                        val sample = withContext(Dispatchers.IO) {
                            telemetryMutex.withLock {
                                currentCoroutineContext().ensureActive()
                                // Guard a just-disabled effect before issuing native reads.
                                if (!enabled.value || !HighFrequencyController.preferences(appContext)
                                        .getBoolean(effectKey, false)) return@withLock null
                                val pollState = HighFrequencyController.pollState()
                                val currentEngines = engines.map { it.second }.toSet()
                                (registered - currentEngines).forEach {
                                    NativeVisualizationTelemetry.setEnabled(it, module, ownerToken, false)
                                }
                                registered.retainAll(currentEngines)
                                val readings = engines.mapNotNull { (id, engine) ->
                                    currentCoroutineContext().ensureActive()
                                    if (!enabled.value) return@withLock null
                                    runCatching {
                                        NativeVisualizationTelemetry.setEnabled(engine, module, ownerToken, true)
                                        registered.add(engine)
                                        engine.outputRuntimeSession()?.copy(sessionId = id)?.let { output ->
                                            RuntimeReadings(output,
                                                highFrequency = engine.highFrequencyRuntimeReading(output))
                                        }
                                    }.getOrNull()
                                }
                                currentCoroutineContext().ensureActive()
                                val output = tracker.update(readings.map { it.output }).copy(mode = mode)
                                val classified = readings.mapNotNull { item ->
                                    val reading = item.highFrequency ?: return@mapNotNull null
                                    val before = previous[item.output.identity]
                                    val frames = item.output.processedFrames?.takeIf { it >= 0 }
                                    val oldFrames = before?.processedFrames?.takeIf { it >= 0 }
                                    val processing = when {
                                        !item.output.enabled -> OutputRuntimeStatus.Processing.IDLE
                                        before == null || !before.enabled || frames == null || oldFrames == null ||
                                            before.hostRate != item.output.hostRate ||
                                            before.sessionId != item.output.sessionId -> OutputRuntimeStatus.Processing.UNKNOWN
                                        frames > oldFrames || (oldFrames >= 0x70000000 && frames <= 0x0fffffff) ->
                                            OutputRuntimeStatus.Processing.ACTIVE
                                        frames < oldFrames -> OutputRuntimeStatus.Processing.UNKNOWN
                                        else -> OutputRuntimeStatus.Processing.IDLE
                                    }
                                    reading.copy(processing = processing)
                                }
                                previous = readings.associate { it.output.identity to it.output }
                                currentCoroutineContext().ensureActive()
                                if (!enabled.value || !(if (publishCache) HighFrequencyController.publishPageRuntime(
                                        appContext, classified, pollState) else HighFrequencyController.pollState() == pollState))
                                    return@withLock null
                                // Never merge simultaneous streams or continue the prior stream's history.
                                val reading = classified.filter { it.enabled &&
                                    it.processing == OutputRuntimeStatus.Processing.ACTIVE }.singleOrNull()
                                val identity = reading?.let { value ->
                                    val windowGeneration = if (spectrum) value.visualization?.spectrumGeneration
                                        else value.visualization?.clarityGeneration
                                    windowGeneration?.let { value.identity to it } ?: value.identity
                                }
                                ClaritySample(SystemClock.elapsedRealtime(), identity, reading, output)
                            }
                        }
                        if (enabled.value && sample != null) onSample(sample)
                        delay(500L)
                      }
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            NativeVisualizationTelemetry.release(ownerToken)
                        }
                    }
                }
            }
        }
    }

    private suspend fun poll(context: Context) {
        var previousGeneration = -1L
        while (currentCoroutineContext().isActive) {
            while (wakeups.tryReceive().isSuccess) { /* coalesce page hand-offs */ }
            if (clients.isEmpty()) {
                withTimeoutOrNull(500) { wakeups.receive() }
                continue
            }
            val epoch = generation
            val currentClients = clients.values.toList()
            val regularClients = currentClients.filter { it.bassMonitor == null }
            val now = SystemClock.elapsedRealtime()
            val bassMonitors = currentClients.mapNotNull { it.bassMonitor }
            val dueBassMonitors = bassMonitors.filter { it.isDue(now) }
            val requested = regularClients.flatMap { it.modules }.toMutableSet().apply {
                if (dueBassMonitors.isNotEmpty()) add(Module.BASS)
            }
            if (regularClients.isEmpty() && dueBassMonitors.isEmpty()) {
                val wait = bassMonitors.minOfOrNull {
                    (BASS_MONITOR_INTERVAL_MS - (now - (it.lastSampleTime ?: now))).coerceAtLeast(1)
                } ?: BASS_MONITOR_INTERVAL_MS
                withTimeoutOrNull(wait) { wakeups.receive() }
                continue
            }
            val detailed = regularClients.any { it.modules != homeModules }
            // The session map belongs to the main thread; only immutable references cross to IO.
            val application = context as? MainApplication
            val mode = application?.let {
                when {
                    it.isPlayerSessionMode -> OutputRuntimeStatus.Mode.PLAYER_SESSION
                    else -> OutputRuntimeStatus.Mode.GLOBAL
                }
            }
            val engines = application?.rootSessionDatabase?.sessionList?.toList()
                ?.mapNotNull { (id, session) ->
                    (session as? RemoteEffectSession)?.effect?.let { id to it }
                }.orEmpty()
            val telemetry = withContext(Dispatchers.IO) {
                telemetryMutex.withLock {
                    readAndPublish(context, engines, requested, epoch)
                }
            }
            if (epoch != generation) continue
            val reading = telemetry?.output
            val globalIssue = if (mode == OutputRuntimeStatus.Mode.GLOBAL) {
                when (application?.rootSessionDatabase?.globalPlaybackIssue) {
                    GlobalPlaybackEvidence.Reason.SUSPENDED -> OutputRuntimeStatus.GlobalIssue.SUSPENDED
                    GlobalPlaybackEvidence.Reason.DIFFERENT_OUTPUT -> OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT
                    null -> if (reading?.connected == true && reading.processing == OutputRuntimeStatus.Processing.IDLE &&
                        application?.rootSessionDatabase?.enabled == true &&
                        application.rootSessionDatabase.hasActiveGlobalPlayback)
                        OutputRuntimeStatus.GlobalIssue.NO_PROCESSING else null
                }
            } else null
            // Include attachment mode and verified global coverage in UI equality.
            val output = reading?.copy(mode = mode, globalIssue = globalIssue)
            if (output != null && output != lastOutput) {
                lastOutput = output
                clients.values.toList().forEach { it.onOutput(output) }
            }
            if (output != null && telemetry != null) {
                dueBassMonitors.forEach { monitor ->
                    if (epoch != generation) return@forEach
                    val snapshot = monitor.tracker.update(telemetry.bass, telemetry.bassEnabled)
                    monitor.lastSampleTime = telemetry.elapsedRealtimeMs
                    monitor.onSample(BassSample(telemetry.elapsedRealtimeMs,
                        monitor.tracker.activeStreamIdentity, snapshot, output))
                }
            }
            val regularInterval = if (regularClients.isNotEmpty() && previousGeneration != epoch &&
                output?.processing == OutputRuntimeStatus.Processing.UNKNOWN) 150L
                else if (detailed && requested.isNotEmpty()) 500L else 1000L
            val afterReading = SystemClock.elapsedRealtime()
            val monitorInterval = bassMonitors.minOfOrNull {
                (BASS_MONITOR_INTERVAL_MS - (afterReading - (it.lastSampleTime ?: afterReading))).coerceAtLeast(1)
            } ?: regularInterval
            val interval = minOf(regularInterval, monitorInterval)
            previousGeneration = epoch
            withTimeoutOrNull(interval) { wakeups.receive() }
        }
    }

    /** This entire method, including controller state locks and publication, runs on IO. */
    private fun readAndPublish(context: Context, engines: List<Pair<Int, JamesDspRemoteEngine>>,
        modules: Set<Module>, epoch: Long): TelemetrySnapshot? {
        if (epoch != generation) return null
        val highFrequencyPoll = if (Module.HIGH_FREQUENCY in modules) HighFrequencyController.pollState() else null
        val fieldPoll = if (Module.FIELD in modules) FieldController.pollState() else null
        val diffPoll = if (Module.DIFF in modules) DiffController.pollState() else null
        val crossfeedPoll = if (Module.CROSSFEED in modules) CrossfeedController.pollState() else null
        val presetCrossfeedPoll = if (Module.PRESET_CROSSFEED in modules) PresetCrossfeedController.pollState() else null
        val reverbPoll = if (Module.REVERB in modules) ReverbController.pollState() else null
        val roomReverbPoll = if (Module.ROOM_REVERB in modules) RoomReverbController.pollState() else null
        val tubePoll = if (Module.TUBE in modules) TubeController.pollState() else null
        val analogPoll = if (Module.ANALOG in modules) AnalogController.pollState() else null
        val dynamicPoll = if (Module.DYNAMIC in modules) DynamicController.pollState() else null
        val timbrePoll = if (Module.TIMBRE in modules) TimbreController.pollState() else null
        val companderPoll = if (Module.COMPANDER in modules) CompanderController.pollState() else null
        val tonePoll = if (Module.TONE in modules) ToneAdvancedController.pollState() else null
        val ddcPoll = if (Module.DDC in modules) DdcController.pollState() else null
        val convolutionPoll = if (Module.CONVOLUTION in modules) ConvolutionController.pollState() else null
        val widthPoll = if (Module.WIDTH in modules) WidthController.pollState() else null
        val readings = engines.mapNotNull { (id, engine) ->
            if (epoch != generation) return null
            runCatching {
                engine.outputRuntimeSession()?.copy(sessionId = id)?.let { output ->
                    RuntimeReadings(output,
                        bass = if (Module.BASS in modules) engine.bassRuntimeReading(output)
                            ?.copy(hostRate = output.hostRate, sessionId = output.sessionId) else null,
                        highFrequency = if (highFrequencyPoll != null) engine.highFrequencyRuntimeReading(output) else null,
                        field = if (fieldPoll != null) engine.fieldRuntimeReading(output) else null,
                        diff = if (diffPoll != null) engine.diffRuntimeReading(output) else null,
                        convolution = if (convolutionPoll != null) engine.convolutionRuntimeReading(output) else null,
                        width = if (widthPoll != null) engine.widthRuntimeReading(output) else null,
                        crossfeed = if (crossfeedPoll != null) engine.crossfeedRuntimeReading(output) else null,
                        presetCrossfeed = if (presetCrossfeedPoll != null) engine.presetCrossfeedRuntimeReading(output) else null,
                        reverb = if (reverbPoll != null) engine.reverbRuntimeReading(output) else null,
                        roomReverb = if (roomReverbPoll != null) engine.roomReverbRuntimeReading(output) else null,
                        tube = if (tubePoll != null) engine.tubeRuntimeReading(output) else null,
                        analog = if (analogPoll != null) engine.analogRuntimeReading(output) else null,
                        timbre = if (timbrePoll != null) engine.timbreRuntimeReading(output) else null,
                        dynamic = if (dynamicPoll != null) engine.dynamicRuntimeReading(output) else null,
                        compander = if (companderPoll != null) engine.companderRuntimeReading(output) else null,
                        toneAdvanced = if (tonePoll != null) engine.toneAdvancedRuntimeReading(output) else null,
                        ddc = if (ddcPoll != null) engine.ddcRuntimeReading(output) else null)
                }
            }.getOrNull()
        }
        val sampledAt = SystemClock.elapsedRealtime()
        if (epoch != generation) return null
        val output = runtimeTracker.update(readings.map { it.output })
        val bassReadings = readings.mapNotNull { it.bass }
        val bassEnabled = Module.BASS in modules && BassController.preferences(context, false)
            .getBoolean(context.getString(R.string.key_bass_enable), false)
        if (Module.BASS in modules) {
            BassController.publishRuntime(context, bassRuntimeTracker.update(bassReadings, bassEnabled))
        }
        highFrequencyPoll?.let { HighFrequencyController.publishRuntime(context, readings.mapNotNull { it.highFrequency },
            it.auditionRevision, it.runtimeRevision, it.sentRevisionToken) }
        fieldPoll?.let { FieldController.publishRuntime(context, readings.mapNotNull { it.field },
            it.auditionRevision, it.runtimeRevision, it.sentRevisionToken) }
        diffPoll?.let { DiffController.publishRuntime(context, readings.mapNotNull { it.diff },
            it.auditionRevision, it.runtimeRevision, it.sentRevisionToken) }
        crossfeedPoll?.let { CrossfeedController.publishRuntime(context, readings.mapNotNull { it.crossfeed }, it) }
        presetCrossfeedPoll?.let { PresetCrossfeedController.publishRuntime(context, readings.mapNotNull { it.presetCrossfeed }, it) }
        reverbPoll?.let { ReverbController.publishRuntime(context, readings.mapNotNull { it.reverb }, it) }
        roomReverbPoll?.let { RoomReverbController.publishRuntime(context, readings.mapNotNull { it.roomReverb }, it) }
        tubePoll?.let { TubeController.publishRuntime(context, readings.mapNotNull { it.tube }, it) }
        analogPoll?.let { AnalogController.publishRuntime(context, readings.mapNotNull { it.analog }, it) }
        dynamicPoll?.let { DynamicController.publishRuntime(context, readings.mapNotNull { it.dynamic }, it) }
        timbrePoll?.let { TimbreController.publishRuntime(context, readings.mapNotNull { it.timbre }, it) }
        companderPoll?.let { CompanderController.publishRuntime(context, readings.mapNotNull { it.compander }, it) }
        tonePoll?.let { ToneAdvancedController.publishRuntime(context, readings.mapNotNull { it.toneAdvanced }, it) }
        ddcPoll?.let { DdcController.publishRuntime(context, readings.mapNotNull { it.ddc }, it) }
        convolutionPoll?.let { ConvolutionController.publishRuntime(context, readings.mapNotNull { it.convolution },
            it.auditionRevision, it.runtimeRevision, it.sentRevisionToken) }
        widthPoll?.let { WidthController.publishRuntime(context, readings.mapNotNull { it.width }, it) }
        return TelemetrySnapshot(output, bassReadings, bassEnabled, sampledAt)
    }

    /** Locks are acquired only in the worker and rechecked after acquisition before cleanup. */
    private fun clearRuntime(context: Context, expectedGeneration: Long) {
        if (generation != expectedGeneration) return
        runtimeTracker.clear()
        bassRuntimeTracker.clear()
        BassController.publishRuntime(context, BassRuntimeStatus.Snapshot(BassRuntimeStatus.State.UNKNOWN))
        fun clear(controller: Any, action: () -> Unit) {
            synchronized(controller) { if (generation == expectedGeneration) action() }
        }
        clear(HighFrequencyController) {
            HighFrequencyController.stopAudition(context)
            HighFrequencyController.publishRuntime(context, emptyList())
        }
        clear(FieldController) { FieldController.stopAudition(context); FieldController.publishRuntime(context, emptyList()) }
        clear(DiffController) { DiffController.stopAudition(context); DiffController.publishRuntime(context, emptyList()) }
        clear(CrossfeedController) {
            CrossfeedController.stopAudition(context, clearSnapshots = true); CrossfeedController.publishRuntime(context, emptyList())
        }
        clear(PresetCrossfeedController) {
            PresetCrossfeedController.stopAudition(context, clearSnapshots = true); PresetCrossfeedController.publishRuntime(context, emptyList())
        }
        clear(ReverbController) {
            ReverbController.stopAudition(context, clearSnapshots = true); ReverbController.publishRuntime(context, emptyList())
        }
        clear(RoomReverbController) {
            RoomReverbController.stopAudition(context, clearSnapshots = true); RoomReverbController.publishRuntime(context, emptyList())
        }
        clear(TubeController) {
            TubeController.stopAudition(context, clearSnapshots = true); TubeController.publishRuntime(context, emptyList())
        }
        clear(AnalogController) {
            AnalogController.stopAudition(context, clearSnapshots = true); AnalogController.publishRuntime(context, emptyList())
        }
        clear(DynamicController) {
            DynamicController.stopAudition(context, clearSnapshots = true); DynamicController.publishRuntime(context, emptyList())
        }
        clear(TimbreController) {
            TimbreController.stopAudition(context, clearSnapshots = true); TimbreController.publishRuntime(context, emptyList())
        }
        clear(CompanderController) {
            CompanderController.stopAudition(context, clearSnapshots = true); CompanderController.publishRuntime(context, emptyList())
        }
        clear(ToneAdvancedController) {
            ToneAdvancedController.stopAudition(context, clearSnapshots = true); ToneAdvancedController.publishRuntime(context, emptyList())
        }
        clear(DdcController) {
            DdcController.stopAudition(context, clearSnapshots = true); DdcController.publishRuntime(context, emptyList())
        }
        clear(ConvolutionController) {
            ConvolutionController.stopAudition(context); ConvolutionController.publishRuntime(context, emptyList())
        }
        clear(WidthController) { WidthController.stopAudition(context); WidthController.publishRuntime(context, emptyList()) }
    }
}
