package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Straight slopes deliberately show a loop-decay model, not a fabricated measured impulse envelope. */
class ReverbDecayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var reading: ReverbAdvancedParameters.Runtime? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dp = resources.displayMetrics.density; val sp = resources.displayMetrics.scaledDensity
        val text = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        val colors = listOf(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary),
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondary),
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary))
        val left = 40 * sp; val right = width - 12 * dp; val top = 14 * dp; val bottom = height - 30 * sp
        if (right <= left || bottom <= top) return
        fun y(db: Double) = top + (-db.coerceIn(-60.0, 0.0) / 60).toFloat() * (bottom - top)
        paint.textSize = 10 * sp; paint.strokeWidth = dp; paint.pathEffect = null
        for (level in listOf(0, -20, -40, -60)) {
            paint.color = text; paint.alpha = 40
            canvas.drawLine(left, y(level.toDouble()), right, y(level.toDouble()), paint)
            paint.alpha = 255; canvas.drawText("$level dB", 0f, y(level.toDouble()) + 3 * sp, paint)
        }
        val report = reading
        if (report == null) { paint.color = text; canvas.drawText("等待驱动预测", left, top + 25 * sp, paint); return }
        val endMs = report.decayMs.max().coerceAtLeast(200)
        fun x(ms: Double) = left + (ms / endMs).toFloat() * (right - left)
        for (fraction in listOf(0.0, 0.5, 1.0)) {
            val label = String.format(Locale.ROOT, "%.2f s", endMs * fraction / 1000)
            paint.color = text; paint.pathEffect = null
            val at = (x(endMs * fraction) - paint.measureText(label) / 2).coerceIn(0f, width - paint.measureText(label))
            canvas.drawText(label, at, bottom + 22 * sp, paint)
        }
        report.decayMs.forEachIndexed { index, duration ->
            paint.color = colors[index]; paint.strokeWidth = 2 * dp
            paint.pathEffect = when (index) {
                1 -> DashPathEffect(floatArrayOf(8 * dp, 4 * dp), 0f)
                2 -> DashPathEffect(floatArrayOf(2 * dp, 4 * dp), 0f)
                else -> null
            }
            canvas.drawLine(left, y(0.0), x(duration.toDouble()), y(-60.0), paint)
        }
        paint.pathEffect = null
    }
}

class ReverbDecayPreference(context: Context) : Preference(context) {
    private var reading: ReverbAdvancedParameters.Runtime? = null
    init {
        key = "v_reverb_pro_decay_graph"; title = "尾音衰减预测 · 算法模型"
        summary = "等待配套驱动与活动音频。"; layoutResource = R.layout.preference_reverb_decay
        isSelectable = false; isIconSpaceReserved = false
    }
    fun update(next: ReverbAdvancedParameters.Runtime?) {
        if (next == reading) return
        reading = next
        fun seconds(ms: Int) = String.format(Locale.ROOT, "%.2f s", ms / 1000.0)
        summary = if (next == null) "等待配套驱动与活动音频。开启本模块和专业调节后显示。" else {
            val duration = next.decayMs
            val frequencies=listOf(min(80.0,next.data[14]/4.0),sqrt(next.data[14].toDouble()*next.data[15]),
                min(next.sampleRate*.45,max(8000.0,next.data[15]*2.0))).joinToString("／"){"${it.roundToInt()} Hz"}
            "${next.sampleRate} Hz；低频 ${seconds(duration[0])}，中频 ${seconds(duration[1])}，高频 ${seconds(duration[2])}。\n" +
                "参考 ${seconds(next.referenceMs)}；代表频率 $frequencies。\n" +
                "实线／长虚线／点线分别为低／中／高频。横轴为时间，纵轴为相对衰减；−60 dB 为各频段预测时间。\n" +
                "使用当前保存参数的反馈系数估算代表频率衰减，分界 ${next.data[14]}／${next.data[15]} Hz。" +
                "不包括预延迟、避让动态包络、试听切换和后续效果，不代表真实房间测量。"
        }
        notifyChanged()
    }
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.reverb_decay_chart) as ReverbDecayView).apply {
            reading = this@ReverbDecayPreference.reading
            contentDescription = this@ReverbDecayPreference.summary
        }
    }
}
