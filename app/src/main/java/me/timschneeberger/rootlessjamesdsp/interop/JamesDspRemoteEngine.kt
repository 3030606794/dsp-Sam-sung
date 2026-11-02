package me.timschneeberger.rootlessjamesdsp.interop

import me.timschneeberger.rootlessjamesdsp.R
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.AudioManager
import android.media.audiofx.AudioEffectHidden
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundController
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundProfessionalRuntime
import me.timschneeberger.rootlessjamesdsp.fusion.SurroundProfessionalParameters
import me.timschneeberger.rootlessjamesdsp.fusion.ToneController
import me.timschneeberger.rootlessjamesdsp.fusion.DdcController
import me.timschneeberger.rootlessjamesdsp.fusion.DdcParameters
import me.timschneeberger.rootlessjamesdsp.fusion.DdcVdc
import me.timschneeberger.rootlessjamesdsp.fusion.ToneAdvancedController
import me.timschneeberger.rootlessjamesdsp.fusion.ToneAdvancedParameters
import me.timschneeberger.rootlessjamesdsp.fusion.ViperController
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.fusion.BassController
import me.timschneeberger.rootlessjamesdsp.fusion.BassParameters
import me.timschneeberger.rootlessjamesdsp.fusion.BassRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.fusion.HighFrequencyController
import me.timschneeberger.rootlessjamesdsp.fusion.HighFrequencyParameters
import me.timschneeberger.rootlessjamesdsp.fusion.HighFrequencyAdvancedParameters
import me.timschneeberger.rootlessjamesdsp.fusion.NativeVisualizationTelemetry
import me.timschneeberger.rootlessjamesdsp.fusion.FieldController
import me.timschneeberger.rootlessjamesdsp.fusion.FieldParameters
import me.timschneeberger.rootlessjamesdsp.fusion.FieldAdvancedParameters
import me.timschneeberger.rootlessjamesdsp.fusion.FieldRuntimeReader
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionController
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionParameters
import me.timschneeberger.rootlessjamesdsp.fusion.WidthController
import me.timschneeberger.rootlessjamesdsp.fusion.WidthParameters
import me.timschneeberger.rootlessjamesdsp.fusion.WidthRuntime
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionAdvancedParameters
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionRuntimeReader
import me.timschneeberger.rootlessjamesdsp.fusion.DiffController
import me.timschneeberger.rootlessjamesdsp.fusion.CrossfeedController
import me.timschneeberger.rootlessjamesdsp.fusion.CrossfeedParameters
import me.timschneeberger.rootlessjamesdsp.fusion.CrossfeedAdvancedParameters
import me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedController
import me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedParameters
import me.timschneeberger.rootlessjamesdsp.fusion.ReverbParameters
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbController
import me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbParameters
import me.timschneeberger.rootlessjamesdsp.fusion.TubeController
import me.timschneeberger.rootlessjamesdsp.fusion.AnalogController
import me.timschneeberger.rootlessjamesdsp.fusion.DynamicController
import me.timschneeberger.rootlessjamesdsp.fusion.TimbreController
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderController
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderParameters
import me.timschneeberger.rootlessjamesdsp.fusion.TubeParameters
import me.timschneeberger.rootlessjamesdsp.fusion.AnalogParameters
import me.timschneeberger.rootlessjamesdsp.fusion.DynamicParameters
import me.timschneeberger.rootlessjamesdsp.fusion.TimbreParameters
import me.timschneeberger.rootlessjamesdsp.fusion.ReverbController
import me.timschneeberger.rootlessjamesdsp.fusion.DiffParameters
import me.timschneeberger.rootlessjamesdsp.fusion.DiffRuntimeReader
import me.timschneeberger.rootlessjamesdsp.peq.PeqController
import me.timschneeberger.rootlessjamesdsp.peq.PeqProtocol
import me.timschneeberger.rootlessjamesdsp.dynamicpeq.DynamicPeqController
import me.timschneeberger.rootlessjamesdsp.dynamicpeq.DynamicPeqProtocol
import me.timschneeberger.rootlessjamesdsp.dynamicpeq.DynamicPeqRepository
import me.timschneeberger.rootlessjamesdsp.dynamicpeq.DynamicPeqRuntime
import me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectsController
import me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectsKind
import me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectsParameters
import me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectsRuntime
import me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsController
import me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsKind
import me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsParameters
import me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsRuntime
import me.timschneeberger.rootlessjamesdsp.expansion.SpeakerOptimizationProtocol
import me.timschneeberger.rootlessjamesdsp.expansion.SmartAdaptProtocol
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.model.ProcessorMessage
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogProtocol
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogScriptTransfer
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.getParameterInt
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.getParameterInts
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.readParameterInt
import me.timschneeberger.rootlessjamesdsp.fusion.DiffAdvancedParameters
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.setParameter
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.setParameterCharBuffer
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.setParameterFloatArray
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.setParameterImpulseResponseBuffer
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.setParameterIntArray
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.crc
import me.timschneeberger.rootlessjamesdsp.utils.extensions.toShort
import timber.log.Timber
import java.util.UUID
import kotlin.math.roundToInt

class JamesDspRemoteEngine(
    context: Context,
    val sessionId: Int,
    val priority: Int,
    callbacks: JamesDspWrapper.JamesDspCallbacks? = null,
) : JamesDspBaseEngine(context, callbacks) {

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Constants.ACTION_SAMPLE_RATE_UPDATED -> syncWithPreferences(arrayOf(Constants.PREF_CONVOLVER,Constants.PREF_DDC))
                Constants.ACTION_PREFERENCES_UPDATED -> syncWithPreferences()
                Constants.ACTION_SERVICE_RELOAD_LIVEPROG -> syncWithPreferences(arrayOf(Constants.PREF_LIVEPROG))
                Constants.ACTION_SERVICE_UPDATE_LIVEPROG_PARAMETERS -> intent.getStringExtra(Constants.EXTRA_LIVEPROG_PATH)?.let(::updateLiveprogParameters)
                Constants.ACTION_SERVICE_HARD_REBOOT_CORE -> { manualRecovery = true; syncWithPreferences() }
                Constants.ACTION_SERVICE_SOFT_REBOOT_CORE -> { softRecovery = true; syncWithPreferences() }
            }
        }
    }

    private val fusionPackets = mutableMapOf<Int, IntArray>()
    private var fusionConfigurationRate: Int? = null
    private var creativeObservedRate: Int? = null
    @Volatile private var forceFusionRefresh = false
    @Volatile private var softRecovery = false
    private var legacyCurveDisabled = false
    private var legacyBassGain: Int? = null
    @Volatile private var graphicEqNeedsRetry = false
    @Volatile private var convolverFailure: String? = null
    @Volatile private var convolutionCommonDelay = 0
    private val convolutionRecovery = ConvolutionTransferRecovery()
    private val convolutionMonitor = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var convolutionDeferred = false
    @Volatile private var convolutionConfirmed = false
    @Volatile private var convolutionTerminalFailure = false
    @Volatile private var convolutionTarget: ConvolutionTransferRecovery.Target? = null
    private val convolutionActivityLock = Any()
    private var convolutionActivityIdentity: Any? = null
    private var convolutionPreviousFrames: Int? = null
    private var convolutionProgressAt = 0L
    private var fusionCapabilities = 0
    private var fusionExtendedCapabilities = 0
    private var peqNeedsDriver = false
    private var peqDriverUpgradeNoticeShown = false
    @Volatile private var dynamicPeqCapability: Boolean? = null
    @Volatile private var creativeEffectsCapability: Boolean? = null
    private var creativeEffectsNeedsDriver = false
    @Volatile private var expansionEffectsCapability: Boolean? = null
    @Volatile private var speakerOptimizationCapability: Boolean? = null
    @Volatile private var smartAdaptCapability: Boolean? = null
    private var expansionEffectsNeedsDriver = false
    private var dynamicPeqUpgradeNoticeShown = false
    private var dynamicPeqNeedsDriver = false
    private var dynamicPeqAppliedPayload: IntArray? = null
    private var ddcSelectedPath=""
    private var ddcAcceptedPath=""
    private var ddcAcceptedSha:String?=null
    private var ddcAcceptedLabel=""
    private var ddcFileUnconfirmed=true
    private var health = EngineHealth()
    private val retryHandler = Handler(Looper.getMainLooper())
    private val retrySync = Runnable { syncWithPreferences() }
    @Volatile private var engineClosed = false
    private val releaseCompletionLock = Any()
    private val releaseCallbacks = mutableListOf<() -> Unit>()
    private var releaseCompleted = false
    @Volatile private var manualRecovery = false
    private val power = EnginePower()
    private var generation = 0
    private var recoveryNoticeShown = false
    private var parameterFailures = 0
    // These requests only invalidate a control cache on the existing preference worker.
    // A PCM observer never owns an audio parameter or an editor draft.
    private data class ParameterRepairTarget(val hash: Int, val enabled: Boolean)
    private data class ParameterRepairRequest(val identity: Any, val rate: Int, val frames: Int,
        val target: ParameterRepairTarget)
    private val parameterRepairLock = Any()
    private var pendingPeqRepair: ParameterRepairRequest? = null
    private var pendingEqRepair: ParameterRepairRequest? = null
    private var fusionPreferenceSyncFailed = false
    private var preferenceFailureReported = false
    private var lastPresetState: String? = null
    @Volatile var effect: AudioEffectHidden? = createEffect()
        private set

    override var enabled: Boolean
        set(value) {
            if (engineClosed) return
            power.request(value)
            syncWithPreferences()
        }
        get() = runCatching { effect?.enabled }.getOrNull() ?: false

    private fun applyRequestedPower() {
            if (!power.pending || engineClosed) return
            val result = power.apply { value ->
            val live = effect ?: return@apply AudioEffect.ERROR_NO_INIT
            val changed = !value || runCatching { live.enabled }.getOrNull() != value
            val status = live.setEnabled(value)
            if (status != AudioEffect.SUCCESS) return@apply status
            live.let { if (changed) {
                HighFrequencyController.sessionEnabled(context, it, value)
                FieldController.sessionEnabled(context, it, value)
                DiffController.sessionEnabled(context, it, value)
                CrossfeedController.sessionEnabled(context,it,value)
                PresetCrossfeedController.sessionEnabled(context,it,value)
                ReverbController.sessionEnabled(context,it,value)
                RoomReverbController.sessionEnabled(context,it,value)
                TubeController.sessionEnabled(context,it,value)
                AnalogController.sessionEnabled(context,it,value)
                DynamicController.sessionEnabled(context,it,value)
                TimbreController.sessionEnabled(context,it,value)
                CompanderController.sessionEnabled(context,it,value)
                ToneAdvancedController.sessionEnabled(context,it,value)
                DdcController.sessionEnabled(context,it,value)
                ConvolutionController.sessionEnabled(context,it,value)
                WidthController.sessionEnabled(context,it,value)
                SurroundProfessionalRuntime.sessionEnabled(context,it,value)
            } }
            if (!value) {
                CreativeEffectsController.resetTransient(context)
                synchronized(this) {
                    fusionPackets.remove(CreativeEffectsKind.FREEZE.parameter)
                    fusionPackets.remove(HighFrequencyParameters.SPECTRUM_PACKET)
                    fusionPackets.remove(FieldParameters.PACKET)
                    fusionPackets.remove(FieldAdvancedParameters.DIFFERENCE_PACKET)
                    fusionPackets.remove(DiffParameters.AUDITION_PACKET)
                    fusionPackets.remove(CrossfeedParameters.TOOLS)
                    fusionPackets.remove(CrossfeedAdvancedParameters.TOOLS)
                    fusionPackets.remove(PresetCrossfeedParameters.TOOLS)
                    fusionPackets.remove(PresetCrossfeedParameters.ADVANCED_TOOLS)
                    fusionPackets.remove(ReverbParameters.TOOLS)
                    fusionPackets.remove(RoomReverbParameters.TOOLS)
                    fusionPackets.remove(TubeParameters.TOOLS)
                    fusionPackets.remove(AnalogParameters.TOOLS)
                    fusionPackets.remove(DynamicParameters.TOOLS)
                    fusionPackets.remove(TimbreParameters.TOOLS)
                    fusionPackets.remove(ConvolutionParameters.AUDITION_ID)
                    fusionPackets.remove(WidthParameters.AUDITION)
                    fusionPackets.remove(me.timschneeberger.rootlessjamesdsp.fusion.WidthAdvancedParameters.AUDITION)
                    fusionPackets.remove(SurroundProfessionalParameters.AUDITION)
                    fusionPackets.remove(me.timschneeberger.rootlessjamesdsp.fusion.SurroundListeningParameters.PACKET)
                }
            }
            status
            }
            when(result) {
                EnginePower.Result.DONE -> Unit
                EnginePower.Result.RETRY -> scheduleHealthRetry()
                EnginePower.Result.STOP -> ViperController.publishStatus(context,"音效开关写入失败；已保留开关选择，可重新启动音效服务")
            }
    }

    override var sampleRate: Float
        get() {
            super.sampleRate = effect.getParameterInt(20001)?.toFloat() ?: -0f
            return super.sampleRate
        }
        set(_){}

    init {
        syncWithPreferences()

        val filter = IntentFilter()
        filter.addAction(Constants.ACTION_PREFERENCES_UPDATED)
        filter.addAction(Constants.ACTION_SAMPLE_RATE_UPDATED)
        filter.addAction(Constants.ACTION_SERVICE_RELOAD_LIVEPROG)
        filter.addAction(Constants.ACTION_SERVICE_UPDATE_LIVEPROG_PARAMETERS)
        filter.addAction(Constants.ACTION_SERVICE_HARD_REBOOT_CORE)
        filter.addAction(Constants.ACTION_SERVICE_SOFT_REBOOT_CORE)
        context.registerLocalReceiver(broadcastReceiver, filter)
        convolutionMonitor.launch {
            var previousIdentity: Any? = null
            var previousRate = -1
            while (isActive && !engineClosed) {
                delay(500)
                val live = effect ?: continue
                val preferences = ConvolutionController.preferences(context)
                if (!preferences.getBoolean(ConvolutionParameters.ENABLED, false)) continue
                val rate = live.getParameterInt(20001) ?: 0
                val active = convolutionAudioActive(live)
                // The constructor already queues the initial complete snapshot. A first poll
                // must not reload every processor just because it has no previous observation.
                val changed = previousIdentity != null && (previousIdentity !== live || previousRate != rate)
                previousIdentity = live; previousRate = rate
                val target = convolutionTarget
                if (target?.identity === live && !convolutionTerminalFailure &&
                    convolutionKernelMatches(live, target) == false) {
                    convolutionDeferred = true
                    convolutionConfirmed = false
                }
                val retry = convolutionDeferred && !convolutionTerminalFailure &&
                    if (convolutionConfirmed) active else
                        convolutionRecovery.action(SystemClock.elapsedRealtime(), active) == ConvolutionTransferRecovery.Action.ATTEMPT
                if (changed || retry) syncWithPreferences(arrayOf(Constants.PREF_CONVOLVER))
            }
        }
    }

    private fun createEffect(): AudioEffectHidden {
        dynamicPeqAppliedPayload = null
        // A newly created audio engine must never replay an earlier momentary capture.
        CreativeEffectsController.resetTransient(context)
        creativeObservedRate = null
        convolutionRecovery.reset()
        convolutionTarget = null; convolutionDeferred = false; convolutionConfirmed = false; convolutionTerminalFailure = false
        invalidateLiveprogParameters()
        ddcAcceptedPath="";ddcAcceptedSha=null;ddcAcceptedLabel="";ddcFileUnconfirmed=true
        return try {
            AudioEffectHidden(EFFECT_TYPE_CUSTOM, EFFECT_JAMESDSP, priority, sessionId).also {
                val capabilitiesRead = it.getParameterInt(FUSION_CAPABILITIES)
                val handshake = DriverProtocolHandshake.classify(it.getParameterInt(FUSION_PROTOCOL),
                    capabilitiesRead, REQUIRED_PROTOCOL, 15)
                if (handshake != DriverProtocolHandshake.State.Available) {
                    it.release()
                    throw IllegalStateException(if (handshake == DriverProtocolHandshake.State.NotReady)
                        DRIVER_NOT_READY else "DSP音效 驱动协议不匹配，请安装与 APK 同一版本的融合模块。")
                }
                val capabilities = requireNotNull(capabilitiesRead)
                protocolVerified = true
                fusionCapabilities = capabilities
                fusionExtendedCapabilities = it.getParameterInt(ToneAdvancedParameters.EXTENDED_CAPABILITIES) ?: 0
                dynamicPeqCapability = it.getParameterInt(DynamicPeqProtocol.CAPABILITY_QUERY) == DynamicPeqProtocol.SCHEMA
                creativeEffectsCapability = it.getParameterInt(CreativeEffectsParameters.CAPABILITY_QUERY) == CreativeEffectsParameters.SCHEMA
                expansionEffectsCapability = it.getParameterInt(ExpansionEffectsParameters.CAPABILITY_QUERY) == ExpansionEffectsParameters.SCHEMA
                speakerOptimizationCapability = it.getParameterInt(SpeakerOptimizationProtocol.CAPABILITY_QUERY) == SpeakerOptimizationProtocol.SCHEMA
                smartAdaptCapability = SmartAdaptProtocol.supports(it.getParameterInts(SmartAdaptProtocol.CAPABILITY_QUERY, 4))
                ToneAdvancedController.publishCapabilities(context,fusionExtendedCapabilities)
                DdcController.publishCapabilities(context,fusionExtendedCapabilities)
                it.setControlStatusListener { _, granted -> if (granted && !engineClosed) {
                    forceFusionRefresh = true
                    syncWithPreferences()
                } }
                BassController.publishCapabilities(context, capabilities)
                HighFrequencyController.publishCapabilities(context, capabilities)
                FieldController.publishCapabilities(context, capabilities)
                DiffController.publishCapabilities(context, capabilities)
                CrossfeedController.publishCapabilities(context,capabilities)
                PresetCrossfeedController.publishCapabilities(context,capabilities)
                ReverbController.publishCapabilities(context,capabilities)
                RoomReverbController.publishCapabilities(context,capabilities)
                TubeController.publishCapabilities(context,capabilities)
                AnalogController.publishCapabilities(context,capabilities)
                DynamicController.publishCapabilities(context,capabilities)
                TimbreController.publishCapabilities(context,capabilities)
                CompanderController.publishCapabilities(context,capabilities)
                ConvolutionController.publishCapabilities(context,capabilities)
                WidthController.publishCapabilities(context,capabilities)
                SurroundController.publishCapabilities(context, capabilities)
                ViperController.publishStatus(context, "已连接 DSP音效：参数均衡器 + JamesDSP + MSEB + ViPER")
            }
        } catch (e: Exception) {
            fusionCapabilities = 0
            fusionExtendedCapabilities = 0
            dynamicPeqCapability = null
            creativeEffectsCapability = null
            expansionEffectsCapability = null
            speakerOptimizationCapability = null
            smartAdaptCapability = null
            ToneAdvancedController.publishCapabilities(context,0)
            DdcController.publishCapabilities(context,0)
            BassController.publishCapabilities(context, 0)
            HighFrequencyController.publishCapabilities(context, 0)
            FieldController.publishCapabilities(context, 0)
            DiffController.publishCapabilities(context, 0)
            CrossfeedController.publishCapabilities(context,0)
            PresetCrossfeedController.publishCapabilities(context,0)
            ReverbController.publishCapabilities(context,0)
            RoomReverbController.publishCapabilities(context,0)
            TubeController.publishCapabilities(context,0)
            AnalogController.publishCapabilities(context,0)
            DynamicController.publishCapabilities(context,0)
            TimbreController.publishCapabilities(context,0)
            CompanderController.publishCapabilities(context,0)
            ConvolutionController.publishCapabilities(context,0)
            WidthController.publishCapabilities(context,0)
            SurroundController.publishCapabilities(context, 0)
            ViperController.publishStatus(context, if (e.message == DRIVER_NOT_READY) DRIVER_NOT_READY else
                "连接失败：请安装 DSP音效 融合模块并重启手机。旧 JamesDSP / ViPER 模块不能驱动本应用。")
            Timber.e("Failed to create DSP音效 effect")
            Timber.e(e)
            throw IllegalStateException(e)
        }
    }

    private fun scheduleHealthRetry(delayMs: Long = 250) {
        if (engineClosed) return
        forceFusionRefresh = true
        retryHandler.removeCallbacks(retrySync)
        retryHandler.postDelayed(retrySync, delayMs)
    }

    override fun onPreferenceCacheCleared() {
        fusionPackets.clear()
        fusionConfigurationRate = null
        forceFusionRefresh = true
    }

    /** Called only by the one preference worker while holding engineLock. */
    override fun beforePreferenceSync(): Boolean {
        if (engineClosed) return false
        preferenceFailureReported = false
        // A broadcast runs on Main; defer waiting for an in-flight IR decode/engine lock to this worker.
        if (softRecovery) {
            softRecovery = false
            clearCache()
        }
        if (manualRecovery) {
            manualRecovery = false; health = EngineHealth()
            if (!recreateEngine()) { scheduleHealthRetry(); return false }
        }
        val live = effect
        val pidRead = live.readParameterInt(20002)
        val rateRead = live.readParameterInt(20001)
        val reading = EngineHealth.Reading(pidRead.value, pidRead.status, rateRead.value, rateRead.status,
            live?.let { runCatching { it.hasControl() }.getOrNull() })
        val decision = health.observe(SystemClock.elapsedRealtime(), reading)
        val crossfeed = PresetCrossfeedController.preferences(context).all
        val headphones = CrossfeedController.preferences(context).all
        val presetState = "six=${PresetCrossfeedParameters.base(crossfeed)} pro=${PresetCrossfeedParameters.enabled(crossfeed)} headphone=${headphones[CrossfeedParameters.LEGACY]} pro=${headphones[CrossfeedParameters.ENABLED]}"
        if (decision != EngineHealth.Action.APPLY || lastPresetState != presetState) {
            Timber.i("DSP connection sid=%d generation=%d preset=%s -> %s status=%s decision=%s", sessionId,
                generation, lastPresetState, presetState, reading, decision)
            lastPresetState = presetState
        }
        when (decision) {
            EngineHealth.Action.RETRY -> { scheduleHealthRetry(); return false }
            EngineHealth.Action.WAIT_CONTROL -> {
                ViperController.publishStatus(context, "音效控制权暂时由其他音频会话占用，等待恢复")
                scheduleHealthRetry(1_000); return false
            }
            EngineHealth.Action.STOP -> {
                retryHandler.removeCallbacks(retrySync)
                ViperController.publishStatus(context, "音效连接持续失败，已停止自动重试；可重新启动音效服务")
                return false
            }
            EngineHealth.Action.RECOVER -> {
                if (!recoveryNoticeShown) {
                    recoveryNoticeShown = true
                    retryHandler.post { if (!engineClosed) context.toast("音效连接暂时中断，正在恢复", false) }
                }
                if (!recreateEngine()) { scheduleHealthRetry(); return false }
            }
            EngineHealth.Action.APPLY -> { retryHandler.removeCallbacks(retrySync); recoveryNoticeShown = false }
        }
        // Cache invalidation/retry is not an audio foundation change and must not release a held sound.
        if (creativeObservedRate != null && creativeObservedRate != rateRead.value) {
            CreativeEffectsController.resetTransient(context)
        }
        creativeObservedRate = rateRead.value
        if (forceFusionRefresh || fusionConfigurationRate != rateRead.value) {
            forceFusionRefresh = false
            fusionPackets.clear()
            fusionConfigurationRate = rateRead.value
        }
        if (power.pending && !power.desired) {
            applyRequestedPower()
            if (power.pending) return false
        }
        consumeParameterRepairs()
        // An unrelated Fusion module can reject its packet while James' original EQ
        // still has valid pending controls. Let that independent submission continue.
        fusionPreferenceSyncFailed = try { !syncFusionPreferences() }
        catch (failure: RuntimeException) {
            Timber.e(failure, "Fusion preference synchronization failed")
            true
        }
        // Keep the existing bounded retry even if a later legacy namespace throws.
        // Its success callback must not erase this separate failure.
        if (fusionPreferenceSyncFailed) preferenceSyncFailed()
        return true
    }

    override fun afterPreferenceSync() {
        if (!fusionPreferenceSyncFailed) {
            parameterFailures = 0
            applyRequestedPower()
        }
    }

    private fun hashParameterWords(words: IntArray): Int {
        var hash = 0x811c9dc5.toInt()
        words.forEach { word ->
            for (shift in 0 until 32 step 8) hash = (hash xor ((word ushr shift) and 255)) * 16777619
        }
        return hash
    }

    /** Capture the very same saved/temporary-listening target used by SET 1250. */
    private fun currentPeqRepairTarget(): ParameterRepairTarget? = try {
        var target: ParameterRepairTarget? = null
        PeqController.sync(context) { _, words ->
            target = ParameterRepairTarget(hashParameterWords(words), words[1] == 1)
            true
        }
        target
    } catch (_: RuntimeException) { null }

    private fun currentEqRepairTarget(): ParameterRepairTarget? {
        val values = PreferenceCache.getPreferences(context, Constants.PREF_EQ).all
        val enabled = when (val value = values[context.getString(R.string.key_eq_enable)]) {
            null -> false
            is Boolean -> value
            else -> return null
        }
        // James deliberately retains its inactive axes. A disabled target needs
        // only an enable confirmation, never a fabricated hash of saved axes.
        if (!enabled) return ParameterRepairTarget(0, false)
        val type = (values[context.getString(R.string.key_eq_filter_type)] as? String ?: "0").toIntOrNull()
            ?.takeIf { it in 0..5 } ?: return null
        val interpolation = (values[context.getString(R.string.key_eq_interpolation)] as? String ?: "0").toIntOrNull()
            ?: return null
        val bands = values[context.getString(R.string.key_eq_bands)] as? String ?: Constants.DEFAULT_EQ
        val hash = me.timschneeberger.rootlessjamesdsp.eq.EqPcmTelemetry.expectedParameterHash(
            bands, type, interpolation, true) ?: return null
        return ParameterRepairTarget(hash, true)
    }

    private fun parameterRepairRequest(session: OutputRuntimeStatus.Session,
        target: ParameterRepairTarget): ParameterRepairRequest? {
        val live = effect ?: return null
        val rate = session.hostRate?.takeIf { it in 8000..768000 } ?: return null
        val frames = session.processedFrames?.takeIf { it >= 0 } ?: return null
        if (engineClosed || session.identity !== live || !session.enabled ||
            runCatching { live.enabled && live.hasControl() }.getOrDefault(false).not()) return null
        return ParameterRepairRequest(live, rate, frames, target)
    }

    /** Enqueue only; the observer's hash is an identity token, never an audio payload. */
    fun requestPeqParameterResync(session: OutputRuntimeStatus.Session, expectedHash: Int,
        expectedEnabled: Boolean): Boolean {
        val target = currentPeqRepairTarget() ?: return false
        if (target.hash != expectedHash || target.enabled != expectedEnabled) return false
        val request = parameterRepairRequest(session, target) ?: return false
        synchronized(parameterRepairLock) { pendingPeqRepair = request }
        syncWithPreferences()
        return true
    }

    /** The EQ observer bounds retries; this worker will read the original saved bank again. */
    fun requestEqParameterResync(session: OutputRuntimeStatus.Session, expectedHash: Int,
        expectedEnabled: Boolean): Boolean {
        val target = currentEqRepairTarget() ?: return false
        if (target.enabled != expectedEnabled || expectedEnabled && target.hash != expectedHash) return false
        val request = parameterRepairRequest(session, target) ?: return false
        synchronized(parameterRepairLock) { pendingEqRepair = request }
        syncWithPreferences()
        return true
    }

    fun cancelPeqParameterResync(identity: Any) {
        synchronized(parameterRepairLock) {
            if (pendingPeqRepair?.identity === identity) pendingPeqRepair = null
        }
    }

    fun cancelEqParameterResync(identity: Any) {
        synchronized(parameterRepairLock) {
            if (pendingEqRepair?.identity === identity) pendingEqRepair = null
        }
    }

    /** Called only while engineLock is held, after the normal health/control check. */
    private fun consumeParameterRepairs() {
        val requests = synchronized(parameterRepairLock) {
            (pendingPeqRepair to pendingEqRepair).also { pendingPeqRepair = null; pendingEqRepair = null }
        }
        if (requests.first == null && requests.second == null) return
        val live = effect ?: return
        if (runCatching { live.enabled && live.hasControl() }.getOrDefault(false).not()) return
        val frames = live.getParameterInt(131083) ?: return
        val hostRate = live.getParameterInt(131082) ?: return
        fun active(request: ParameterRepairRequest): Boolean = request.identity === live &&
            request.rate == hostRate &&
            (frames > request.frames || request.frames >= 0x70000000 && frames <= 0x0fffffff)
        requests.first?.let { request ->
            if (active(request) && currentPeqRepairTarget() == request.target) fusionPackets.remove(PeqController.PARAMETER)
        }
        requests.second?.let { request ->
            if (active(request) && currentEqRepairTarget() == request.target && Constants.PREF_EQ !in cache.changedNamespaces)
                cache.changedNamespaces.add(Constants.PREF_EQ)
        }
    }

    override fun preferenceSyncFailed() {
        // Fusion and legacy namespaces can both fail in one serialized pass.
        if (preferenceFailureReported) return
        preferenceFailureReported = true
        parameterFailures++
        if (parameterFailures < 4) {
            ViperController.publishStatus(context,"部分参数暂未应用，正在重试")
            scheduleHealthRetry(500)
        } else {
            retryHandler.removeCallbacks(retrySync)
            ViperController.publishStatus(context,"部分参数未应用，已停止自动重试；可重试设置或重新启动音效服务")
        }
    }

    private fun recreateEngine(): Boolean {
        try {
            HighFrequencyController.stopAudition(context)
            FieldController.stopAudition(context)
            DiffController.stopAudition(context)
            CrossfeedController.stopAudition(context,clearSnapshots=true)
            PresetCrossfeedController.stopAudition(context,clearSnapshots=true)
            ReverbController.stopAudition(context,clearSnapshots=true)
            RoomReverbController.stopAudition(context,clearSnapshots=true)
            TubeController.stopAudition(context,clearSnapshots=true)
            AnalogController.stopAudition(context,clearSnapshots=true)
            DynamicController.stopAudition(context,clearSnapshots=true)
            TimbreController.stopAudition(context,clearSnapshots=true)
            CompanderController.stopAudition(context,clearSnapshots=true)
            ToneAdvancedController.stopAudition(context,clearSnapshots=true)
            DdcController.stopAudition(context,clearSnapshots=true)
            ConvolutionController.stopAudition(context)
            WidthController.stopAudition(context)
            SurroundProfessionalRuntime.stop(context)
            effect?.let {SurroundProfessionalRuntime.sessionClosed(context,it)}
            effect?.let { HighFrequencyController.sessionClosed(context, it) }
            effect?.let { FieldController.sessionClosed(context, it) }
            effect?.let { DiffController.sessionClosed(context, it) }
            effect?.let { CrossfeedController.sessionClosed(context,it) }
            effect?.let { PresetCrossfeedController.sessionClosed(context,it) }
        effect?.let { ReverbController.sessionClosed(context,it) }
        effect?.let { RoomReverbController.sessionClosed(context,it) }
        effect?.let { TubeController.sessionClosed(context,it) }
        effect?.let { AnalogController.sessionClosed(context,it) }
        effect?.let { DynamicController.sessionClosed(context,it) }
        effect?.let { TimbreController.sessionClosed(context,it) }
        effect?.let { CompanderController.sessionClosed(context,it) }
        effect?.let { ToneAdvancedController.sessionClosed(context,it) }
        effect?.let { DdcController.sessionClosed(context,it) }
        effect?.let { ConvolutionController.sessionClosed(context,it) }
        effect?.let { WidthController.sessionClosed(context,it) }
            val previous = effect
            effect = null
            previous?.setControlStatusListener(null)
            runCatching { previous?.release() }
            fusionPackets.clear()
            legacyCurveDisabled = false
            legacyBassGain = null
            clearCache()
            effect = createEffect()
            generation++
            power.replaced()
            Timber.i("DSP connection recreated sid=%d generation=%d requestedPower=%s", sessionId, generation, power.desired)
            return true
        }
        catch (ex: IllegalStateException) {
            Timber.e("Failed to re-instantiate JamesDSP effect")
            Timber.e(ex.cause)
            effect = null
            return false
        }
    }

    override fun syncWithPreferences(forceUpdateNamespaces: Array<String>?) {
        if (engineClosed) return
        val targets = if (graphicEqNeedsRetry)
            (forceUpdateNamespaces ?: emptyArray()) + Constants.PREF_GEQ
        else forceUpdateNamespaces
        super.syncWithPreferences(targets)
    }

    private fun sendFusionParameter(parameter: Int, values: IntArray): Boolean {
        // A same-rate RESET or deferred native rebuild can invalidate a working kernel.
        // Re-send professional configurations so the native processors can recover.
        // DDC actions must retain their complete ordered command sequence as well.
        if (parameter != PresetCrossfeedParameters.PACKET && parameter != RoomReverbParameters.PACKET &&
            parameter !in DdcParameters.PACKET..DdcParameters.ORIGINAL &&
            parameter != ToneAdvancedParameters.PACKET && parameter != CompanderParameters.PACKET &&
            parameter != DynamicParameters.PACKET && parameter != TimbreParameters.PACKET &&
            parameter != DynamicPeqProtocol.PARAMETER &&
            parameter != TubeParameters.PACKET && parameter != AnalogParameters.PACKET &&
            fusionPackets[parameter]?.contentEquals(values) == true) return true
        val success = if ((parameter == 1206 || parameter == 1200) && values.size == 1)
            effect.setParameter(parameter, values[0].toShort()) == AudioEffect.SUCCESS
        else effect.setParameterIntArray(parameter, values) == AudioEffect.SUCCESS
        if (success) fusionPackets[parameter] = values.copyOf()
        else fusionPackets.remove(parameter)
        return success
    }

    private fun writeLegacyEnable(enable: Boolean): Boolean {
        val success = effect.setParameter(1210, enable.toShort()) == AudioEffect.SUCCESS
        legacyCurveDisabled = success && !enable
        graphicEqNeedsRetry = !success
        return success
    }

    private fun syncParametricEq(): Boolean {
        if (fusionExtendedCapabilities and PeqProtocol.PRECISE_Q_CAPABILITY == 0) {
            peqNeedsDriver = PreferenceCache.getPreferences(context, Constants.PREF_GEQ)
                .getBoolean(context.getString(R.string.key_geq_enable), false)
            if (peqNeedsDriver && !peqDriverUpgradeNoticeShown) {
                peqDriverUpgradeNoticeShown = true
                retryHandler.post { if (!engineClosed) context.toast(context.getString(R.string.peq_precise_driver_required), false) }
            }
            if (!peqNeedsDriver) peqDriverUpgradeNoticeShown = false
            // An older driver can safely understand only this disabled legacy bank.
            // Isolate the missing precision capability from every other effect.
            return sendFusionParameter(PeqController.PARAMETER, intArrayOf(1, 0, 0))
        }
        peqNeedsDriver = false
        peqDriverUpgradeNoticeShown = false
        return PeqController.sync(context) { parameter, values ->
        // The legacy engine synchronizes asynchronously. Confirm it is bypassed
        // before activating PEQ; a failed write must be retried, never cached.
        if (values[1] == 1 && !legacyCurveDisabled && !writeLegacyEnable(false)) false
        else sendFusionParameter(parameter, values)
        }
    }

    @Synchronized
    private fun syncLegacyBassGain(): Boolean {
        val precise = BassParameters.value(BassController.preferences(context, false).all, BassParameters.maxGain)
        val wholeDb = (precise / 10.0).roundToInt()
        if (legacyBassGain == wholeDb) return true
        val success = effect.setParameter(112, wholeDb.toShort()) == AudioEffect.SUCCESS
        legacyBassGain = if (success) wholeDb else null
        return success
    }

    @Synchronized
    private fun syncDynamicPeq(): Boolean {
        val state = DynamicPeqRepository(context).load()
        dynamicPeqNeedsDriver = dynamicPeqCapability != true && state.enabled
        if (dynamicPeqNeedsDriver && !dynamicPeqUpgradeNoticeShown) {
            dynamicPeqUpgradeNoticeShown = true
            retryHandler.post { if (!engineClosed) context.toast(DynamicPeqController.DRIVER_REQUIRED, false) }
        }
        if (!dynamicPeqNeedsDriver) dynamicPeqUpgradeNoticeShown = false
        return DynamicPeqController.sync(context, dynamicPeqCapability == true) { parameter, values ->
            sendFusionParameter(parameter, values).also { success ->
                dynamicPeqAppliedPayload = if (success) values.copyOf() else null
            }
        }
    }

    @Synchronized
    private fun syncFusionPreferences(): Boolean {
        // Avoid resetting unchanged processors when another control is adjusted.
        val viperOk = ViperController.sync(context, fusionCapabilities, effect ?: this, ::sendFusionParameter)
        val bassOk = if (fusionCapabilities and BassController.CAPABILITY != 0)
            BassController.syncDynamic(context, fusionCapabilities, ::sendFusionParameter)
        else syncLegacyBassGain()
        val toneOk = if(fusionExtendedCapabilities and ToneAdvancedParameters.CAPABILITY!=0)
            ToneAdvancedController.sync(context,fusionExtendedCapabilities,effect ?: this,::sendFusionParameter)
        else ToneController.sync(context,::sendFusionParameter)
        val ddcOk=DdcController.sync(context,fusionExtendedCapabilities,effect ?: this,::sendFusionParameter)
        val surroundOk = SurroundController.sync(context, fusionCapabilities,effect ?: this, ::sendFusionParameter)
        val peqOk = syncParametricEq()
        val dynamicPeqOk = syncDynamicPeq()
        creativeEffectsNeedsDriver = creativeEffectsCapability != true && CreativeEffectsKind.values().any {
            CreativeEffectsController.preferences(context, it).all[it.enableKey] == true
        }
        val creativeEffectsOk = CreativeEffectsController.sync(context, creativeEffectsCapability == true, ::sendFusionParameter)
        expansionEffectsNeedsDriver = ExpansionEffectsKind.values().any {
            SpeakerOptimizationProtocol.supported(it, expansionEffectsCapability, speakerOptimizationCapability, smartAdaptCapability) != true &&
                ExpansionEffectsController.preferences(context, it).all[it.enableKey] == true
        }
        val expansionEffectsOk = ExpansionEffectsController.sync(context, expansionEffectsCapability == true, speakerOptimizationCapability == true, smartAdaptCapability == true, ::sendFusionParameter)
        val convPrefs=ConvolutionController.preferences(context)
        if(!convPrefs.getBoolean(ConvolutionParameters.ENABLED,false))ConvolutionController.stopAudition(context)
        val convolutionOk=if(fusionCapabilities and ConvolutionParameters.CAPABILITY!=0) {
            val configured=sendFusionParameter(ConvolutionParameters.PACKET_ID,ConvolutionParameters.runtimePacket(convPrefs.all))
            val advanced=fusionCapabilities and ConvolutionAdvancedParameters.CAPABILITY==0 || sendFusionParameter(ConvolutionAdvancedParameters.PACKET_ID,
                ConvolutionAdvancedParameters.packet(convPrefs.all,convolutionCommonDelay,ConvolutionController.comparisonTenths))
            val stopped=ConvolutionController.audition!=0 || sendFusionParameter(ConvolutionParameters.AUDITION_ID,intArrayOf(0))
            configured && advanced && stopped
        } else true
        val widthOk=WidthController.sync(context,fusionCapabilities,effect ?: this,::sendFusionParameter)
        val crossfeedOk=PresetCrossfeedController.sync(context,fusionCapabilities,effect ?: this,::sendFusionParameter)
        val roomReverbOk=RoomReverbController.sync(context,fusionCapabilities,effect ?: this,::sendFusionParameter)
        val companderOk=CompanderController.sync(context,fusionCapabilities,effect ?: this,::sendFusionParameter)
        val timbreOk=TimbreController.sync(context,fusionCapabilities,effect ?: this,::sendFusionParameter)
        val output = PreferenceCache.getPreferences(context, Constants.PREF_OUTPUT)
        val balance = (output.all["balance"] as? Number)?.toInt()?.coerceIn(-100, 100) ?: 0
        val balanceOk = sendFusionParameter(FUSION_BALANCE, intArrayOf(balance))
        ViperController.publishStatus(context, convolverFailure ?: if (peqNeedsDriver) context.getString(R.string.peq_precise_driver_required)
        else if (dynamicPeqNeedsDriver) DynamicPeqController.DRIVER_REQUIRED
        else if (creativeEffectsNeedsDriver) CreativeEffectsController.DRIVER_REQUIRED
        else if (expansionEffectsNeedsDriver) ExpansionEffectsController.DRIVER_REQUIRED
        else if (viperOk && toneOk && ddcOk && peqOk && dynamicPeqOk && creativeEffectsOk && expansionEffectsOk && balanceOk && surroundOk && bassOk && convolutionOk && widthOk && crossfeedOk && roomReverbOk && timbreOk && companderOk)
            "参数已同步：参数均衡器 → 动态 PEQ → 扬声器串音抵消 → JamesDSP → MSEB → ViPER"
        else if (!surroundOk && fusionCapabilities and SurroundController.CAPABILITY == 0)
            "扬声器串音抵消需要新版驱动：请安装配套扬声器串音抵消模块并重启手机。"
        else "部分参数写入失败，请检查驱动版本或重新启动音效服务。")
        return viperOk && toneOk && ddcOk && peqOk && dynamicPeqOk && creativeEffectsOk && expansionEffectsOk && balanceOk && surroundOk && bassOk && convolutionOk && widthOk && crossfeedOk && roomReverbOk && timbreOk && companderOk
    }

    override fun close() = closeWhenReleased {}

    /** Completion means the AudioEffect has actually been disabled and released, not just queued. */
    fun closeWhenReleased(onReleased: () -> Unit) {
        val startRelease = synchronized(releaseCompletionLock) {
            if (releaseCompleted) null
            else {
                releaseCallbacks += onReleased
                if (engineClosed) false else { engineClosed = true; true }
            }
        }
        if (startRelease == null) { onReleased(); return }
        if (!startRelease) return
        convolutionMonitor.cancel()
        retryHandler.removeCallbacks(retrySync)
        // Cancel pending work immediately; an IR decode must not make the UI wait for release.
        runCatching { super.close() }.onFailure { Timber.w(it, "Failed to stop engine preparation") }
        runCatching { context.unregisterLocalReceiver(broadcastReceiver) }
        Timber.i("Closing DSP effect sid=%d", sessionId)
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                synchronized(engineLock) {
                    val retiring = effect
                    try {
                        // The regular enabled setter queues a preference sync that close() cancels.
                        // Write the final disable directly after any in-flight transfer has stopped.
                        runCatching { retiring?.setEnabled(false) }
                            .onFailure { Timber.w(it, "Failed to disable retiring effect sid=%d", sessionId) }
                        retiring?.let {
                            SurroundProfessionalRuntime.sessionClosed(context, it)
                            WidthController.sessionClosed(context, it)
                            HighFrequencyController.sessionClosed(context, it)
                            FieldController.sessionClosed(context, it)
                            DiffController.sessionClosed(context, it)
                            CrossfeedController.sessionClosed(context, it)
                            PresetCrossfeedController.sessionClosed(context, it)
                            ReverbController.sessionClosed(context, it)
                            RoomReverbController.sessionClosed(context, it)
                            TubeController.sessionClosed(context, it)
                            AnalogController.sessionClosed(context, it)
                            DynamicController.sessionClosed(context, it)
                            TimbreController.sessionClosed(context, it)
                            CompanderController.sessionClosed(context, it)
                            ToneAdvancedController.sessionClosed(context, it)
                            DdcController.sessionClosed(context, it)
                            ConvolutionController.sessionClosed(context, it)
                        }
                    } finally {
                        runCatching { retiring?.setControlStatusListener(null) }
                        runCatching { retiring?.release() }
                            .onFailure { Timber.w(it, "Failed to release retiring effect sid=%d", sessionId) }
                        effect = null
                    }
                }
            } finally {
                val completed = synchronized(releaseCompletionLock) {
                    releaseCompleted = true
                    releaseCallbacks.toList().also { releaseCallbacks.clear() }
                }
                Timber.i("DSP effect released sid=%d", sessionId)
                completed.forEach { callback ->
                    runCatching(callback).onFailure { Timber.w(it, "Effect release callback failed") }
                }
            }
        }
    }

    override fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean {
        return effect.setParameterFloatArray(
            1500,
            floatArrayOf(threshold, release, postGain)
        ) == AudioEffect.SUCCESS
    }

    override fun onRejectedCompander(enable: Boolean): Boolean =
        enable || effect.setParameter(1200, 0.toShort()) == AudioEffect.SUCCESS

    override fun setCompanderInternal(
        enable: Boolean,
        timeConstant: Float,
        granularity: Int,
        tfTransforms: Int,
        bands: DoubleArray
    ): Boolean {
        if(fusionCapabilities and CompanderParameters.CAPABILITY!=0)return true
        return (effect.setParameterFloatArray(
            115,
            floatArrayOf(timeConstant, granularity.toFloat(), tfTransforms.toFloat()) + bands.map { it.toFloat() }
        ) == AudioEffect.SUCCESS) and (effect.setParameter(1200, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setReverb(enable: Boolean, preset: Int): Boolean {
        var ret = true
        val current=RoomReverbController.preferences(context).all
        val currentEnabled=current[RoomReverbParameters.ENABLED] as? Boolean ?: enable
        if (currentEnabled && fusionCapabilities and RoomReverbParameters.CAPABILITY==0)
            ret = effect.setParameter(128, RoomReverbParameters.capture(current).base.toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1203, currentEnabled.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setCrossfeed(enable: Boolean, mode: Int): Boolean {
        // Fusion already applied the latest complete preset, including its power gate. Never
        // replay a stale legacy snapshot after the user selects another base during sync.
        val current = PresetCrossfeedController.preferences(context).all
        val currentEnabled = current[PresetCrossfeedParameters.ENABLED] as? Boolean ?: enable
        val currentMode = PresetCrossfeedParameters.base(current)
        var ret = true
        if (currentEnabled && fusionCapabilities and PresetCrossfeedParameters.CAPABILITY==0)
            ret = effect.setParameter(188, currentMode.toShort()) == AudioEffect.SUCCESS
        val success = ret and (effect.setParameter(1208, currentEnabled.toShort()) == AudioEffect.SUCCESS)
        if (!success) ViperController.publishStatus(context,"交叉馈送参数未完整应用，当前以驱动保留的可用状态为准；可重试或检查配套驱动")
        return success
    }

    override fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean {
        throw UnsupportedOperationException()
    }

    override fun setBassBoost(enable: Boolean, maxGain: Float): Boolean {
        var ret = true
        // SET 1300 already supplied the precise gain. A later legacy write would overwrite it.
        if (enable && fusionCapabilities and BassController.CAPABILITY == 0) {
            ret = syncLegacyBassGain()
        }
        return ret and (effect.setParameter(1201, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setStereoEnhancement(enable: Boolean, level: Float): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(137, level.roundToInt().toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1204, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setVacuumTube(enable: Boolean, level: Float): Boolean {
        // The new driver receives configuration before power, once, through TimbreController.
        if (fusionCapabilities and TimbreParameters.CAPABILITY != 0) return true
        var ret = true
        if (enable)
            ret = effect.setParameter(150, (level * 1000).roundToInt().toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1206, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setMultiEqualizerInternal(
        enable: Boolean,
        filterType: Int,
        interpolationMode: Int,
        bands: DoubleArray,
    ): Boolean {
        var ret = true

        if (enable) {
            val properties = floatArrayOf(
                filterType.toFloat(),
                if(interpolationMode == 1) 1.0f else -1.0f
            ) + bands.map { it.toFloat() }
            ret = effect.setParameterFloatArray(116, properties) == AudioEffect.SUCCESS
        }

        return ret and (effect.setParameter(1202, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun onDdcSource(path:String) {
        if(ddcSelectedPath!=path)DdcController.stopAudition(context,clearSnapshots=true)
        ddcSelectedPath=path
    }

    override fun onRejectedDdc(enabled:Boolean,path:String,error:String):Boolean {
        ddcSelectedPath=path
        DdcController.publishFile(context,effect ?: this,path,ddcAcceptedPath,ddcAcceptedSha,ddcAcceptedLabel,error)
        return if(enabled)true else setVdcInternal(false,"")
    }

    override fun setVdcInternal(enable: Boolean, vdc: String): Boolean {
        val advanced=fusionExtendedCapabilities and DdcParameters.CAPABILITY!=0
        if(!enable) {
            DdcController.stopAudition(context,clearSnapshots=true)
            return if(advanced)sendFusionParameter(DdcParameters.OFF,intArrayOf(0)) else effect.setParameter(1212,0.toShort())==AudioEffect.SUCCESS
        }
        val source=parseDdcSource(vdc) // Base has already bounded and validated the source.
        val selected=ddcSelectedPath
        fun current():Boolean {
            val values=DdcController.preferences(context).all
            return !engineClosed && values[DdcParameters.ENABLED]==true && values[DdcParameters.FILE]==selected
        }
        if(!current())return true
        val crc=vdc.crc()
        if(ddcFileUnconfirmed || ddcAcceptedSha!=source.sha256 || this.ddcHash!=crc) {
            ddcFileUnconfirmed=true
            DdcController.publishFile(context,effect ?: this,selected,ddcAcceptedPath,ddcAcceptedSha,ddcAcceptedLabel,loading=true)
            val sent=DdcTransfer.send(source.bytes(),
                prepare={effect.setParameterIntArray(8888,it)},
                chunk={effect.setParameter(12001,it)},
                commit={effect.setParameter(10009,1.toShort())},current=::current)
            if(!current())return true // Superseded acceptance never receives an activating config.
            if(sent!=AudioEffect.SUCCESS) {
                val message="DDC 装载未成功，继续上一有效校正（状态 $sent）"
                DdcController.publishFile(context,effect ?: this,selected,ddcAcceptedPath,ddcAcceptedSha,ddcAcceptedLabel,message)
                return true // Rejected user content is not an engine crash/recovery loop.
            }
            if(advanced) {
                val accepted=effect.getParameterInts(DdcParameters.RUNTIME,80)?.let(DdcParameters::decode)?.acceptedSha256
                if(accepted!=source.sha256) {
                    DdcController.publishFile(context,effect ?: this,selected,ddcAcceptedPath,ddcAcceptedSha,ddcAcceptedLabel,"驱动尚未确认当前 DDC 文件，继续上一有效校正")
                    return true
                }
            }
            if(effect.setParameter(25001,crc)!=AudioEffect.SUCCESS) {
                DdcController.publishFile(context,effect ?: this,selected,ddcAcceptedPath,ddcAcceptedSha,ddcAcceptedLabel,"DDC 文件已接受，但缓存确认未完成；正在等待重试")
                return false
            }
        }
        if(!current())return true
        ddcAcceptedPath=selected;ddcAcceptedSha=source.sha256;ddcAcceptedLabel=selected.substringAfterLast('/').substringBeforeLast('.');ddcFileUnconfirmed=false
        DdcController.publishFile(context,effect ?: this,selected,ddcAcceptedPath,ddcAcceptedSha,ddcAcceptedLabel)
        // Professional drivers activate file+power+settings atomically in DdcController.sync.
        return if(advanced)DdcController.sync(context,fusionExtendedCapabilities,effect ?: this,::sendFusionParameter)
        else effect.setParameter(1212,1.toShort())==AudioEffect.SUCCESS
    }

    override fun convolutionSampleRate(): Int = effect.getParameterInt(20001) ?: 0
    override fun convolutionTransferPending(): Boolean = convolutionDeferred
    override fun convolutionFailureIsolated(): Boolean = convolutionTerminalFailure

    private fun convolutionAudioActive(live: AudioEffectHidden): Boolean {
        val hostRate = live.getParameterInt(131082)
        val frames = live.getParameterInt(131083)
        val now = SystemClock.elapsedRealtime()
        return synchronized(convolutionActivityLock) {
            if (convolutionActivityIdentity !== live) {
                convolutionActivityIdentity = live; convolutionPreviousFrames = null; convolutionProgressAt = 0
            }
            if (frames != null && convolutionPreviousFrames != null && frames != convolutionPreviousFrames)
                convolutionProgressAt = now
            convolutionPreviousFrames = frames
            hostRate != null && hostRate in 8000..768000 && convolutionProgressAt > 0 && now - convolutionProgressAt < 750
        }
    }

    private fun convolutionKernelAck(live: AudioEffectHidden): ConvolutionTransferRecovery.KernelAck? =
        ConvolutionTransferRecovery.decodeAck(live.getParameterInts(ConvolutionParameters.BULK_RUNTIME_ID, 32))

    private fun convolutionKernelMatches(live: AudioEffectHidden, target: ConvolutionTransferRecovery.Target): Boolean? =
        if (fusionCapabilities and ConvolutionParameters.CAPABILITY != 0) convolutionKernelAck(live)?.matches(target)
        else live.getParameterInt(30003)?.let { it == target.crc }

    override fun configureConvolutionRuntime(prepared: ConvolutionController.PreparedIr): Boolean {
        convolutionCommonDelay=prepared.commonDelaySamples
        return fusionCapabilities and ConvolutionAdvancedParameters.CAPABILITY==0 || synchronized(this) {
            sendFusionParameter(ConvolutionAdvancedParameters.PACKET_ID,ConvolutionAdvancedParameters.packet(
                ConvolutionController.preferences(context).all,convolutionCommonDelay,ConvolutionController.comparisonTenths))
        }
    }

    override fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int
    ): Boolean {

        if (enable && !convolutionRequestCurrent()) return true
        val liveEffect = effect ?: return false
        if (!enable) {
            convolutionRecovery.reset(); convolutionTarget = null
            convolutionDeferred = false; convolutionConfirmed = false; convolutionTerminalFailure = false; convolverFailure = null
            return liveEffect.setParameter(1205, 0.toShort()) == AudioEffect.SUCCESS
        }
        val target = ConvolutionTransferRecovery.Target(liveEffect, irCrc, irFrames, irChannels, convolutionSampleRate())
        if (convolutionTarget != target) {
            convolutionTarget = target; convolutionTerminalFailure = false; convolverFailure = null
            convolutionConfirmed = false
        }
        convolutionRecovery.select(target)
        val active = convolutionAudioActive(liveEffect)
        val kernelMatches = convolutionKernelMatches(liveEffect, target)
        // A transient/unstable metadata read does not prove a previously acknowledged IR was
        // unloaded. Poll it again without uploading the same kernel over a playing stream.
        if (ConvolutionTransferRecovery.waitForAcknowledgement(kernelMatches, convolutionConfirmed)) {
            convolutionDeferred = true
            return true
        }
        if (kernelMatches != true) {
            convolutionDeferred = true
            when (convolutionRecovery.action(SystemClock.elapsedRealtime(), active)) {
                ConvolutionTransferRecovery.Action.WAIT -> return true
                ConvolutionTransferRecovery.Action.FAILED -> return reportConvolverFailure("连续重试", AudioEffect.ERROR)
                ConvolutionTransferRecovery.Action.ATTEMPT -> Unit
            }
            var stage = "分块准备"
            var partition = 0
            val transferred = liveEffect.setParameterImpulseResponseBuffer(12000, 10004, impulseResponse, irChannels,
                onTransferStage = { name, index -> stage = name; partition = index }) {
                convolutionRequestCurrent() && effect === liveEffect && !engineClosed
            }
            if (!convolutionRequestCurrent() || effect !== liveEffect || engineClosed) return true
            if (transferred != AudioEffect.SUCCESS)
                return deferConvolverFailure("$stage（第 $partition 块）", transferred, active)
            // 30003 is a transport cache token, not proof that the FFT kernel was loaded.
            val committed = liveEffect.setParameter(25003, irCrc)
            if (committed != AudioEffect.SUCCESS) return deferConvolverFailure("缓存确认", committed, active)
            if (convolutionKernelMatches(liveEffect, target) != true)
                return deferConvolverFailure("工作核 CRC 确认", AudioEffect.ERROR_INVALID_OPERATION, active)
        }
        if (!convolutionRequestCurrent() || effect !== liveEffect || engineClosed) return true
        if (liveEffect.getParameterInt(30003) != irCrc) {
            val cacheCommit = liveEffect.setParameter(25003, irCrc)
            if (cacheCommit != AudioEffect.SUCCESS) return deferConvolverFailure("缓存确认", cacheCommit, active)
        }
        val activated = liveEffect.setParameter(1205, 1.toShort())
        if (activated != AudioEffect.SUCCESS) return deferConvolverFailure("激活", activated, active)
        val controls = synchronized(this) {
            ConvolutionController.sync(context, fusionCapabilities, liveEffect, convolutionCommonDelay, ::sendFusionParameter)
        }
        if (!controls) return deferConvolverFailure("运行参数", AudioEffect.ERROR_INVALID_OPERATION, active)
        convolutionRecovery.succeeded()
        convolutionConfirmed = true
        convolutionDeferred = !active
        convolutionTerminalFailure = false
        convolverFailure = null
        ViperController.publishStatus(context, if (active) "卷积工作核已确认并启用" else "脉冲响应已暂存待激活；等待音频流")
        return true
    }

    private fun deferConvolverFailure(stage: String, status: Int, active: Boolean): Boolean {
        convolutionDeferred = true; convolutionConfirmed = false
        Timber.w("IR sid=%d crc=%d frames=%d stage=%s status=%d; quiet retry, active=%s", sessionId,
            convolutionTarget?.crc, convolutionTarget?.frames, stage, status, active)
        return if (convolutionRecovery.failed(SystemClock.elapsedRealtime(), active) == ConvolutionTransferRecovery.Action.FAILED)
            reportConvolverFailure(stage, status)
        else {
            ViperController.publishStatus(context, "脉冲响应已暂存待激活，正在后台静默重试")
            true
        }
    }

    private fun reportConvolverFailure(stage: String, status: Int): Boolean {
        convolutionDeferred = false; convolutionTerminalFailure = true
        val retained = effect?.let(::convolutionKernelAck)?.loaded == true
        if (!retained) effect.setParameter(1205, 0.toShort())
        val message = "脉冲响应${stage}连续重试失败（$status）；" + if (retained) "已保留上一有效工作核" else "当前没有可用工作核，卷积暂时旁路"
        convolverFailure = message
        ViperController.publishStatus(context, message)
        Timber.e(message)
        if (convolutionRecovery.takeFailureNotice()) callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.TransferFailed)
        return false
    }

    @Synchronized
    override fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean {
        // The old curve/AutoEQ processor is retired. Imported legacy strings
        // remain in the profile archive only and cannot take over this switch.
        return writeLegacyEnable(false)
    }

    @Synchronized
    override fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean {
        liveprogContentRejected = false
        val prevCrc = this.liveprogHash
        val currentCrc = script.crc()

        Timber.i("Liveprog hash before: $prevCrc, current: $currentCrc")
        if (prevCrc != currentCrc && enable) {
            val transfer = LiveprogScriptTransfer.send(script,
                prepare = { effect.setParameterIntArray(8888, it) },
                chunk = { effect.setParameter(12001, it) },
                commit = { effect.setParameter(10010, 1.toShort()) })
            val compileResult = if (transfer.committed && supportsRealtimeLiveprogParameters())
                effect.getParameterInt(LiveprogProtocol.COMPILE_RESULT) else null
            if (transfer.status != AudioEffect.SUCCESS || (compileResult != null && compileResult <= 0) ||
                (supportsRealtimeLiveprogParameters() && transfer.committed && compileResult == null)) {
                liveprogContentRejected = transfer.committed && compileResult != null && compileResult <= 0
                callbacks?.onLiveprogResult(compileResult?.takeIf { it <= 0 } ?: 0, name,
                    context.getString(R.string.liveprog_compile_retained, compileResult ?: transfer.status))
                return false
            }
            if (effect.setParameter(25002, currentCrc) != AudioEffect.SUCCESS) return false
            invalidateLiveprogParameters()
            callbacks?.onLiveprogResult(1, name, null)
        }

        return effect.setParameter(1213, enable.toShort()) == AudioEffect.SUCCESS
    }

    override fun supportsRealtimeLiveprogParameters(): Boolean =
        fusionExtendedCapabilities and LiveprogProtocol.REALTIME_CAPABILITY != 0

    override fun prepareLiveprogScript(name: String, source: String): String =
        if (supportsRealtimeLiveprogParameters()) super.prepareLiveprogScript(name, source) else source

    override fun setLiveprogParameterInternal(name: String, value: Double): Boolean {
        val packet = LiveprogProtocol.parameter(name, value) ?: return false
        return effect.setParameter(LiveprogProtocol.PARAMETER, packet) == AudioEffect.SUCCESS
    }

    // Feature support
    override fun supportsEelVmAccess(): Boolean { return false }
    override fun supportsCustomCrossfeed(): Boolean { return false }

    // EEL VM utilities (unavailable)
    override fun enumerateEelVariables(): ArrayList<EelVmVariable> { return arrayListOf() }
    override fun manipulateEelVariable(name: String, value: Float): Boolean { return false }
    override fun freezeLiveprogExecution(freeze: Boolean) {}

    // Status
    fun expansionEffectsSupported(kind: ExpansionEffectsKind): Boolean? = if (engineClosed || effect == null) null
        else SpeakerOptimizationProtocol.supported(kind, expansionEffectsCapability, speakerOptimizationCapability, smartAdaptCapability)

    internal fun expansionIdentityMatches(identity: Any): Boolean = !engineClosed && effect === identity

    fun expansionMatchProfile(): IntArray? = synchronized(engineLock) {
        if (engineClosed || expansionEffectsCapability != true) return@synchronized null
        effect?.getParameterInts(132042, 64)
    }
    fun expansionInvalidateLearning() = synchronized(engineLock) {
        fusionPackets.remove(196761)
        fusionPackets.remove(ExpansionEffectsKind.MATCH_EQ.parameter)
    }

    fun expansionCommand(parameter: Int, words: IntArray): Boolean = synchronized(engineLock) {
        val supported = when (parameter) {
            SpeakerOptimizationProtocol.PARAMETER -> speakerOptimizationCapability
            SmartAdaptProtocol.PARAMETER -> smartAdaptCapability
            else -> expansionEffectsCapability
        }
        if (engineClosed || supported != true) return@synchronized false
        sendFusionParameter(parameter, words)
    }

    fun expansionEffectsRuntime(kind: ExpansionEffectsKind): Pair<Any, ExpansionEffectsRuntime>? = synchronized(engineLock) {
        if (expansionEffectsSupported(kind) != true) return@synchronized null
        val live = effect ?: return@synchronized null
        val words = live.getParameterInts(kind.query, ExpansionEffectsParameters.RUNTIME_WORDS) ?: return@synchronized null
        val runtime = ExpansionEffectsParameters.decode(kind, words) ?: return@synchronized null
        if (effect !== live) return@synchronized null
        val expected = ExpansionEffectsController.packet(context, kind)
        val sent = fusionPackets[kind.parameter] ?: return@synchronized null
        if (ExpansionEffectsParameters.hash(kind, sent) != ExpansionEffectsParameters.hash(kind, expected) || runtime.enabled != (expected[1] == 1)) return@synchronized null
        live to runtime.copy(engineEnabled = runCatching { live.enabled }.getOrDefault(false) && words[11] and 1 != 0)
    }

    fun creativeEffectsSupported(): Boolean? = if (engineClosed || effect == null) null else creativeEffectsCapability

    /** Read the production effect on the caller's worker; no extra observer DSP is created. */
    fun creativeEffectsRuntime(kind: CreativeEffectsKind): Pair<Any, CreativeEffectsRuntime>? = synchronized(engineLock) {
        if (engineClosed || creativeEffectsCapability != true) return@synchronized null
        val live = effect ?: return@synchronized null
        val words = live.getParameterInts(kind.query, CreativeEffectsParameters.RUNTIME_WORDS) ?: return@synchronized null
        val runtime = CreativeEffectsParameters.decode(kind, words) ?: return@synchronized null
        if (effect !== live) return@synchronized null
        val expected = CreativeEffectsController.packet(context, kind)
        if (fusionPackets[kind.parameter]?.contentEquals(expected) != true ||
            runtime.enabled != (expected[1] == 1)) return@synchronized null
        live to runtime.copy(engineEnabled = runCatching { live.enabled }.getOrDefault(false))
    }

    fun dynamicPeqSupported(): Boolean? = if (engineClosed || effect == null) null else dynamicPeqCapability

    /** Query the same effect object that owns this bank; no observer-created DSP instance. */
    fun dynamicPeqRuntime(): Pair<Any, DynamicPeqRuntime>? = synchronized(engineLock) {
        if (engineClosed || dynamicPeqCapability != true) return@synchronized null
        val live = effect ?: return@synchronized null
        val words = live.getParameterInts(DynamicPeqProtocol.RUNTIME_QUERY, DynamicPeqProtocol.RUNTIME_WORDS)
            ?: return@synchronized null
        val runtime = DynamicPeqProtocol.decodeRuntime(words) ?: return@synchronized null
        if (effect !== live) return@synchronized null
        val expected = DynamicPeqProtocol.payload(DynamicPeqRepository(context).load())
        if (dynamicPeqAppliedPayload?.contentEquals(expected) != true || runtime.enabled != (expected[1] == 1) ||
            runtime.bandCount != expected[2]) return@synchronized null
        live to runtime.copy(engineEnabled = runCatching { live.enabled }.getOrDefault(false))
    }

    private var diagnosticsEffect: AudioEffectHidden? = null
    private var diagnosticsAvailable: Boolean? = null

    /** Optional alpha10 diagnostics; older protocol-5 drivers keep working without rate reporting. */
    fun outputRuntimeSession(): OutputRuntimeStatus.Session? {
        val liveEffect = effect ?: return null
        if (diagnosticsEffect !== liveEffect) {
            diagnosticsEffect = liveEffect
            diagnosticsAvailable = null
        }
        // Probe unsupported optional queries once per effect, avoiding a log failure every second.
        val rate = if (diagnosticsAvailable != false) liveEffect.getParameterInt(131082) else null
        if (diagnosticsAvailable == null) diagnosticsAvailable = rate != null
        val frames = if (diagnosticsAvailable == true) liveEffect.getParameterInt(131083) else null
        if (frames == null && diagnosticsAvailable == true) scheduleHealthRetry()
        val active = runCatching { liveEffect.enabled }.getOrElse { scheduleHealthRetry(); false }
        return OutputRuntimeStatus.Session(liveEffect, active,
            rate?.takeIf { it in 8000..768000 }, frames?.takeIf { it >= 0 })
    }

    /** Called from the existing background status poll; unsupported drivers are never queried. */
    fun bassRuntimeReading(session: OutputRuntimeStatus.Session): BassRuntimeStatus.Reading? {
        val liveEffect = effect ?: return null
        if (session.identity !== liveEffect) return null
        val supported = fusionCapabilities and BassController.CAPABILITY != 0
        return BassRuntimeStatus.Reading(liveEffect, session.enabled, supported, session.processedFrames,
            if (supported && session.enabled) liveEffect.getParameterInt(BassController.FREQUENCY_QUERY) else null,
            if (supported && session.enabled) liveEffect.getParameterInt(BassController.GAIN_QUERY) else null)
    }

    fun visualizationTelemetrySupported(): Boolean = !engineClosed &&
        fusionExtendedCapabilities and NativeVisualizationTelemetry.CAPABILITY != 0

    internal fun liveprogPcmSupported(): Boolean = !engineClosed &&
        fusionExtendedCapabilities and me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogPcmTelemetry.CAPABILITY != 0

    /** A GET renews only the observation lease; no effect, script or audition is changed. */
    internal fun liveprogPcmSnapshot(session: OutputRuntimeStatus.Session): me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogPcmTelemetry.Snapshot? {
        val live = effect ?: return null
        if (!liveprogPcmSupported() || session.identity !== live) return null
        val rate = session.hostRate?.takeIf { it in 8000..768000 } ?: return null
        val protocol = me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogPcmTelemetry
        return live.getParameterInts(protocol.QUERY, protocol.WORDS)?.let { protocol.decode(it, rate) }
    }

    /** PEQ observation is independent of its power switch and every temporary audition. */
    fun peqPcmSupported(): Boolean = !engineClosed &&
        fusionExtendedCapabilities and me.timschneeberger.rootlessjamesdsp.peq.PeqPcmTelemetry.CAPABILITY != 0

    /** Same-frame host-rate PEQ input/output; this GET renews only the short observation lease. */
    fun peqPcmSnapshot(session: OutputRuntimeStatus.Session): me.timschneeberger.rootlessjamesdsp.peq.PeqPcmTelemetry.Snapshot? {
        val live = effect ?: return null
        if (!peqPcmSupported() || session.identity !== live || !session.enabled) return null
        val rate = session.hostRate?.takeIf { it in 8000..768000 } ?: return null
        val protocol = me.timschneeberger.rootlessjamesdsp.peq.PeqPcmTelemetry
        return live.getParameterInts(protocol.QUERY, protocol.WORDS)?.let { protocol.decode(it, rate) }
    }

    /** Capability-gated 1079 read-only tap directly around the original multimodal equalizer. */
    fun eqPcmSupported(): Boolean = !engineClosed &&
        fusionExtendedCapabilities and me.timschneeberger.rootlessjamesdsp.eq.EqPcmTelemetry.CAPABILITY != 0

    fun eqPcmSnapshot(session: OutputRuntimeStatus.Session): me.timschneeberger.rootlessjamesdsp.eq.EqPcmTelemetry.Snapshot? {
        val live = effect ?: return null
        if (!eqPcmSupported() || session.identity !== live || !session.enabled) return null
        val hostRate = session.hostRate?.takeIf { it in 8000..768000 } ?: return null
        val protocol = me.timschneeberger.rootlessjamesdsp.eq.EqPcmTelemetry
        return live.getParameterInts(protocol.QUERY, protocol.WORDS)?.let { protocol.decode(it, hostRate) }
    }

    /** Independent observation switch: bypasses preset sync and never changes an audition. */
    fun setVisualizationTelemetry(module: Int, enabled: Boolean): Boolean {
        val live = effect ?: return false
        if (!visualizationTelemetrySupported() || module !in 1..3) return false
        return live.setParameterIntArray(NativeVisualizationTelemetry.ARM,
            intArrayOf(module, if (enabled) 1 else 0)) == AudioEffect.SUCCESS
    }

    /** Effective configuration is readable while a module is off; no native calls on the UI thread. */
    fun highFrequencyRuntimeReading(session: OutputRuntimeStatus.Session): HighFrequencyController.Reading? {
        val liveEffect = effect ?: return null
        if (session.identity !== liveEffect || fusionCapabilities and HighFrequencyController.CAPABILITY == 0) return null
        val rate = session.hostRate?.takeIf { it in 8000..768000 } ?: return null
        val targets = (131086..131092).map { liveEffect.getParameterInt(it) ?: return null }
        if (listOf(0, 1, 2, 5, 6).any { targets[it] <= 0 } || targets[3] < 0 || targets[4] < 0) return null
        // First-generation drivers must never receive the second-generation queries.
        val advanced = if (fusionCapabilities and HighFrequencyAdvancedParameters.CAPABILITY != 0)
            (131094..131102).map { liveEffect.getParameterInt(it) }.takeIf { values ->
                values.all { it != null && it >= 0 } && values[5] in listOf(0, 1, 2, 4) &&
                    values[6] in listOf(0, 32) && values[7]!! <= 1800 && values[8]!! <= 1800
            }?.filterNotNull().orEmpty() else emptyList()
        val observing = NativeVisualizationTelemetry.isEnabled(this, NativeVisualizationTelemetry.Module.CLARITY) ||
            NativeVisualizationTelemetry.isEnabled(this, NativeVisualizationTelemetry.Module.SPECTRUM)
        val visualization = if (observing && session.enabled && visualizationTelemetrySupported())
            liveEffect.getParameterInts(NativeVisualizationTelemetry.ENERGY_QUERY, NativeVisualizationTelemetry.ENERGY_WORDS)
                ?.let { NativeVisualizationTelemetry.decodeEnergy(it, rate) } else null
        return HighFrequencyController.Reading(liveEffect, session.enabled, rate, targets,
            liveEffect.getParameterInt(131093), advanced, session.processedFrames,
            spectrumEnergyDbfs = visualization?.spectrumEnergyDbfs,
            clarityEnhancementDb = visualization?.clarityEnhancementDb, visualization = visualization)
    }

    fun fieldRuntimeReading(session: OutputRuntimeStatus.Session): FieldController.Reading? {
        val liveEffect = effect ?: return null
        if (session.identity !== liveEffect || fusionCapabilities and FieldController.CAPABILITY == 0) return null
        val rate = session.hostRate?.takeIf { it in 8000..768000 } ?: return null
        val reading = FieldRuntimeReader.reading(fusionCapabilities,liveEffect,session.enabled,rate,session.processedFrames) { liveEffect.getParameterInt(it) }
            ?: return null
        val visualization = if (session.enabled && visualizationTelemetrySupported() &&
            NativeVisualizationTelemetry.isEnabled(this, NativeVisualizationTelemetry.Module.FIELD))
            liveEffect.getParameterInts(NativeVisualizationTelemetry.FIELD_QUERY, NativeVisualizationTelemetry.FIELD_WORDS)
                ?.let { NativeVisualizationTelemetry.decodeField(it, rate) } else null
        return reading.copy(visualization = visualization)
    }

    fun crossfeedRuntimeReading(session:OutputRuntimeStatus.Session):CrossfeedController.Reading? {
        val live=effect ?: return null
        if(session.identity !== live || fusionCapabilities and CrossfeedParameters.CAPABILITY==0)return null
        return CrossfeedController.Reading(live,session.enabled,session.processedFrames,session.hostRate ?: 0,
            live.getParameterInts(CrossfeedParameters.RUNTIME,20)?.let(CrossfeedParameters::decode),
            diagnostics=if(fusionCapabilities and CrossfeedAdvancedParameters.CAPABILITY!=0 && CrossfeedAdvancedParameters.on(CrossfeedController.preferences(context).all,CrossfeedAdvancedParameters.DIAGNOSTICS))
                live.getParameterInts(CrossfeedAdvancedParameters.RUNTIME,212)?.let(CrossfeedAdvancedParameters::decode)else null)
    }
    fun presetCrossfeedRuntimeReading(session:OutputRuntimeStatus.Session):PresetCrossfeedController.Reading? {
        val live=effect ?: return null
        if(session.identity !== live || fusionCapabilities and PresetCrossfeedParameters.CAPABILITY==0)return null
        return PresetCrossfeedController.Reading(live,session.enabled,session.processedFrames,session.hostRate ?: 0,
            live.getParameterInts(PresetCrossfeedParameters.RUNTIME,24)?.let(PresetCrossfeedParameters::decode))
    }
    fun roomReverbRuntimeReading(session:OutputRuntimeStatus.Session):RoomReverbController.Reading? {
        val live=effect ?: return null
        if(session.identity !== live || fusionCapabilities and RoomReverbParameters.CAPABILITY==0)return null
        return RoomReverbController.Reading(live,session.enabled,session.processedFrames,session.hostRate ?: 0,
            live.getParameterInts(RoomReverbParameters.RUNTIME,32)?.let(RoomReverbParameters::decode))
    }
    /** A read-only observation lease; unsupported drivers never receive the PCM query. */
    fun roomReverbPcmSupported(): Boolean = !engineClosed &&
        fusionCapabilities and RoomReverbParameters.CAPABILITY != 0 &&
        fusionExtendedCapabilities and me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbPcmTelemetry.CAPABILITY != 0

    /** GET renews only the native display capture lease, never an audio parameter or audition. */
    fun roomReverbPcmSnapshot(session: OutputRuntimeStatus.Session): me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbPcmTelemetry.Snapshot? {
        val live = effect ?: return null
        if (session.identity !== live || !session.enabled || !roomReverbPcmSupported()) return null
        val rate = session.hostRate?.takeIf { it in 8000..768000 } ?: return null
        return live.getParameterInts(me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbPcmTelemetry.QUERY,
            me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbPcmTelemetry.WORDS)
            ?.let { me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbPcmTelemetry.decode(it, rate) }
    }
    fun tubeRuntimeReading(session:OutputRuntimeStatus.Session):TubeController.Reading? {
        val live=effect ?: return null
        if(session.identity !== live || fusionCapabilities and TubeParameters.CAPABILITY==0)return null
        return TubeController.Reading(live,session.enabled,session.processedFrames,session.hostRate ?: 0,
            live.getParameterInts(TubeParameters.RUNTIME,32)?.let(TubeParameters::decode))
    }
    /** Optional GET-only display capture; original quality, meters and A/B state remain untouched. */
    fun tubePcmSupported(): Boolean = !engineClosed &&
        fusionCapabilities and TubeParameters.CAPABILITY != 0 &&
        fusionExtendedCapabilities and me.timschneeberger.rootlessjamesdsp.fusion.TubePcmTelemetry.CAPABILITY != 0

    /** One synchronous PCM window; GET only renews its temporary native observation lease. */
    fun tubePcmSnapshot(session: OutputRuntimeStatus.Session): me.timschneeberger.rootlessjamesdsp.fusion.TubePcmTelemetry.Snapshot? {
        val live = effect ?: return null
        if (session.identity !== live || !session.enabled || !tubePcmSupported()) return null
        val rate = session.hostRate?.takeIf { it in 8000..768000 } ?: return null
        return live.getParameterInts(me.timschneeberger.rootlessjamesdsp.fusion.TubePcmTelemetry.QUERY,
            me.timschneeberger.rootlessjamesdsp.fusion.TubePcmTelemetry.WORDS)
            ?.let { me.timschneeberger.rootlessjamesdsp.fusion.TubePcmTelemetry.decode(it, rate) }
    }
    fun analogRuntimeReading(session:OutputRuntimeStatus.Session):AnalogController.Reading? {
        val live=effect ?: return null
        if(session.identity !== live || fusionCapabilities and AnalogParameters.CAPABILITY==0)return null
        return AnalogController.Reading(live,session.enabled,session.processedFrames,session.hostRate ?: 0,
            live.getParameterInts(AnalogParameters.RUNTIME,40)?.let(AnalogParameters::decode))
    }
    /** Optional analog observation capability; no original processing parameter is changed. */
    fun analogPcmSupported(): Boolean = !engineClosed &&
        fusionCapabilities and AnalogParameters.CAPABILITY != 0 &&
        fusionExtendedCapabilities and me.timschneeberger.rootlessjamesdsp.fusion.AnalogPcmTelemetry.CAPABILITY != 0

    /** GET returns synchronous raw/processed/output/aligned-dry PCM and renews only its lease. */
    fun analogPcmSnapshot(session: OutputRuntimeStatus.Session): me.timschneeberger.rootlessjamesdsp.fusion.AnalogPcmTelemetry.Snapshot? {
        val live = effect ?: return null
        if (session.identity !== live || !session.enabled || !analogPcmSupported()) return null
        val rate = session.hostRate?.takeIf { it in 8000..768000 } ?: return null
        return live.getParameterInts(me.timschneeberger.rootlessjamesdsp.fusion.AnalogPcmTelemetry.QUERY,
            me.timschneeberger.rootlessjamesdsp.fusion.AnalogPcmTelemetry.WORDS)
            ?.let { me.timschneeberger.rootlessjamesdsp.fusion.AnalogPcmTelemetry.decode(it, rate) }
    }

    /** Optional spectrum observation capability; no original processing parameter is changed. */
    fun spectrumPcmSupported(): Boolean = !engineClosed &&
        fusionCapabilities and HighFrequencyController.CAPABILITY != 0 &&
        fusionExtendedCapabilities and me.timschneeberger.rootlessjamesdsp.fusion.SpectrumPcmTelemetry.CAPABILITY != 0

    /** GET returns synchronous raw/processed/output/aligned-dry PCM and renews only its lease. */
    fun spectrumPcmSnapshot(session: OutputRuntimeStatus.Session): me.timschneeberger.rootlessjamesdsp.fusion.SpectrumPcmTelemetry.Snapshot? {
        val live = effect ?: return null
        if (session.identity !== live || !session.enabled || !spectrumPcmSupported()) return null
        val rate = session.hostRate?.takeIf { it in 8000..768000 } ?: return null
        return live.getParameterInts(me.timschneeberger.rootlessjamesdsp.fusion.SpectrumPcmTelemetry.QUERY,
            me.timschneeberger.rootlessjamesdsp.fusion.SpectrumPcmTelemetry.WORDS)
            ?.let { me.timschneeberger.rootlessjamesdsp.fusion.SpectrumPcmTelemetry.decode(it, rate) }
    }

    /** Optional timbre observation capability; no original processing parameter is changed. */
    fun timbrePcmSupported(): Boolean = !engineClosed &&
        fusionCapabilities and TimbreParameters.CAPABILITY != 0 &&
        fusionExtendedCapabilities and me.timschneeberger.rootlessjamesdsp.fusion.TimbrePcmTelemetry.CAPABILITY != 0

    /** GET returns synchronous raw/processed/output/aligned-dry PCM and renews only its lease. */
    fun timbrePcmSnapshot(session: OutputRuntimeStatus.Session): me.timschneeberger.rootlessjamesdsp.fusion.TimbrePcmTelemetry.Snapshot? {
        val live = effect ?: return null
        if (session.identity !== live || !session.enabled || !timbrePcmSupported()) return null
        val rate = session.hostRate?.takeIf { it in 8000..768000 } ?: return null
        return live.getParameterInts(me.timschneeberger.rootlessjamesdsp.fusion.TimbrePcmTelemetry.QUERY,
            me.timschneeberger.rootlessjamesdsp.fusion.TimbrePcmTelemetry.WORDS)
            ?.let { me.timschneeberger.rootlessjamesdsp.fusion.TimbrePcmTelemetry.decode(it, rate) }
    }

    /** Optional dynamic observation capability; no original processing parameter is changed. */
    fun dynamicPcmSupported(): Boolean = !engineClosed &&
        fusionCapabilities and DynamicParameters.CAPABILITY != 0 &&
        fusionExtendedCapabilities and me.timschneeberger.rootlessjamesdsp.fusion.DynamicPcmTelemetry.CAPABILITY != 0

    /** GET returns synchronous raw/processed/output/aligned-dry PCM and renews only its lease. */
    fun dynamicPcmSnapshot(session: OutputRuntimeStatus.Session): me.timschneeberger.rootlessjamesdsp.fusion.DynamicPcmTelemetry.Snapshot? {
        val live = effect ?: return null
        if (session.identity !== live || !session.enabled || !dynamicPcmSupported()) return null
        val rate = session.hostRate?.takeIf { it in 8000..768000 } ?: return null
        return live.getParameterInts(me.timschneeberger.rootlessjamesdsp.fusion.DynamicPcmTelemetry.QUERY,
            me.timschneeberger.rootlessjamesdsp.fusion.DynamicPcmTelemetry.WORDS)
            ?.let { me.timschneeberger.rootlessjamesdsp.fusion.DynamicPcmTelemetry.decode(it, rate) }
    }

    /** Optional compander observation capability; no original processing parameter is changed. */
    fun companderPcmSupported(): Boolean = !engineClosed &&
        fusionCapabilities and CompanderParameters.CAPABILITY != 0 &&
        fusionExtendedCapabilities and me.timschneeberger.rootlessjamesdsp.fusion.CompanderPcmTelemetry.CAPABILITY != 0

    /** GET returns synchronous raw/processed/output/aligned-dry PCM and renews only its lease. */
    fun companderPcmSnapshot(session: OutputRuntimeStatus.Session): me.timschneeberger.rootlessjamesdsp.fusion.CompanderPcmTelemetry.Snapshot? {
        val live = effect ?: return null
        if (session.identity !== live || !session.enabled || !companderPcmSupported()) return null
        val rate = session.hostRate?.takeIf { it in 8000..768000 } ?: return null
        return live.getParameterInts(me.timschneeberger.rootlessjamesdsp.fusion.CompanderPcmTelemetry.QUERY,
            me.timschneeberger.rootlessjamesdsp.fusion.CompanderPcmTelemetry.WORDS)
            ?.let { me.timschneeberger.rootlessjamesdsp.fusion.CompanderPcmTelemetry.decode(it, rate) }
    }

    fun companderGainSnapshot(session: OutputRuntimeStatus.Session): me.timschneeberger.rootlessjamesdsp.fusion.CompanderGainTelemetry.Snapshot? {
        val live = effect ?: return null
        if (session.identity !== live || !session.enabled || !companderPcmSupported()) return null
        val rate = session.hostRate?.takeIf { it in 8000..768000 } ?: return null
        return live.getParameterInts(me.timschneeberger.rootlessjamesdsp.fusion.CompanderGainTelemetry.QUERY,
            me.timschneeberger.rootlessjamesdsp.fusion.CompanderGainTelemetry.WORDS)
            ?.let { me.timschneeberger.rootlessjamesdsp.fusion.CompanderGainTelemetry.decode(it, rate) }
    }
    /** Optional ddc observation capability; no original processing parameter is changed. */
    fun ddcPcmSupported(): Boolean = !engineClosed &&
        fusionExtendedCapabilities and DdcParameters.CAPABILITY != 0 &&
        fusionExtendedCapabilities and me.timschneeberger.rootlessjamesdsp.fusion.DdcPcmTelemetry.CAPABILITY != 0

    /** GET returns synchronous raw/processed/output/aligned-dry PCM and renews only its lease. */
    fun ddcPcmSnapshot(session: OutputRuntimeStatus.Session): me.timschneeberger.rootlessjamesdsp.fusion.DdcPcmTelemetry.Snapshot? {
        val live = effect ?: return null
        if (session.identity !== live || !session.enabled || !ddcPcmSupported()) return null
        val rate = session.hostRate?.takeIf { it in 8000..768000 } ?: return null
        return live.getParameterInts(me.timschneeberger.rootlessjamesdsp.fusion.DdcPcmTelemetry.QUERY,
            me.timschneeberger.rootlessjamesdsp.fusion.DdcPcmTelemetry.WORDS)
            ?.let { me.timschneeberger.rootlessjamesdsp.fusion.DdcPcmTelemetry.decode(it, rate) }
    }

    /** Optional convolution observation capability; no original processing parameter is changed. */
    fun convolutionPcmSupported(): Boolean = !engineClosed &&
        fusionCapabilities and ConvolutionParameters.CAPABILITY != 0 &&
        fusionExtendedCapabilities and me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionPcmTelemetry.CAPABILITY != 0

    /** GET returns synchronous raw/processed/output/aligned-dry PCM and renews only its lease. */
    fun convolutionPcmSnapshot(session: OutputRuntimeStatus.Session): me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionPcmTelemetry.Snapshot? {
        val live = effect ?: return null
        if (session.identity !== live || !session.enabled || !convolutionPcmSupported()) return null
        val rate = session.hostRate?.takeIf { it in 8000..768000 } ?: return null
        return live.getParameterInts(me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionPcmTelemetry.QUERY,
            me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionPcmTelemetry.WORDS)
            ?.let { me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionPcmTelemetry.decode(it, rate) }
    }

    fun dynamicRuntimeReading(session:OutputRuntimeStatus.Session):DynamicController.Reading? {
        val live=effect ?: return null
        if(session.identity !== live || fusionCapabilities and DynamicParameters.CAPABILITY==0)return null
        val combined=live.getParameterInts(DynamicParameters.SNAPSHOT,248)
        return DynamicController.Reading(live,session.enabled,session.processedFrames,session.hostRate ?: 0,
            combined?.copyOfRange(0,48)?.let(DynamicParameters::decode),
            response=combined?.copyOfRange(48,248)?.let(DynamicParameters::decodeResponse))
    }

    fun ddcRuntimeReading(session:OutputRuntimeStatus.Session):DdcController.Reading? {
        val live=effect ?: return null
        if(session.identity !== live || fusionExtendedCapabilities and DdcParameters.CAPABILITY==0)return null
        val combined=live.getParameterInts(DdcParameters.SNAPSHOT,312)
        return DdcController.Reading(live,session.enabled,session.processedFrames,session.hostRate ?: 0,
            combined?.copyOfRange(0,80)?.let(DdcParameters::decode),
            response=combined?.copyOfRange(80,312)?.let(DdcParameters::decodeResponse))
    }

    fun toneAdvancedRuntimeReading(session:OutputRuntimeStatus.Session):ToneAdvancedController.Reading? {
        val live=effect ?: return null
        if(session.identity !== live || fusionExtendedCapabilities and ToneAdvancedParameters.CAPABILITY==0)return null
        val combined=live.getParameterInts(ToneAdvancedParameters.SNAPSHOT,264)
        return ToneAdvancedController.Reading(live,session.enabled,session.processedFrames,session.hostRate ?: 0,
            combined?.copyOfRange(0,64)?.let(ToneAdvancedParameters::decode),
            response=combined?.copyOfRange(64,264)?.let(ToneAdvancedParameters::decodeResponse))
    }

    fun companderRuntimeReading(session:OutputRuntimeStatus.Session):CompanderController.Reading? {
        val live=effect ?: return null
        if(session.identity !== live || fusionCapabilities and CompanderParameters.CAPABILITY==0)return null
        val combined=live.getParameterInts(CompanderParameters.SNAPSHOT,280)
        return CompanderController.Reading(live,session.enabled,session.processedFrames,session.hostRate ?: 0,
            combined?.copyOfRange(0,80)?.let(CompanderParameters::decode),
            response=combined?.copyOfRange(80,280)?.let(CompanderParameters::decodeResponse))
    }

    fun timbreRuntimeReading(session:OutputRuntimeStatus.Session):TimbreController.Reading? {
        val live=effect ?: return null
        if(session.identity !== live || fusionCapabilities and TimbreParameters.CAPABILITY==0)return null
        return TimbreController.Reading(live,session.enabled,session.processedFrames,session.hostRate ?: 0,
            live.getParameterInts(TimbreParameters.RUNTIME,48)?.let(TimbreParameters::decode))
    }
    fun reverbRuntimeReading(session:OutputRuntimeStatus.Session):ReverbController.Reading? {
        val live=effect ?: return null
        if(session.identity !== live || fusionCapabilities and ReverbParameters.CAPABILITY==0)return null
        return ReverbController.Reading(live,session.enabled,session.processedFrames,session.hostRate ?: 0,
            live.getParameterInts(ReverbParameters.RUNTIME,32)?.let(ReverbParameters::decode),
            advanced=if(fusionCapabilities and me.timschneeberger.rootlessjamesdsp.fusion.ReverbAdvancedParameters.CAPABILITY!=0)
                live.getParameterInts(me.timschneeberger.rootlessjamesdsp.fusion.ReverbAdvancedParameters.RUNTIME,16)
                    ?.let(me.timschneeberger.rootlessjamesdsp.fusion.ReverbAdvancedParameters::decode)else null)
    }
    fun diffRuntimeReading(session: OutputRuntimeStatus.Session): DiffController.Reading? {
        val liveEffect=effect ?: return null
        if(session.identity !== liveEffect || fusionCapabilities and DiffController.CAPABILITY == 0)return null
        val rate=session.hostRate?.takeIf { it in 8000..768000 } ?: return null
        if(fusionCapabilities and DiffAdvancedParameters.CAPABILITY!=0)
            return DiffController.Reading(liveEffect,session.enabled,rate,session.processedFrames,
                liveEffect.getParameterInts(DiffAdvancedParameters.BULK_RUNTIME,84)?.let(DiffRuntimeReader::decodeBulk))
        return DiffRuntimeReader.reading(fusionCapabilities,liveEffect,session.enabled,rate,session.processedFrames) { liveEffect.getParameterInt(it) }
    }

    fun convolutionRuntimeReading(session: OutputRuntimeStatus.Session): ConvolutionController.Reading? {
        val live=effect ?: return null
        if(session.identity !== live || fusionCapabilities and ConvolutionParameters.CAPABILITY==0)return null
        return ConvolutionController.Reading(live,session.enabled,session.hostRate ?: 0,session.processedFrames,
            live.getParameterInts(ConvolutionParameters.BULK_RUNTIME_ID,32)?.let(ConvolutionRuntimeReader::decode),
            advanced=if(fusionCapabilities and ConvolutionAdvancedParameters.CAPABILITY!=0)
                live.getParameterInts(ConvolutionAdvancedParameters.RUNTIME_ID,20)?.let(me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionAdvancedRuntime::decode) else null)
    }

    fun widthRuntimeReading(session: OutputRuntimeStatus.Session): WidthController.Reading? {
        val live=effect ?: return null
        if(session.identity !== live || fusionCapabilities and WidthParameters.CAPABILITY==0)return null
        return WidthController.Reading(live,session.enabled,session.processedFrames,live.getParameterInts(WidthParameters.RUNTIME,32)?.let(WidthRuntime::decode),
            advanced=if(fusionCapabilities and me.timschneeberger.rootlessjamesdsp.fusion.WidthAdvancedParameters.CAPABILITY!=0)
                live.getParameterInts(131216,176)?.let(me.timschneeberger.rootlessjamesdsp.fusion.WidthAdvancedRuntime::decode) else null)
    }
    fun surroundRuntimeReading(session:OutputRuntimeStatus.Session):SurroundProfessionalRuntime.Reading? {
        val live=effect ?: return null
        if(session.identity !== live || fusionCapabilities and SurroundController.CAPABILITY==0)return null
        return SurroundProfessionalRuntime.Reading(live,session.enabled,session.processedFrames,session.hostRate,
            if(fusionCapabilities and SurroundProfessionalParameters.CAPABILITY!=0)live.getParameterInts(SurroundProfessionalParameters.RUNTIME,32)?.let(SurroundProfessionalRuntime::decode)else null,
            listening=if(fusionCapabilities and me.timschneeberger.rootlessjamesdsp.fusion.SurroundListeningParameters.CAPABILITY!=0)live.getParameterInts(me.timschneeberger.rootlessjamesdsp.fusion.SurroundListeningParameters.RUNTIME,24)?.let(me.timschneeberger.rootlessjamesdsp.fusion.SurroundListeningParameters::decode)else null)
    }

    val pid: Int
        get() = effect.getParameterInt(20002) ?: -1
    val isPidValid: Boolean
        get() = pid > 0
    val isSampleRateAbnormal: Boolean
        get() = sampleRate <= 0
    val paramCommitCount: Int
        get() = effect.getParameterInt(19998) ?: -1
    val isPresetInitialized: Boolean
        get() = paramCommitCount > 0
    val bufferLength: Int
        get() = effect.getParameterInt(19999) ?: -1
    val allocatedBlockLength: Int
        get() = effect.getParameterInt(20000) ?: -1
    val graphicEqHash: Int
        get() = effect.getParameterInt(30000) ?: -1
    val ddcHash: Int
        get() = effect.getParameterInt(30001) ?: -1
    val liveprogHash: Int
        get() = effect.getParameterInt(30002) ?: -1
    val convolverHash: Int
        get() = effect.getParameterInt(30003) ?: -1

    enum class PluginState {
        Unavailable,
        Available,
        NotReady,
        Unsupported
    }

    companion object {
        private val EFFECT_TYPE_CUSTOM = UUID.fromString("30306067-9400-4d53-9000-000000000000")
        private val EFFECT_JAMESDSP = UUID.fromString("30306067-9400-4d53-9000-000000000001")
        private const val FUSION_PROTOCOL = 131080
        private const val FUSION_CAPABILITIES = 131081
        private const val FUSION_BALANCE = 131074
        private const val REQUIRED_PROTOCOL = 5
        private const val DRIVER_NOT_READY = "驱动暂未就绪，请稍后重试连接。"
        @Volatile private var protocolVerified = false

        fun isPluginInstalled(context: Context): PluginState {
            // A successful handshake already identifies this boot's installed module;
            // avoid allocating another AIDL effect for every power or service command.
            if (protocolVerified) return PluginState.Available
            // AIDL can omit custom effects from queryEffects. Probe the unique UUID and handshake.
            return try {
                // A capability check must never attach to global output mix/session 0.
                // This unique positive probe has no AudioTrack and is never enabled.
                val sessionId=(context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                    ?.generateAudioSessionId() ?: return PluginState.NotReady
                if(sessionId<=0)return PluginState.NotReady
                val probe = AudioEffectHidden(EFFECT_TYPE_CUSTOM, EFFECT_JAMESDSP, 0, sessionId)
                try {
                    when (DriverProtocolHandshake.classify(probe.getParameterInt(FUSION_PROTOCOL),
                        probe.getParameterInt(FUSION_CAPABILITIES), REQUIRED_PROTOCOL, 15)) {
                        DriverProtocolHandshake.State.Available -> {
                            protocolVerified = true
                            PluginState.Available
                        }
                        DriverProtocolHandshake.State.NotReady -> PluginState.NotReady
                        DriverProtocolHandshake.State.Unsupported -> PluginState.Unsupported
                    }
                } finally { probe.release() }
            } catch (e: Exception) {
                Timber.e("isPluginInstalled: exception raised")
                Timber.e(e)
                if (e is IllegalArgumentException) PluginState.Unavailable else PluginState.NotReady
            }
        }
    }
}
