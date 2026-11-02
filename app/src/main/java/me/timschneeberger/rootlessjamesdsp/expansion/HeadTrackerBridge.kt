package me.timschneeberger.rootlessjamesdsp.expansion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlin.math.*

/** Only actual Android head trackers qualify. Phone orientation is never substituted. */
object HeadTrackerBridge : SensorEventListener {
    private const val HEAD_TRACKER = 37
    private val thread = HandlerThread("dsp-head-tracker").apply { start() }
    private val handler = Handler(thread.looper)
    private var manager: SensorManager? = null
    private var app: Context? = null
    private var sensor: Sensor? = null
    private var watching = false
    @Volatile private var desired = false
    @Volatile private var connected = false
    @Volatile private var denied = false
    @Volatile private var sampleAt = 0L
    @Volatile private var deviceName = ""
    private val poseLock = Any()
    private val pose = FloatArray(3)
    private val center = FloatArray(3)
    private var epoch = 0
    private var discontinuity = Float.NaN
    private var lastNotify = 0L
    private val dynamic = object : SensorManager.DynamicSensorCallback() {
        override fun onDynamicSensorConnected(value: Sensor) { if(value.type == HEAD_TRACKER) refresh() }
        override fun onDynamicSensorDisconnected(value: Sensor) {
            if(value == sensor) { manager?.unregisterListener(this@HeadTrackerBridge); sensor = null; connected = false; notifyChange(); refresh() }
        }
    }
    fun update(context: Context) {
        app = context.applicationContext
        desired = ExpansionEffectsController.preferences(context, ExpansionEffectsKind.HEADTRACK)
            .getBoolean(ExpansionEffectsKind.HEADTRACK.enableKey, false)
        handler.post { refresh() }
    }
    private fun refresh() {
        val context = app ?: return
        val sm = manager ?: (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.also { manager = it } ?: return
        if(!desired) {
            sm.unregisterListener(this); sensor = null; connected = false
            if(watching) { runCatching { sm.unregisterDynamicSensorCallback(dynamic) }; watching = false }
            return
        }
        if(!watching) { runCatching { sm.registerDynamicSensorCallback(dynamic, handler) }.onSuccess { watching = true } }
        if(sensor != null) return
        val found = runCatching { (sm.getDynamicSensorList(HEAD_TRACKER) + sm.getSensorList(HEAD_TRACKER)).firstOrNull() }.getOrNull()
        if(found == null) { connected = false; return }
        try {
            if(sm.registerListener(this, found, 20_000, handler)) {
                sensor = found; deviceName = found.name; denied = false
                synchronized(poseLock) { center.fill(0f); discontinuity = Float.NaN }
            } else denied = true
        } catch(_: SecurityException) { denied = true }
        notifyChange()
    }
    override fun onSensorChanged(event: SensorEvent) {
        if(!desired || event.sensor.type != HEAD_TRACKER || event.values.size < 3) return
        val rx = event.values[0].toDouble(); val ry = event.values[1].toDouble(); val rz = event.values[2].toDouble()
        if(!rx.isFinite() || !ry.isFinite() || !rz.isFinite()) return
        // Android HEAD_TRACKER reports an angle-axis rotation vector, not sin(angle/2).
        val angle = sqrt(rx*rx + ry*ry + rz*rz)
        val scale = if(angle < 1e-9) .5 else sin(angle*.5)/angle
        val q = floatArrayOf((rx*scale).toFloat(), (ry*scale).toFloat(), (rz*scale).toFloat(), cos(angle*.5).toFloat())
        val rotation = FloatArray(9); val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotation, q)
        SensorManager.getOrientation(rotation, orientation)
        synchronized(poseLock) {
            if(event.values.size >= 7 && event.values[6] != discontinuity) {
                discontinuity = event.values[6]
                for(i in 0..2) center[i] = Math.toDegrees(orientation[i].toDouble()).toFloat()
            }
            for(i in 0..2) pose[i] = Math.toDegrees(orientation[i].toDouble()).toFloat()
            epoch = epoch % 1_999_999 + 1
        }
        sampleAt = SystemClock.elapsedRealtime(); connected = true
        if(sampleAt-lastNotify >= 40) { lastNotify = sampleAt; notifyChange() }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    fun patchPacket(context: Context, kind: ExpansionEffectsKind, packet: IntArray) {
        if(kind != ExpansionEffectsKind.HEADTRACK || packet.size < 12) return
        val live = desired && connected && SystemClock.elapsedRealtime()-sampleAt < 1000
        synchronized(poseLock) {
            for(i in 0..2) {
                var degrees = pose[i]-center[i]
                while(degrees > 180) degrees -= 360
                while(degrees < -180) degrees += 360
                packet[7+i] = (degrees.coerceIn(if(i==1) -90f else -180f, if(i==1) 90f else 180f)*1000).roundToInt()
            }
            packet[10] = if(live) 1000 else 0
            packet[11] = epoch*1000
        }
    }
    fun status(context: Context): String = when {
        denied -> "系统未开放头部跟踪数据 · 请检查耳机与系统的空间音频设置"
        connected && SystemClock.elapsedRealtime()-sampleAt < 1000 -> "已连接：$deviceName"
        sensor != null -> "已找到 $deviceName · 等待头部姿态数据"
        else -> "未连接头部跟踪设备 · 需要系统可识别的头部跟踪耳机或 USB 跟踪器"
    }
    fun recenter(context: Context) {
        synchronized(poseLock) { for(i in 0..2) center[i] = pose[i]; epoch = epoch % 1_999_999 + 1 }
        notifyChange()
    }
    private fun notifyChange() { app?.let { ExpansionEffectsController.notifyChanged(it) } }
}
