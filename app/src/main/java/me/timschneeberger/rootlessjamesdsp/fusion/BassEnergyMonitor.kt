package me.timschneeberger.rootlessjamesdsp.fusion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Optional, read-only system-mix capture. This never reads the microphone or changes DSP settings. */
object BassEnergyMonitor {
    /**
     * A fixed-reference relative FFT level, not calibrated dBFS or the bass effect's gain.
     * The 8-bit AS_PLAYED complex coefficients are divided by 128 before averaging bin power.
     * [lowerHz]/[upperHz] are actual included bin centres; [binWidthHz] states the resolution.
     * A quantized-zero FFT uses [DISPLAY_FLOOR_DB] only as a display floor, with [hasSignal] false.
     */
    data class Sample(
        val elapsedRealtimeMs: Long,
        val streamId: Long,
        val levelDb: Double,
        val lowerHz: Double,
        val upperHz: Double,
        val binWidthHz: Double,
        val sampleRateHz: Int,
        val fftSize: Int,
        val hasSignal: Boolean,
    ) {
        val streamIdentity: Any get() = streamId
    }

    sealed class State(val label: String) {
        object Disabled : State("实时监视已关闭")
        object Starting : State("正在连接系统混音采集")
        object Active : State("正在采集系统混音低频相对能量")
        object NoData : State("暂无可捕获的系统混音数据，输出可能绕过采集")
        object PermissionRequired : State("需要音频采集权限才能开启实时监视")
        data class Unavailable(val reason: String) : State("系统混音采集不可用：$reason")
    }

    private class Client(val onSample: (Sample) -> Unit, val onState: (State) -> Unit,
        val subscribedAt: Long = SystemClock.elapsedRealtime())
    private class CaptureFailure(val description: String) : RuntimeException(description)

    private const val LOWER_HZ = 10.0
    private const val UPPER_HZ = 500.0
    private const val CAPTURE_RATE_MILLIHZ = 20_000
    private const val SAMPLE_INTERVAL_MS = 50L
    private const val NO_DATA_TIMEOUT_MS = 2_000L
    const val DISPLAY_FLOOR_DB = -120.0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controlMutex = Mutex()
    private val streamIds = AtomicLong()
    // Subscriber ownership and state delivery belong to the main thread.
    private val clients = linkedMapOf<Any, Client>()
    private var capture: CaptureSession? = null
    private var lastState: State = State.Disabled

    /** Only RESUMED, explicitly enabled subscribers own capture resources. No cached samples replay. */
    fun bind(
        owner: LifecycleOwner,
        context: Context,
        enabled: StateFlow<Boolean>,
        onSample: (Sample) -> Unit,
        onState: (State) -> Unit,
    ) {
        val appContext = context.applicationContext
        val token = Any()
        var deliveredState: State? = null
        val reportState: (State) -> Unit = { state ->
            if (deliveredState != state) {
                deliveredState = state
                onState(state)
            }
        }
        owner.lifecycleScope.launch {
            try {
                owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    enabled.collectLatest { realtime ->
                        if (!realtime) {
                            reportState(State.Disabled)
                            return@collectLatest
                        }
                        clients[token] = Client(onSample, reportState)
                        reportState(if (capture == null) State.Starting else lastState)
                        reconcile(appContext)
                        try {
                            awaitCancellation()
                        } finally {
                            unsubscribe(token, appContext)
                        }
                    }
                }
            } finally {
                unsubscribe(token, appContext)
                reportState(State.Disabled)
            }
        }
    }

    private fun unsubscribe(token: Any, context: Context) {
        val removed = clients.remove(token) ?: return
        removed.onState(State.Disabled)
        if (clients.isEmpty()) {
            // Invalidate delivery immediately, before asynchronous native release finishes.
            capture?.invalidate()
            lastState = State.Disabled
            reconcile(context)
        }
    }

    private fun reconcile(context: Context) {
        scope.launch {
            controlMutex.withLock {
                val previous = capture
                if (clients.isEmpty()) {
                    capture = null
                    previous?.close()
                    return@withLock
                }
                if (previous?.acceptingCallbacks == true) return@withLock
                capture = null
                // An old Visualizer must finish release before a new one attaches to session 0.
                previous?.close()
                if (clients.isEmpty()) return@withLock
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED) {
                    publishState(State.PermissionRequired)
                    return@withLock
                }
                val next = CaptureSession(context)
                capture = next
                publishState(State.Starting)
                next.start()
            }
        }
    }

    private fun publishState(state: State) {
        if (lastState == state) return
        lastState = state
        clients.values.toList().forEach { it.onState(state) }
    }

    private class CaptureSession(private val context: Context) {
        private val accepting = AtomicBoolean(true)
        val acceptingCallbacks: Boolean get() = accepting.get()
        private val closed = CompletableDeferred<Unit>()
        private val pendingSample = AtomicReference<Sample?>()
        private val pendingState = AtomicReference<State?>()
        private val deliveryPosted = AtomicBoolean(false)
        @Volatile private var workerHandler: Handler? = null
        private var visualizer: Visualizer? = null
        private var streamId = streamIds.incrementAndGet()
        private var lastSampleTime: Long? = null
        private var previousRate = 0
        private var previousSize = 0

        private val worker = object : HandlerThread("BassEnergyCapture-$streamId", Process.THREAD_PRIORITY_BACKGROUND) {
            override fun onLooperPrepared() {
                workerHandler = Handler(looper)
                if (accepting.get()) openOnWorker() else releaseOnWorker()
            }
        }
        private val noData = Runnable { enqueue(state = State.NoData) }
        private val deliver = object : Runnable {
            override fun run() {
                if (accepting.get() && capture === this@CaptureSession && clients.isNotEmpty()) {
                    pendingState.getAndSet(null)?.let(::publishState)
                    pendingSample.getAndSet(null)?.let { sample ->
                        clients.values.toList().forEach {
                            if (sample.elapsedRealtimeMs >= it.subscribedAt) it.onSample(sample)
                        }
                    }
                } else {
                    pendingState.set(null)
                    pendingSample.set(null)
                }
                deliveryPosted.set(false)
                if (accepting.get() && (pendingState.get() != null || pendingSample.get() != null))
                    postDelivery()
            }
        }

        fun start() {
            try {
                worker.start()
            } catch (_: RuntimeException) {
                enqueue(state = State.Unavailable("无法启动采集线程"))
                closed.complete(Unit)
            }
        }

        fun invalidate() {
            accepting.set(false)
            pendingSample.set(null)
            pendingState.set(null)
            mainHandler.removeCallbacks(deliver)
        }

        suspend fun close() {
            invalidate()
            if (!closed.isCompleted) workerHandler?.post { releaseOnWorker() }
            // If the thread has not prepared its Looper yet, it observes accepting=false and releases.
            closed.await()
        }

        private fun openOnWorker() {
            try {
                if (!accepting.get()) {
                    releaseOnWorker()
                    return
                }
                // Construction on this Looper also keeps FFT listener work off the UI thread.
                val effect = Visualizer(0)
                visualizer = effect
                if (!accepting.get()) {
                    releaseOnWorker()
                    return
                }
                val range = Visualizer.getCaptureSizeRange()
                if (range.size < 2 || range[0] <= 0 || range[1] < range[0])
                    throw CaptureFailure("设备未提供有效的 FFT 长度")
                var size = 1
                val maximum = minOf(range[1], 2048)
                while (size <= maximum / 2) size *= 2
                if (size < range[0] || size < 4)
                    throw CaptureFailure("设备不支持可用的 FFT 长度")
                requireSuccess(effect.setCaptureSize(size), "无法设置 FFT 长度")
                // NORMALIZED auto-amplifies each capture and hides real playback-level changes.
                requireSuccess(effect.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED), "设备不支持按播放音量采集")
                val rate = minOf(Visualizer.getMaxCaptureRate(), CAPTURE_RATE_MILLIHZ)
                if (rate <= 0) throw CaptureFailure("设备未提供有效的采集速率")
                val listener = object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(effect: Visualizer, waveform: ByteArray, samplingRate: Int) = Unit
                    override fun onFftDataCapture(effect: Visualizer, fft: ByteArray, samplingRate: Int) {
                        if (!accepting.get()) return
                        // Visualizer selects the constructor's Looper, which is this worker thread.
                        consumeFft(fft, samplingRate, SystemClock.elapsedRealtime())
                    }
                }
                requireSuccess(effect.setDataCaptureListener(listener, rate, false, true), "设备无法监听系统混音 FFT")
                requireSuccess(effect.setEnabled(true), "系统未允许启用混音采集")
                workerHandler?.postDelayed(noData, NO_DATA_TIMEOUT_MS)
            } catch (_: SecurityException) {
                enqueue(state = if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED) State.PermissionRequired
                else State.Unavailable("系统未授权此应用捕获输出混音"))
                releaseOnWorker()
            } catch (failure: CaptureFailure) {
                enqueue(state = State.Unavailable(failure.description))
                releaseOnWorker()
            } catch (_: UnsupportedOperationException) {
                enqueue(state = State.Unavailable("设备未提供系统混音 Visualizer"))
                releaseOnWorker()
            } catch (_: RuntimeException) {
                enqueue(state = State.Unavailable("系统拒绝连接输出混音，当前路线可能不支持采集"))
                releaseOnWorker()
            }
        }

        private fun consumeFft(fft: ByteArray, samplingRateMilliHz: Int, now: Long) {
            if (!accepting.get()) return
            if (lastSampleTime?.let { now - it < SAMPLE_INTERVAL_MS } == true) return
            val rateHz = samplingRateMilliHz / 1000.0
            val size = fft.size
            if (rateHz <= 0.0 || size < 4 || size % 2 != 0) {
                enqueue(state = State.Unavailable("当前输出未提供有效的 FFT 数据"))
                return
            }
            val binWidth = rateHz / size
            val first = maxOf(1, ceil(LOWER_HZ / binWidth).toInt())
            val last = minOf(size / 2, floor(UPPER_HZ / binWidth).toInt())
            if (last < first) {
                enqueue(state = State.Unavailable("当前 FFT 分辨率内没有可用的低频采样点"))
                return
            }
            if (previousRate != 0 && (previousRate != samplingRateMilliHz || previousSize != size))
                streamId = streamIds.incrementAndGet()
            previousRate = samplingRateMilliHz
            previousSize = size
            var power = 0.0
            for (bin in first..last) {
                // FFT[0] is DC; FFT[1] is the special real-only Nyquist bin.
                val re = if (bin == size / 2) fft[1].toInt() else fft[2 * bin].toInt()
                val im = if (bin == size / 2) 0 else fft[2 * bin + 1].toInt()
                power += (re.toDouble() * re + im.toDouble() * im) / (128.0 * 128.0)
            }
            val hasSignal = power > 0.0
            val db = if (hasSignal) 10.0 * log10(power / (last - first + 1)) else DISPLAY_FLOOR_DB
            lastSampleTime = now
            workerHandler?.removeCallbacks(noData)
            workerHandler?.postDelayed(noData, NO_DATA_TIMEOUT_MS)
            enqueue(
                state = State.Active,
                sample = Sample(now, streamId, db, first * binWidth, last * binWidth,
                    binWidth, rateHz.toInt(), size, hasSignal),
            )
        }

        private fun enqueue(state: State? = null, sample: Sample? = null) {
            if (!accepting.get()) return
            if (state != null) pendingState.set(state)
            if (sample != null) pendingSample.set(sample)
            postDelivery()
        }

        private fun postDelivery() {
            if (deliveryPosted.compareAndSet(false, true)) mainHandler.post(deliver)
        }

        private fun requireSuccess(status: Int, description: String) {
            if (status != Visualizer.SUCCESS) throw CaptureFailure("$description（$status）")
        }

        private fun releaseOnWorker() {
            if (closed.isCompleted) return
            workerHandler?.removeCallbacksAndMessages(null)
            visualizer?.let { effect ->
                try { effect.setDataCaptureListener(null, 0, false, false) } catch (_: RuntimeException) { }
                try { effect.setEnabled(false) } catch (_: RuntimeException) { }
                try { effect.release() } catch (_: RuntimeException) { }
            }
            visualizer = null
            closed.complete(Unit)
            worker.quitSafely()
        }
    }
}
