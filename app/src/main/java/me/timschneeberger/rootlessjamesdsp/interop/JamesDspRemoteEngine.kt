package me.timschneeberger.rootlessjamesdsp.interop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.AudioEffectHidden
import me.timschneeberger.rootlessjamesdsp.fusion.ToneController
import me.timschneeberger.rootlessjamesdsp.fusion.ViperController
import me.timschneeberger.rootlessjamesdsp.peq.PeqController
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.model.ProcessorMessage
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.getParameterInt
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
                Constants.ACTION_SAMPLE_RATE_UPDATED -> syncWithPreferences(arrayOf(Constants.PREF_CONVOLVER))
                Constants.ACTION_PREFERENCES_UPDATED -> syncWithPreferences()
                Constants.ACTION_SERVICE_RELOAD_LIVEPROG -> syncWithPreferences(arrayOf(Constants.PREF_LIVEPROG))
                Constants.ACTION_SERVICE_HARD_REBOOT_CORE -> rebootEngine()
                Constants.ACTION_SERVICE_SOFT_REBOOT_CORE -> { clearCache(); syncWithPreferences() }
            }
        }
    }

    private val fusionPackets = mutableMapOf<Int, IntArray>()
    private var legacyCurveDisabled = false
    @Volatile private var graphicEqNeedsRetry = false
    @Volatile private var convolverFailure: String? = null
    var effect: AudioEffectHidden? = createEffect()

    override var enabled: Boolean
        set(value) { effect?.enabled = value }
        get() = effect?.enabled ?: false

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
        filter.addAction(Constants.ACTION_SERVICE_HARD_REBOOT_CORE)
        filter.addAction(Constants.ACTION_SERVICE_SOFT_REBOOT_CORE)
        context.registerLocalReceiver(broadcastReceiver, filter)
    }

    private fun createEffect(): AudioEffectHidden {
        return try {
            AudioEffectHidden(EFFECT_TYPE_CUSTOM, EFFECT_JAMESDSP, priority, sessionId).also {
                if (it.getParameterInt(FUSION_PROTOCOL) != REQUIRED_PROTOCOL ||
                    ((it.getParameterInt(FUSION_CAPABILITIES) ?: 0) and 15) != 15) {
                    it.release()
                    throw IllegalStateException("DSP音效 驱动协议不匹配，请安装与 APK 同一版本的融合模块。")
                }
                ViperController.publishStatus(context, "已连接 DSP音效：参数均衡器 + JamesDSP + MSEB + ViPER")
            }
        } catch (e: Exception) {
            ViperController.publishStatus(context, "连接失败：请安装 DSP音效 融合模块并重启手机。旧 JamesDSP / ViPER 模块不能驱动本应用。")
            Timber.e("Failed to create DSP音效 effect")
            Timber.e(e)
            throw IllegalStateException(e)
        }
    }

    private fun checkEngine() {
        if (!isPidValid) {
            Timber.e("PID ($pid) for session $sessionId invalid. Engine probably crashed or detached.")
            context.toast("Engine crashed. Rebooting JamesDSP.", false)
            rebootEngine()
        }

        if (isSampleRateAbnormal) {
            Timber.e("PID ($pid) for session $sessionId invalid. Engine crashed.")
            context.toast("Abnormal sampling rate. Rebooting JamesDSP.", false)
            rebootEngine()
        }
    }

    private fun rebootEngine() {
        try {
            val wasEnabled = effect?.enabled ?: false
            effect?.release()
            fusionPackets.clear()
            legacyCurveDisabled = false
            clearCache()
            effect = createEffect()
            effect?.enabled = wasEnabled
            syncFusionPreferences()
            super.syncWithPreferences(null)
        }
        catch (ex: IllegalStateException) {
            Timber.e("Failed to re-instantiate JamesDSP effect")
            Timber.e(ex.cause)
            effect = null
            return
        }
    }

    override fun syncWithPreferences(forceUpdateNamespaces: Array<String>?) {
        if(effect == null) {
            Timber.d("Rejecting update due to disposed engine")
            return
        }

        checkEngine()
        val targets = if (graphicEqNeedsRetry)
            (forceUpdateNamespaces ?: emptyArray()) + Constants.PREF_GEQ
        else forceUpdateNamespaces
        syncFusionPreferences()
        super.syncWithPreferences(targets)
    }

    private fun sendFusionParameter(parameter: Int, values: IntArray): Boolean {
        if (fusionPackets[parameter]?.contentEquals(values) == true) return true
        val success = effect.setParameterIntArray(parameter, values) == AudioEffect.SUCCESS
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

    private fun syncParametricEq(): Boolean = PeqController.sync(context) { parameter, values ->
        // The legacy engine synchronizes asynchronously. Confirm it is bypassed
        // before activating PEQ; a failed write must be retried, never cached.
        if (values[1] == 1 && !legacyCurveDisabled && !writeLegacyEnable(false)) false
        else sendFusionParameter(parameter, values)
    }

    @Synchronized
    private fun syncFusionPreferences(): Boolean {
        // Avoid resetting unchanged processors when another control is adjusted.
        val viperOk = ViperController.sync(context, ::sendFusionParameter)
        val toneOk = ToneController.sync(context, ::sendFusionParameter)
        val peqOk = syncParametricEq()
        val output = PreferenceCache.getPreferences(context, Constants.PREF_OUTPUT)
        val balance = (output.all["balance"] as? Number)?.toInt()?.coerceIn(-100, 100) ?: 0
        val balanceOk = sendFusionParameter(FUSION_BALANCE, intArrayOf(balance))
        ViperController.publishStatus(context, convolverFailure ?: if (viperOk && toneOk && peqOk && balanceOk)
            "参数已同步：参数均衡器 → JamesDSP → MSEB → ViPER"
        else "部分参数写入失败，请检查驱动版本或重新启动音效服务。")
        return peqOk
    }

    override fun close() {
        context.unregisterLocalReceiver(broadcastReceiver)
        effect?.release()
        effect = null
        super.close()
    }

    override fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean {
        return effect.setParameterFloatArray(
            1500,
            floatArrayOf(threshold, release, postGain)
        ) == AudioEffect.SUCCESS
    }

    override fun setCompanderInternal(
        enable: Boolean,
        timeConstant: Float,
        granularity: Int,
        tfTransforms: Int,
        bands: DoubleArray
    ): Boolean {
        return (effect.setParameterFloatArray(
            115,
            floatArrayOf(timeConstant, granularity.toFloat(), tfTransforms.toFloat()) + bands.map { it.toFloat() }
        ) == AudioEffect.SUCCESS) and (effect.setParameter(1200, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setReverb(enable: Boolean, preset: Int): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(128, preset.toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1203, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setCrossfeed(enable: Boolean, mode: Int): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(188, mode.toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1208, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean {
        throw UnsupportedOperationException()
    }

    override fun setBassBoost(enable: Boolean, maxGain: Float): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(112, maxGain.roundToInt().toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1201, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setStereoEnhancement(enable: Boolean, level: Float): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(137, level.roundToInt().toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1204, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setVacuumTube(enable: Boolean, level: Float): Boolean {
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

    override fun setVdcInternal(enable: Boolean, vdc: String): Boolean {
        val prevCrc = this.ddcHash
        val currentCrc = vdc.crc()

        Timber.i("VDC hash before: $prevCrc, current: $currentCrc")
        if (prevCrc != currentCrc && enable) {
            effect.setParameterCharBuffer(12001, 10009, vdc)
            effect.setParameter(25001, currentCrc) // Commit hash
        }

        return effect.setParameter(1212, enable.toShort()) == AudioEffect.SUCCESS
    }

    override fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int
    ): Boolean {

        val prevCrc = this.convolverHash

        Timber.i("Convolver hash before: $prevCrc, current: $irCrc")
        if (prevCrc != irCrc && enable) {
            val transferred = effect.setParameterImpulseResponseBuffer(12000, 10004, impulseResponse, irChannels)
            if (transferred != AudioEffect.SUCCESS) {
                return reportConvolverFailure("上传", transferred)
            }
            val committed = effect.setParameter(25003, irCrc)
            if (committed != AudioEffect.SUCCESS) {
                return reportConvolverFailure("提交", committed)
            }
        }

        val activated = effect.setParameter(1205, enable.toShort())
        if (activated != AudioEffect.SUCCESS) return reportConvolverFailure("切换", activated)
        if (convolverFailure != null) {
            convolverFailure = null
            ViperController.publishStatus(context, if (enable) "卷积已启用" else "卷积已关闭")
        }
        return true
    }

    private fun reportConvolverFailure(stage: String, status: Int): Boolean {
        effect.setParameter(1205, 0.toShort())
        val message = "脉冲响应${stage}失败（$status）：卷积暂时旁路，请重新开关卷积或选择较短的脉冲响应重试。"
        convolverFailure = message
        ViperController.publishStatus(context, message)
        Timber.e(message)
        callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.TransferFailed)
        return false
    }

    @Synchronized
    override fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean {
        // The old curve/AutoEQ processor is retired. Imported legacy strings
        // remain in the profile archive only and cannot take over this switch.
        return writeLegacyEnable(false)
    }

    override fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean {
        val prevCrc = this.liveprogHash
        val currentCrc = script.crc()

        Timber.i("Liveprog hash before: $prevCrc, current: $currentCrc")
        if (prevCrc != currentCrc && enable) {
            effect.setParameterCharBuffer(12001, 10010, script)
            effect.setParameter(25002, currentCrc) // Commit hash
        }

        return effect.setParameter(1213, enable.toShort()) == AudioEffect.SUCCESS
    }

    // Feature support
    override fun supportsEelVmAccess(): Boolean { return false }
    override fun supportsCustomCrossfeed(): Boolean { return false }

    // EEL VM utilities (unavailable)
    override fun enumerateEelVariables(): ArrayList<EelVmVariable> { return arrayListOf() }
    override fun manipulateEelVariable(name: String, value: Float): Boolean { return false }
    override fun freezeLiveprogExecution(freeze: Boolean) {}

    // Status
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
        Unsupported
    }

    companion object {
        private val EFFECT_TYPE_CUSTOM = UUID.fromString("30306067-9400-4d53-9000-000000000000")
        private val EFFECT_JAMESDSP = UUID.fromString("30306067-9400-4d53-9000-000000000001")
        private const val FUSION_PROTOCOL = 131080
        private const val FUSION_CAPABILITIES = 131081
        private const val FUSION_BALANCE = 131074
        private const val REQUIRED_PROTOCOL = 5

        fun isPluginInstalled(): PluginState {
            // AIDL can omit custom effects from queryEffects. Probe the unique UUID and handshake.
            return try {
                val probe = AudioEffectHidden(EFFECT_TYPE_CUSTOM, EFFECT_JAMESDSP, 0, 0)
                try {
                    if (probe.getParameterInt(FUSION_PROTOCOL) == REQUIRED_PROTOCOL &&
                        ((probe.getParameterInt(FUSION_CAPABILITIES) ?: 0) and 15) == 15)
                        PluginState.Available
                    else PluginState.Unsupported
                } finally { probe.release() }
            } catch (e: Exception) {
                Timber.e("isPluginInstalled: exception raised")
                Timber.e(e)
                PluginState.Unavailable
            }
        }
    }
}
