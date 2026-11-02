package me.timschneeberger.rootlessjamesdsp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.fusion.OutputRuntimeStatus
import me.timschneeberger.rootlessjamesdsp.fusion.ToneAdvancedController
import me.timschneeberger.rootlessjamesdsp.fusion.ToneAdvancedParameters as P
import me.timschneeberger.rootlessjamesdsp.fusion.ToneChartFocus
import me.timschneeberger.rootlessjamesdsp.fusion.ToneDashboardModel
import me.timschneeberger.rootlessjamesdsp.fusion.ToneDashboardRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder
import java.util.Locale
import kotlin.math.*

/** Native block meters, main-kernel control readings and its static response; no PCM or writes. */
internal class ToneFocusRealtimeChart(context: Context) : View(context) {
    private data class Frame(val time: Long, val source: Any, val session: Int?, val rate: Int,
        val mode: Int, val tools: Int, val sequence: Int, val split: Boolean, val data: List<Int>)
    private data class Curve(val frame: Frame, val data: P.Response)
    private val d = resources.displayMetrics.density
    private val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val dark = ColorUtils.calculateLuminance(surface) < .5
    private val cyan = if (dark) Color.rgb(104,216,236) else Color.rgb(16,124,146)
    private val amber = if (dark) Color.rgb(255,198,118) else Color.rgb(169,102,22)
    private val muted = ColorUtils.setAlphaComponent(ink,155)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = min(9f*resources.displayMetrics.scaledDensity,10f*d); fontFeatureSettings = "tnum"
    }
    private val digits = TextPaint(text).apply { typeface = Typeface.MONOSPACE }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d; color = ColorUtils.setAlphaComponent(ink,40) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f*d; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val plot = RectF()
    private var model: ToneDashboardModel? = null
    private var focus = ToneChartFocus.CORE
    private var focusKey = ""
    private var active = false
    private var frozen = true
    private var blocked = true
    private var breakNext = true
    private var anchor = 0L
    private var current: Frame? = null
    private var curve: Curve? = null
    private var state = "实时采样已关闭"
    private var levelReason = "等待真实输入输出电平"
    private val history = ArrayDeque<Frame>()
    private val main = Handler(Looper.getMainLooper())
    private var held: List<Frame>? = null
    private var heldEnd = 0L
    private var inspected: Frame? = null
    private var dialog: AlertDialog? = null
    private var downX = 0f
    private var downY = 0f
    private var pendingTouch = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val pointNames = listOf("温度","深度","弹性","厚度","人声","女声","低齿","高齿","瞬态","空气")
    private val modes = listOf("正常处理","本模块输入原声","正常处理（含混合）","处理差分","快照 A","快照 B","双快照测量")
    private val longPress = Runnable {
        if (pendingTouch && isShown && !usesResponse() && focus != ToneChartFocus.CORE && history.isNotEmpty()) {
            pendingTouch = false; held = history.toList(); heldEnd = anchor
            parent?.requestDisallowInterceptTouchEvent(true); select(downX)
        }
    }
    private val canDraw: Boolean get() = active && isAttachedToWindow && windowVisibility == VISIBLE
    private fun redraw() { if (canDraw) invalidate() }
    private fun usesResponse(): Boolean = when (focus) {
        ToneChartFocus.TEMPERATURE, ToneChartFocus.DEPTH, ToneChartFocus.PRESENCE,
        ToneChartFocus.SIBILANCE, ToneChartFocus.AIR, ToneChartFocus.RESPONSE -> true
        ToneChartFocus.ELASTIC -> focusKey == P.frequency[3].key || focusKey == P.q[0].key || focusKey == P.elasticStaticWeight.key
        else -> false
    }
    private fun usesGain(): Boolean = focus == ToneChartFocus.ELASTIC || focus == ToneChartFocus.TRANSIENT || focus == ToneChartFocus.WEAK
    fun setConfiguration(value: ToneDashboardModel) {
        val old = model; model = value
        if (old != null && old.settings == value.settings && old.rate == value.rate && old.enabled == value.enabled) return
        // New settings may be warming. Keep genuinely observed main-kernel readings;
        // do not relabel their values as the newly saved target or synthesize a curve.
        if (old != null && old.rate != value.rate) { current = null; curve = null; breakNext = true }
        redraw()
    }
    fun setFocus(value: ToneChartFocus, key: String = "") {
        if (focus == value && focusKey == key) return
        finishInspection(); dialog?.dismiss(); dialog = null; focus = value; focusKey = key; redraw()
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) { finishInspection(); dialog?.dismiss(); dialog = null }
        redraw()
    }
    fun pause(reason: String) { frozen = true; state = reason; breakNext = true; finishInspection(); redraw() }
    fun showPaused(reason: String) = pause(reason)
    private fun same(a: Frame, identity: Any, session: Int?, rate: Int, mode: Int, tools: Int): Boolean =
        a.source === identity && a.session == session && a.rate == rate && a.mode == mode && a.tools == tools
    fun showSample(sample: ToneDashboardRuntimeMonitor.Sample) {
        anchor = max(anchor,sample.elapsedRealtimeMs); frozen = false
        while (history.isNotEmpty() && (history.size > 120 || history.first().time < anchor-20000)) history.removeFirst()
        val mode = ToneAdvancedController.audition
        val tools = if (mode == 0) 0 else ToneAdvancedController.epoch
        val identity = sample.streamIdentity
        val packet = sample.reading?.snapshot
        val hard = !sample.output.connected || sample.output.globalIssue != null || sample.output.activeSessions.size > 1
        blocked = hard
        val data = packet?.data?.takeIf { !hard && sample.unavailableReason == null &&
            sample.reading?.active == true && packet.enabled && packet.ready &&
            it[2] == sample.reading?.rate && it[5] == mode && (mode == 0 || it[11] == tools) }
        val old = current
        if (hard || old != null && (identity != null && (old.source !== identity || old.session != sample.streamSessionId) ||
                old.mode != mode || old.tools != tools || data != null && old.rate != data[2])) {
            current = null; curve = null; breakNext = true
        }
        if (data == null || identity == null) {
            breakNext = true
            if (hard || old == null || anchor-old.time > 1000 || identity != null &&
                !same(old,identity,sample.streamSessionId,packet?.data?.get(2) ?: old.rate,mode,tools)) {
                current = null; curve = null
                state = sample.unavailableReason ?: "等待新的听感音色调节仪表"
            }
            redraw(); return
        }
        val split = breakNext || old == null || !same(old,identity,sample.streamSessionId,data[2],mode,tools) || anchor-old.time > 1000
        if (old != null && !split && old.sequence == data[1]) { redraw(); return }
        val next = Frame(anchor,identity,sample.streamSessionId,data[2],mode,tools,data[1],split,data)
        current = next; history.addLast(next); breakNext = false
        val response = sample.reading?.response
        if (response != null && response.ready && response.rate == data[2] && response.data[1] == data[1]) curve = Curve(next,response)
        else if (model?.settings?.response != true) curve = null
        levelReason = sample.levelUnavailableReason ?: "当前音频块的 RMS / 采样峰值"
        state = when {
            data[11] == 0 && data[62] > 0 -> "新配置预热 ${data[61]}/${data[62]} ms · 当前读数仍为原主配置"
            mode in 4..5 -> "当前输出为快照试听 · 动态控制与响应仍来自主配置"
            mode == 3 -> "当前输出为处理差分 · 主配置控制量独立显示"
            else -> "主配置真实控制量 · 输入输出为当前试听路径"
        }
        redraw()
    }
    private fun label(c: Canvas, value: String, x: Float, y: Float, space: Float, tint: Int = ink, right: Boolean = false, numeric: Boolean = false) {
        if (space <= 0) return
        val p = if (numeric) digits else text
        p.color = tint; p.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
        val left = if (right) x-space else x
        c.save(); c.clipRect(left,y+p.ascent()-d,left+space,y+p.descent()+d)
        c.drawText(if (numeric) value else TextUtils.ellipsize(value,p,space,TextUtils.TruncateAt.END).toString(),x,y,p); c.restore()
    }
    private fun fmt(value: Double?, places: Int = 1): String = value?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT,"%.${places}f",it) } ?: "—"
    private fun raw(value: Int?): Double? = value?.takeIf { it != Int.MIN_VALUE }?.div(100.0)
    private fun maximum(a: Int?,b: Int?): Double? = listOfNotNull(raw(a),raw(b)).maxOrNull()
    private fun axes(c: Canvas, area: RectF, low: Double, high: Double) {
        c.drawRect(area,grid)
        repeat(5) { i -> val x = area.left+area.width()*i/4; c.drawLine(x,area.top,x,area.bottom,grid) }
        repeat(3) { i ->
            val y = area.top+area.height()*i/2; c.drawLine(area.left,y,area.right,y,grid)
            label(c,fmt(high-(high-low)*i/2,0),area.left-3*d,y+3*d,26*d,muted,true)
        }
    }
    private fun trace(c: Canvas, area: RectF, low: Double, high: Double, tint: Int, value: (List<Int>)->Double?) {
        path.rewind(); var joined = false
        val values = held ?: history.toList(); val end = if (held != null) heldEnd else anchor
        for (frame in values) {
            val number = value(frame.data); val x = area.right-(end-frame.time)/20000f*area.width()
            if (number == null || x < area.left) joined = false else {
                val y = area.bottom-((number.coerceIn(low,high)-low)/(high-low)).toFloat()*area.height()
                if (!joined || frame.split) path.moveTo(x,y) else path.lineTo(x,y)
                joined = true
            }
        }
        line.color = ColorUtils.setAlphaComponent(tint,if(frozen)100 else 230)
        c.save(); c.clipRect(area); c.drawPath(path,line); c.restore()
    }
    private fun endLabels(c: Canvas) {
        label(c,"−20s",plot.left,plot.bottom+12*d,plot.width()/2,muted)
        label(c,"现在",plot.right,plot.bottom+12*d,plot.width()/2,muted,true)
    }
    private fun footer(c: Canvas, value: String = state) = label(c,if(frozen) "$state · 已冻结" else value,2*d,height-3*d,width-4*d,muted)
    override fun onDraw(c: Canvas) {
        super.onDraw(c); if(width<80*d || height<80*d) return
        when {
            usesResponse() -> drawResponse(c)
            focus == ToneChartFocus.CORE -> drawAmounts(c)
            usesGain() -> drawGain(c)
            focus == ToneChartFocus.HEADROOM -> drawHeadroom(c)
            else -> drawLevels(c)
        }
    }
    private fun drawGain(c: Canvas) {
        val data = (inspected ?: current)?.data
        label(c,"主配置低音起音 / 全频瞬态 · 当前增益 dB",2*d,10*d,width-4*d)
        label(c,"低音 ${fmt(raw(data?.get(29)))}",2*d,25*d,width*.48f,primary)
        label(c,"瞬态 ${fmt(raw(data?.get(30)))}",width-2*d,25*d,width*.48f,cyan,true)
        val detail = if (focus == ToneChartFocus.WEAK)
            "弱保护 ${if(data?.get(40)==1) "开启" else "关闭"} · 门限 ${fmt(data?.get(39)?.div(10.0))} dBFS"
            else "快/慢包络：低音 ${fmt(data?.get(35)?.div(10.0))}/${data?.get(36) ?: "—"} · 瞬态 ${fmt(data?.get(37)?.div(10.0))}/${data?.get(38) ?: "—"} ms"
        label(c,detail,2*d,39*d,width-4*d,muted)
        plot.set(30*d,49*d,width-7*d,height-27*d)
        if(plot.height()<12*d) return
        axes(c,plot,-12.0,12.0)
        trace(c,plot,-12.0,12.0,primary){raw(it[29])}; trace(c,plot,-12.0,12.0,cyan){raw(it[30])}
        endLabels(c)
        if(current==null) label(c,state,plot.left+3*d,plot.centerY()+3*d,plot.width()-6*d,muted)
        footer(c,"最后样本的原生动态增益 · 零值不代表弱保护已触发")
    }
    private fun drawHeadroom(c: Canvas) {
        val data = (inspected ?: current)?.data
        label(c,"主配置余量 · 自动当前值 / 额外保存目标",2*d,10*d,width-4*d)
        label(c,"自动 ${fmt(raw(data?.get(27)))} dB",2*d,25*d,width*.50f,primary)
        label(c,"额外 ${fmt(data?.get(28)?.div(10.0))} dB",width-2*d,25*d,width*.48f,amber,true)
        label(c,"紫：当前自动衰减 · 橙：额外衰减目标",2*d,39*d,width-4*d,muted)
        plot.set(30*d,49*d,width-7*d,height-27*d)
        if(plot.height()<12*d) return
        axes(c,plot,-96.0,0.0)
        trace(c,plot,-96.0,0.0,primary){raw(it[27])}; trace(c,plot,-96.0,0.0,amber){it[28]/10.0}
        endLabels(c)
        if(current==null) label(c,state,plot.left+3*d,plot.centerY()+3*d,plot.width()-6*d,muted)
        footer(c,"余量是固定/自动预衰减 · 不等于实时限幅 GR")
    }
    private fun drawLevels(c: Canvas) {
        val frame = inspected ?: current; val data = frame?.data
        label(c,"输入 / 实际试听输出 · 当前音频块 RMS dBFS",2*d,10*d,width-4*d)
        val step = (width-35*d)/2
        repeat(2) { index ->
            val tint = if(index==0)cyan else primary
            val value = if(data?.get(12)==1) maximum(data[20+index*2],data[21+index*2]) else null
            val peak = if(data?.get(12)==1) maximum(data[16+index*2],data[17+index*2]) else null
            val x = 30*d+index*step
            label(c,if(index==0)"输入" else "输出",x,25*d,28*d,tint)
            label(c,fmt(value).padStart(7),x+step-8*d,25*d,step-32*d,tint,true,true)
            val bounds = RectF(x,29*d,x+step-10*d,35*d)
            fill.color = ColorUtils.setAlphaComponent(ink,18); c.drawRoundRect(bounds,2*d,2*d,fill)
            if(value!=null){fill.color=ColorUtils.setAlphaComponent(tint,if(frozen)90 else 190);c.drawRoundRect(RectF(bounds.left,bounds.top,bounds.left+bounds.width()*((value.coerceIn(-96.0,6.0)+96)/102).toFloat(),bounds.bottom),2*d,2*d,fill)}
            if(peak!=null){line.color=tint;val px=bounds.left+bounds.width()*((peak.coerceIn(-96.0,6.0)+96)/102).toFloat();c.drawLine(px,bounds.top-d,px,bounds.bottom+d,line)}
        }
        val match = focus == ToneChartFocus.MATCH
        if(match) drawMatch(c,data) else {
            val detail = when(focus){
                ToneChartFocus.MIX -> " · 效果目标 ${fmt(data?.get(32)?.div(10.0))}%"
                ToneChartFocus.LEVEL -> " · 输出目标 ${fmt(data?.get(33)?.div(10.0))} dB"
                else -> " · 峰值 ${fmt(if(data?.get(12)==1)maximum(data[18],data[19])else null)} dBFS"
            }
            label(c,"试听：${frame?.mode?.let{modes.getOrElse(it){"等待确认"}} ?: "等待确认"}$detail",2*d,48*d,width-4*d,muted)
        }
        plot.set(30*d,(if(match)75 else 58)*d,width-7*d,height-27*d)
        if(plot.height()<10*d) return
        axes(c,plot,-96.0,0.0)
        trace(c,plot,-96.0,0.0,cyan){if(it[12]==1)maximum(it[20],it[21])else null}
        trace(c,plot,-96.0,0.0,primary){if(it[12]==1)maximum(it[22],it[23])else null}
        endLabels(c)
        if(data?.get(12)!=1 || data.subList(16,24).all{it==Int.MIN_VALUE})
            label(c,if(frozen)state else levelReason,plot.left+3*d,plot.centerY()+3*d,plot.width()-6*d,muted)
        footer(c,"RMS 取左右较大值 · 峰标为音频块采样峰值")
    }
    private fun drawMatch(c: Canvas,data: List<Int>?) {
        val ab = data?.takeIf{it[11]==ToneAdvancedController.epoch && it[5]==ToneAdvancedController.audition}
        val phase = when(ab?.get(7)){
            1 -> "A/B 预热 ${ab[61]}/${ab[62]} ms"
            2 -> "A/B 测量 ${ab[8]}/${ab[31]} ms"
            3 -> "A/B 测量完成"
            4 -> "A/B 信号不足"
            0 -> "尚未完成 A/B 测量"
            else -> "等待当前工具代的 A/B 读数"
        }
        label(c,phase,2*d,48*d,width-4*d,primary)
        val result=ab?.takeIf{it[7]==3 && ToneAdvancedController.matchingReady()}
        label(c,"固定衰减 A ${fmt(raw(result?.get(9)),2)} / B ${fmt(raw(result?.get(10)),2)} dB",2*d,63*d,width-4*d,muted)
    }
    private fun drawAmounts(c: Canvas) {
        val data = (inspected ?: current)?.data
        label(c,"主配置实际十项作用量 · 点（不是 dB）",2*d,10*d,width-4*d)
        label(c,"新配置预热期间仍显示原主配置 · 点按查看频点",2*d,24*d,width-4*d,muted)
        plot.set(8*d,33*d,width-8*d,height-21*d)
        if(plot.height()<26*d) return
        val rowHeight=plot.height()/2;val step=plot.width()/5
        repeat(10){index ->
            val row=index/5;val column=index%5;val cx=plot.left+(column+.5f)*step
            val top=plot.top+row*rowHeight;val zero=top+rowHeight*.51f
            val amount=data?.get(41+index)?.div(10.0)
            c.drawLine(cx-step*.40f,zero,cx+step*.40f,zero,grid)
            if(amount!=null){val y=zero-(amount/200).toFloat()*rowHeight*.28f;fill.color=ColorUtils.setAlphaComponent(if(amount>=0)primary else amber,if(frozen)85 else 185);c.drawRect(cx-step*.16f,min(zero,y),cx+step*.16f,max(zero,y).coerceAtLeast(min(zero,y)+d),fill)}
            label(c,pointNames[index],cx+step*.43f,top+9*d,step*.90f,ink,true)
            label(c,fmt(amount),cx+step*.43f,top+rowHeight-3*d,step*.90f,primary,true,true)
        }
        if(data==null)label(c,state,plot.left+3*d,plot.centerY()+3*d,plot.width()-6*d,muted)
        footer(c,"显示主配置已派生作用量 · 与 A/B 试听输出分开")
    }
    private fun currentCurve(): Curve? {
        val mode=ToneAdvancedController.audition;val tools=if(mode==0)0 else ToneAdvancedController.epoch
        return curve?.takeIf{(frozen || !blocked && anchor-it.frame.time<=1000) &&
            it.frame.mode==mode && it.frame.tools==tools && (model?.settings?.response==true || frozen) &&
            current?.let{f->same(f,it.frame.source,it.frame.session,it.frame.rate,it.frame.mode,it.frame.tools)}==true}
    }
    private fun upperHz(): Double = min(20000.0,(current?.rate ?: model?.rate ?: 48000)*.43)
    private fun drawResponse(c: Canvas) {
        label(c,"主配置当前静态响应预测 · dB",2*d,10*d,width-4*d)
        label(c,"青：静态滤波 · 紫：含混合 / 余量 / 输出",2*d,24*d,width-4*d,muted)
        plot.set(30*d,36*d,width-7*d,height-27*d)
        if(plot.height()<12*d)return
        axes(c,plot,-48.0,24.0)
        val response=currentCurve();val upper=upperHz().coerceAtLeast(21.0)
        if(response!=null){
            repeat(2){series->
                path.rewind();var joined=false
                for(point in response.data.points){
                    val hz=point[0].toDouble();val value=raw(point[1+series])
                    if(value==null || hz<20 || point[5]!=1){joined=false;continue}
                    val x=plot.left+(ln(hz/20)/ln(upper/20)).toFloat()*plot.width()
                    val y=plot.bottom-((value.coerceIn(-48.0,24.0)+48)/72).toFloat()*plot.height()
                    if(joined)path.lineTo(x,y)else path.moveTo(x,y);joined=true
                }
                line.color=ColorUtils.setAlphaComponent(if(series==0)cyan else primary,if(frozen)100 else 230)
                c.save();c.clipRect(plot);c.drawPath(path,line);c.restore()
            }
        }else label(c,if(frozen)state else if(model?.settings?.response!=true)"请打开原生“静态音色响应与动态控制”" else "等待当前主配置响应",plot.left+3*d,plot.centerY()+3*d,plot.width()-6*d,muted)
        label(c,"20 Hz",plot.left,plot.bottom+12*d,plot.width()/2,muted)
        label(c,"${fmt(upper/1000,1)} kHz",plot.right,plot.bottom+12*d,plot.width()/2,muted,true)
        footer(c,"点按查看频点 · 不含动态起音增益 / A/B 路由，非实测 FFT")
    }
    private fun inspectPoint(x: Float,y: Float) {
        val title: String;val body: String
        if(usesResponse()){
            val response=currentCurve()?:return
            val hz=20*(upperHz()/20).pow(((x-plot.left)/plot.width()).coerceIn(0f,1f).toDouble())
            val point=response.data.points.minByOrNull{abs(ln(it[0].coerceAtLeast(1)/hz))}?:return
            title="主配置预测 · ${point[0]} Hz"
            body="静态滤波 ${fmt(raw(point[1]),2)} dB\n静态混合输出 ${fmt(raw(point[2]),2)} dB\n\n"+
                "含当前滤波系数、自动余量、混合、额外余量和输出的静态预测；不含低音起音 / 瞬态动态增益，也不含 A/B 试听路由。\n"+
                "配置预热交接前仍属于原主配置，不是音乐实测频谱。"
        }else if(focus==ToneChartFocus.CORE){
            val data=current?.data?:return
            val row=(((y-plot.top)/plot.height()).coerceIn(0f,.9999f)*2).toInt()
            val column=(((x-plot.left)/plot.width()).coerceIn(0f,.9999f)*5).toInt()
            val index=(row*5+column).coerceIn(0,9)
            val indices=when(index){0->listOf(0,1);1->listOf(2);2->listOf(3);3->listOf(4);4->listOf(5);5->listOf(6);6->listOf(7);7->listOf(8);9->listOf(9);else->emptyList()}
            title="主配置 · ${pointNames[index]}"
            val frequency=if(indices.isEmpty())"宽带瞬态控制，无独立固定滤波频点"else "滤波目标频率 "+indices.joinToString(" / "){data[51+it].toString()}+" Hz"
            body="实际派生作用量 ${fmt(data[41+index]/10.0,1)} 点\n$frequency\n\n"+
                "这是当前主配置已派生的参数作用量，不是动态增益 dB 或音频能量。新配置热身交接前保留原主配置；试听快照可能使用另一套配置。"
        }else return
        dialog?.dismiss();dialog=GlassAlertDialogBuilder(context).setTitle(title).setMessage(body).setPositiveButton(android.R.string.ok,null).show()
    }
    private fun select(x: Float) {
        val values=held?:return
        if(plot.width()<=0)return
        val time=heldEnd-((plot.right-x)/plot.width()).coerceIn(0f,1f)*20000
        inspected=values.minByOrNull{abs(it.time-time)};redraw()
    }
    private fun finishInspection(){pendingTouch=false;main.removeCallbacks(longPress);held=null;inspected=null;parent?.requestDisallowInterceptTouchEvent(false)}
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{if(!plot.contains(event.x,event.y))return false;downX=event.x;downY=event.y;pendingTouch=true;main.postDelayed(longPress,ViewConfiguration.getLongPressTimeout().toLong());return true}
            MotionEvent.ACTION_MOVE->{if(held!=null)select(event.x)else if(abs(event.x-downX)>slop || abs(event.y-downY)>slop){pendingTouch=false;main.removeCallbacks(longPress)};return true}
            MotionEvent.ACTION_UP->{val inspect=pendingTouch&&(usesResponse()||focus==ToneChartFocus.CORE);finishInspection();performClick();if(inspect)inspectPoint(event.x,event.y);redraw();return true}
            MotionEvent.ACTION_CANCEL->{finishInspection();redraw();return true}
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean {super.performClick();return true}
    override fun onAttachedToWindow(){super.onAttachedToWindow();redraw()}
    override fun onDetachedFromWindow(){dialog?.dismiss();dialog=null;finishInspection();super.onDetachedFromWindow()}
}
