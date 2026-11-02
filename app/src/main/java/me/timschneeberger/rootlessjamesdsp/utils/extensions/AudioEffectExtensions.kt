package me.timschneeberger.rootlessjamesdsp.utils.extensions

import android.media.audiofx.AudioEffect
import android.media.audiofx.AudioEffectHidden
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import kotlin.math.ceil


object AudioEffectExtensions {
    fun AudioEffectHidden?.setParameterCharArray(parameter: Int, value: String): Int {
        this ?: return AudioEffect.ERROR_NO_INIT

        var result = value.toByteArray(Charset.forName("US-ASCII"))
        if (result.size < 256) {
            val zeroPad = 256 - result.size
            result += ByteArray(zeroPad)
        }
        return safeAccess { this.setParameter(parameter, result) }
    }

    fun AudioEffectHidden?.setParameterCharBuffer(parameterSend: Int, parameterCommit: Int, string: String): Int{
        this ?: return AudioEffect.ERROR_NO_INIT

        return me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogScriptTransfer.send(string,
            prepare = { setParameterIntArray(PARAM_CHAR_BUFFER_INFO, it) },
            chunk = { setParameter(parameterSend, it) },
            commit = { safeAccess { this.setParameter(parameterCommit, 1.toShort()) } }
        ).status
    }

    fun AudioEffectHidden?.setParameterImpulseResponseBuffer(
        parameterSend: Int,
        parameterCommit: Int,
        impulseResponse: FloatArray,
        channels: Int,
        onTransferStage: (String, Int) -> Unit = { _, _ -> },
        shouldContinue: () -> Boolean = { true }
    ) : Int {
        this ?: return AudioEffect.ERROR_NO_INIT

        var partition = 0
        return me.timschneeberger.rootlessjamesdsp.interop.ImpulseResponseTransfer.send(
            impulseResponse, channels,
            prepare = { onTransferStage("分块缓冲准备", 0); if(shouldContinue())setParameterIntArray(PARAM_FLOAT_BUFFER_INFO, it) else AudioEffect.ERROR_INVALID_OPERATION },
            chunk = { onTransferStage("分块写入", ++partition); if(shouldContinue())setParameterFloatArray(parameterSend, it) else AudioEffect.ERROR_INVALID_OPERATION },
            commit = { onTransferStage("工作核初始化", partition); if(shouldContinue())safeAccess { this.setParameter(parameterCommit, 1.toShort()) } else AudioEffect.ERROR_INVALID_OPERATION }
        )
    }

    fun AudioEffectHidden?.setParameterFloatArray(parameter: Int, value: FloatArray): Int {
        this ?: return AudioEffect.ERROR_NO_INIT

        val result = ByteArray(value.size * 4)
        val byteDataBuffer = ByteBuffer.wrap(result)
        byteDataBuffer.order(ByteOrder.nativeOrder())
        for (i in value.indices)
            byteDataBuffer.putFloat(value[i])

        return safeAccess { this.setParameter(parameter, result) }
    }

    fun AudioEffectHidden?.setParameterIntArray(parameter: Int, value: IntArray): Int {
        this ?: return AudioEffect.ERROR_NO_INIT

        val result = ByteArray(value.size * 4)
        val byteDataBuffer = ByteBuffer.wrap(result)
        byteDataBuffer.order(ByteOrder.nativeOrder())
        for (i in value.indices)
            byteDataBuffer.putInt(value[i])

        return safeAccess { this.setParameter(parameter, result) }
    }

    fun AudioEffectHidden?.setParameter(param: ByteArray, value: ByteArray): Int {
        this ?: return AudioEffect.ERROR_NO_INIT
        return safeAccess { this.setParameter(param, value) }
    }

    fun AudioEffectHidden?.setParameter(param: Int, value: Int): Int {
        this ?: return AudioEffect.ERROR_NO_INIT
        return safeAccess { this.setParameter(param, value) }
    }

    fun AudioEffectHidden?.setParameter(param: Int, value: ByteArray): Int {
        this ?: return AudioEffect.ERROR_NO_INIT
        return safeAccess { this.setParameter(param, value) }
    }

    fun AudioEffectHidden?.setParameter(param: Int, value: Short): Int {
        this ?: return AudioEffect.ERROR_NO_INIT
        return safeAccess { this.setParameter(param, value) }
    }

    data class IntRead(val status: Int, val value: Int?)
    internal fun decodeIntRead(status: Int, bytes: ByteArray): IntRead =
        IntRead(status, if (status == 4 && bytes.size == 4) ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).int else null)

    fun AudioEffectHidden?.readParameterInt(parameter: Int): IntRead {
        this ?: return IntRead(AudioEffect.ERROR_NO_INIT, null)
        val bytes = ByteArray(4)
        val ret = safeAccess { this.getParameter(parameter, bytes) }
        if (ret != bytes.size) {
            Timber.w("getParameterInt: parameter %d returned %d bytes/status (expected 4)", parameter, ret)
        }
        return decodeIntRead(ret, bytes)
    }
    fun AudioEffectHidden?.getParameterInt(parameter: Int): Int? = readParameterInt(parameter).value
    fun AudioEffectHidden?.getParameterInts(parameter: Int,count: Int): IntArray? {
        this ?: return null
        // These capability-gated, GET-only snapshots have fixed payloads larger
        // than ordinary status packets. Keep every other query's original bound.
        val knownPcmWindow =
            (parameter == me.timschneeberger.rootlessjamesdsp.fusion.SpectrumPcmTelemetry.QUERY &&
                count == me.timschneeberger.rootlessjamesdsp.fusion.SpectrumPcmTelemetry.WORDS) ||
            (parameter == me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbPcmTelemetry.QUERY &&
                count == me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbPcmTelemetry.WORDS) ||
            (parameter == me.timschneeberger.rootlessjamesdsp.fusion.TubePcmTelemetry.QUERY &&
                count == me.timschneeberger.rootlessjamesdsp.fusion.TubePcmTelemetry.WORDS) ||
            (parameter == me.timschneeberger.rootlessjamesdsp.fusion.AnalogPcmTelemetry.QUERY &&
                count == me.timschneeberger.rootlessjamesdsp.fusion.AnalogPcmTelemetry.WORDS) ||
            (parameter == me.timschneeberger.rootlessjamesdsp.fusion.TimbrePcmTelemetry.QUERY &&
                count == me.timschneeberger.rootlessjamesdsp.fusion.TimbrePcmTelemetry.WORDS) ||
            (parameter == me.timschneeberger.rootlessjamesdsp.fusion.DynamicPcmTelemetry.QUERY &&
                count == me.timschneeberger.rootlessjamesdsp.fusion.DynamicPcmTelemetry.WORDS) ||
            (parameter == me.timschneeberger.rootlessjamesdsp.fusion.CompanderPcmTelemetry.QUERY &&
                count == me.timschneeberger.rootlessjamesdsp.fusion.CompanderPcmTelemetry.WORDS) ||
            (parameter == me.timschneeberger.rootlessjamesdsp.fusion.DdcPcmTelemetry.QUERY &&
                count == me.timschneeberger.rootlessjamesdsp.fusion.DdcPcmTelemetry.WORDS) ||
            (parameter == me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionPcmTelemetry.QUERY &&
                count == me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionPcmTelemetry.WORDS) ||
            (parameter == me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogPcmTelemetry.QUERY &&
                count == me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogPcmTelemetry.WORDS) ||
            (parameter == me.timschneeberger.rootlessjamesdsp.peq.PeqPcmTelemetry.QUERY &&
                count == me.timschneeberger.rootlessjamesdsp.peq.PeqPcmTelemetry.WORDS) ||
            (parameter == me.timschneeberger.rootlessjamesdsp.eq.EqPcmTelemetry.QUERY &&
                count == me.timschneeberger.rootlessjamesdsp.eq.EqPcmTelemetry.WORDS)
        if(count !in 1..1024 && !knownPcmWindow)return null
        val bytes=ByteArray(count*4)
        val returned=safeAccess { getParameter(parameter,bytes) }
        if(returned!=bytes.size)return null
        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        return IntArray(count) { buffer.int }
    }

    private fun safeAccess(onTry: () -> Int): Int {
        return try { onTry.invoke() } catch (ex: IllegalStateException) {
            Timber.e("AudioEffect is in invalid state")
            Timber.d(ex)
            AudioEffect.ERROR_NO_INIT
        }
    }

    private const val MAX_CHAR_PARTITION_SIZE = 256
    private const val PARAM_CHAR_BUFFER_INFO = 8888
    private const val PARAM_FLOAT_BUFFER_INFO = 9999
}
