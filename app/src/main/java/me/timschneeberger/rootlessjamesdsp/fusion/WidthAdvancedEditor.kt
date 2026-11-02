package me.timschneeberger.rootlessjamesdsp.fusion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.FrameLayout
import me.timschneeberger.rootlessjamesdsp.R
import com.google.android.material.color.MaterialColors
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import java.util.Locale
import kotlin.math.*

/** Second-round rows share the first-round card, power and fold lifecycle. */
class WidthAdvancedEditor(private val context: Context,private val group: SwitchPreferenceGroup,private val owner: Any) {
    private val p=WidthAdvancedParameters
    private val prefs=WidthController.preferences(context)
    private val rows=linkedMapOf<WidthAdvancedParameters.Entry,Preference>()
    private fun row(key: String,title: String,body: String,order: Int,selectable: Boolean=false)=object : RuntimeStatusPreference(context) {
        override fun onBindViewHolder(holder: PreferenceViewHolder){super.onBindViewHolder(holder);listOf(android.R.id.title,android.R.id.summary).forEach {id->(holder.findViewById(id) as? TextView)?.apply {maxLines=Int.MAX_VALUE;ellipsize=null;setSingleLine(false)}}}
    }.apply {this.key=key;this.title=title;summary=body;this.order=order;isSelectable=selectable;isIconSpaceReserved=false;isPersistent=false;group.addPreference(this)}
    private val actual=StableRuntimeReadoutPreference(context).apply {
        key="width2_actual";title="分段输入／本模块正常输出";summary="等待新版驱动监测"
        order=1310;isPersistent=false;group.addPreference(this)
    }
    private val compare=FieldChoicePreference(context,"width2_compare","临时频段与 A/B 试听",listOf("第二轮试听未启用","仅低频（三段模式）","仅中频（三段模式）","仅高频（三段模式）","仅新增扩宽","参数 A","参数 B"),"退出试听").apply {
        order=1360;setOnPreferenceChangeListener {_,v->val n=v as Int;if(n==0){WidthController.stopAudition(context,owner);true}else WidthController.startAudition(context,owner,n+3)}
    }
    private val saveA=row("width2_save_a","保存当前参数到 A","仅保留在当前页面会话，不写入预设",1330,true)
    private val saveB=row("width2_save_b","保存当前参数到 B","仅保留在当前页面会话，不写入预设",1340,true)
    private val match=object: SwitchPreferenceCompat(context) {
        override fun onBindViewHolder(holder: PreferenceViewHolder){super.onBindViewHolder(holder);listOf(android.R.id.title,android.R.id.summary).forEach {id->(holder.findViewById(id) as? TextView)?.apply {maxLines=Int.MAX_VALUE;ellipsize=null;setSingleLine(false)}}}
    }.apply {key="width2_compare_match";title="A/B 使用有界 RMS 电平匹配";summary="以原声为参考，最多 ±3 dB；试听时统一输出校准为 0 dB。退出后恢复保存参数。";order=1350;isPersistent=false;isIconSpaceReserved=false;setOnPreferenceChangeListener {_,v->WidthController.setCompareMatch(context,v as Boolean);true}}
    private val graph=WidthGraphPreference(context).apply {key="width2_graph";order=1320}
    init {
        p.normalize(prefs)
        val sections=listOf(
            "⑦ 分频段动态宽度" to "根据 Side/Mid 比例压回新增扩宽。三段模式分别调整低／中／高频，全频模式使用全频参数；回收比例针对超过 100% 的部分，收窄设置保留。新处理默认关闭。",
            "⑧ 瞬态扩宽抑制" to "检测 0.5 ms／50 ms 快慢功率包络差，在起音时回收扩宽。三段模式选择作用频段；全频模式整体处理。启动时间为指数响应，无额外预读缓冲。",
            "⑨ 分频段相关度保护" to "高级开关启用时使用本组检测方式，关闭后恢复第一轮保护开关的设置。全频阈值、迟滞、回收时间、恢复时间和强度沿用第一轮；分段阈值只在三段独立保护使用。检测对象为尚未回收扩宽的候选。",
            "⑩ 扩宽增量均衡" to "两段钟形参数均衡只作用于新增 Side，原有基准 Side 和 Mid 路径保留；宽度未超过 100% 时无新增部分可处理。频率按 James 实际采样率限制。",
            "⑪ 电平匹配高级控制" to "先开启第一轮 RMS 自动电平匹配，再启用高级响应。分析时间和补偿上限沿用第一轮；静音时冻结匹配目标，衰减和恢复采用独立时间常数。",
            "⑫ 分段监测与 A/B 试听" to "图表开关默认关闭。监测位于本模块正常输出、临时试听之前；Side/Mid 比值不是主观宽度百分比。临时试听在收起、滚出、离页、暂停、停用或连接结束后退出。A/B 仅比较专业 M/S 参数。"
        )
        sections.forEachIndexed {i,(title,body)->row("width2_group${i+1}",title,body,700+i*100)}
        p.entries.forEachIndexed {i,e->
            val c=e.control;val preference=when(e.kind) {
                "number"->BassNumericPreference(context,c,p.value(prefs.all,e)).apply {isPersistent=false;setOnPreferenceChangeListener {_,v->WidthController.stopAudition(context,owner);prefs.edit().putInt(c.key,v as Int).apply();true}}
                "choice"->FieldChoicePreference(context,c.key,c.title,e.items,resetSelection=c.default).apply {setOnPreferenceChangeListener {_,v->WidthController.stopAudition(context,owner);prefs.edit().putInt(c.key,v as Int).apply();true}}
                else->object: SwitchPreferenceCompat(context) {
                    override fun onBindViewHolder(holder: PreferenceViewHolder){super.onBindViewHolder(holder);(holder.findViewById(android.R.id.title) as? TextView)?.apply {setSingleLine(false);maxLines=Int.MAX_VALUE;ellipsize=null}}
                }.apply {key=c.key;title=c.title;isPersistent=false;isIconSpaceReserved=false;setOnPreferenceChangeListener {_,v->WidthController.stopAudition(context,owner);prefs.edit().putBoolean(c.key,v as Boolean).apply();true}}
            }
            preference.order=700+(e.group-1)*100+(i+1);rows[e]=preference;group.addPreference(preference)
        }
        saveA.setOnPreferenceClickListener {WidthController.captureComparison(context,8)};saveB.setOnPreferenceClickListener {WidthController.captureComparison(context,9)}
        if(!group.isProfessionalMode)group.addPreference(graph)
        group.addPreference(match);group.addPreference(compare)
        actual.isVisible = true
    }
    fun render() {
        val values=prefs.all;val mode=WidthParameters.choice(values,WidthParameters.MODE);val supported=WidthController.supportedAdvanced(context)
        rows.forEach {(e,r)->
            val v=p.value(values,e);when(r){is BassNumericPreference->if(r.value!=v)r.value=v;is FieldChoicePreference->r.value=v;is SwitchPreferenceCompat->if(r.isChecked!=(v==1))r.isChecked=v==1}
            r.isEnabled=supported && when {
                e.control.key in listOf("width2_guard_low","width2_guard_mid","width2_guard_high")->mode==2 && values["width2_guard_mode"]==1
                e.band>=0->if(e.band==3)mode==1 else mode==2
                e.group in 2..4->mode!=0
                else->true
            }
        }
        renderStatus(values,supported)
    }
    fun renderStatus()=renderStatus(prefs.all,WidthController.supportedAdvanced(context))
    private fun renderStatus(values: Map<String,*>,supported: Boolean) {
        val mode=WidthParameters.choice(values,WidthParameters.MODE)
        saveA.isEnabled=supported && mode!=0;saveB.isEnabled=saveA.isEnabled;match.isEnabled=saveA.isEnabled;match.isChecked=WidthController.compareMatch
        saveA.summary=if(WidthController.hasComparison(8))"A 已保存；点击用当前参数替换"else "仅保留在当前页面会话，不写入预设"
        saveB.summary=if(WidthController.hasComparison(9))"B 已保存；点击用当前参数替换"else "仅保留在当前页面会话，不写入预设"
        compare.isEnabled=saveA.isEnabled && prefs.getBoolean(WidthParameters.ENABLED,false) && WidthController.runtime.any {it.enabled}
        compare.value=if(WidthController.audition>=4)WidthController.audition-3 else 0
        val readings=WidthController.runtime.filter {it.enabled && it.active && it.advanced?.data?.get(5)==1 && prefs.getBoolean(WidthParameters.ENABLED,false)}
        fun format(x: Int?,scale: Double=10000.0)=StableRuntimeReadoutPreference.fixedNumber(x,scale,2,true)
        fun percent(x: Int?)=StableRuntimeReadoutPreference.fixedNumber(x,10.0,1,width=6)+"%"
        fun hz(x: Int?)=x?.toString()?.padStart(6) ?: "—".padStart(6)
        val streams=WidthController.runtime.map {it as WidthController.Reading?}.ifEmpty {listOf(null)}
        actual.summary=streams.joinToString("\n\n") {r->
            val v=r?.takeIf {it.enabled && it.active && prefs.getBoolean(WidthParameters.ENABLED,false)}
                ?.advanced?.data?.takeIf {supported && values["width2_meter_on"]==true && it[5]==1}
            val state=when {
                !supported->"第二轮专业处理需要配套新版驱动；第一轮与旧参数继续保留。"
                values["width2_meter_on"]!=true->"分段监测与图表已关闭；动态处理仍按各自开关工作。"
                v==null->"等待有效音频／读数未更新"
                else->"分段实时读数；—表示无有效读数。"
            }
            "${hz(r?.snapshot?.rate)} Hz\n$state\n"+
                (0..2).joinToString("\n") {i->
                    val name=listOf("低频","中频","高频")[i]
                    "$name：相关度 ${format(v?.get(7+i))} → ${format(v?.get(10+i))}；\n"+
                        "S/M ${format(v?.get(13+i),10.0)} → ${format(v?.get(16+i),10.0)} dB"
                }+"\n动态回收 低／中／高／全频：\n"+(19..22).joinToString("／"){percent(v?.get(it))}+
                "\n瞬态回收：\n"+(23..26).joinToString("／"){percent(v?.get(it))}+
                "\n保护回收：\n"+(27..30).joinToString("／"){percent(v?.get(it))}+
                "\n增量均衡频率目标 ${hz(v?.get(46))}／${hz(v?.get(47))} Hz；\n"+
                "实际试听 ${v?.get(44)?.let(WidthAdvancedRuntime::auditionLabel) ?: "—"}"
        }
        graph.isVisible=!group.isProfessionalMode && supported && values["width2_meter_on"]==true && group.isExpanded
        if(!group.isProfessionalMode)graph.update(readings.firstOrNull()?.advanced,values,graph.isVisible)
    }
}

/** Fixed-size canvas data; no audio, disk I/O or recurring timer on the UI thread. */
class WidthGraphPreference(context: Context): Preference(context) {
    private var snapshot: WidthAdvancedRuntime.Snapshot?=null
    private var values: Map<String,*> = emptyMap<String,Any>()
    private val history=ArrayDeque<Pair<Long,List<Int>>>()
    private var view: Plot?=null
    init {isSelectable=false;isPersistent=false;isIconSpaceReserved=false;layoutResource=R.layout.preference_width_graph}
    fun update(next: WidthAdvancedRuntime.Snapshot?,params: Map<String,*>,visible: Boolean) {
        values=params;snapshot=next
        val now=SystemClock.elapsedRealtime();val duration=(params["width2_history"] as? Number)?.toLong()?.coerceIn(5,30) ?: 10
        val drawingVisible=visible && view?.isShown==true && view?.getGlobalVisibleRect(Rect())==true
        if(drawingVisible && next!=null && params["width2_meter_on"]==true && history.lastOrNull()?.second!=next.data)history.addLast(now to next.data)
        while(history.isNotEmpty() && (now-history.first().first>duration*1000 || history.size>240))history.removeFirst()
        if(next==null || !drawingVisible)history.clear()
        view?.invalidate()
    }
    override fun onBindViewHolder(holder: PreferenceViewHolder) {super.onBindViewHolder(holder);(holder.itemView as FrameLayout).apply {removeAllViews();addView(Plot(context).also {view=it})}}
    private inner class Plot(context: Context): View(context) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        init {val d=resources.displayMetrics;minimumHeight=((420+130*d.scaledDensity/d.density)*d.density).roundToInt();layoutParams=ViewGroup.LayoutParams(-1,minimumHeight);contentDescription="扩宽增量频响、分段相关度历史与声场矢量图"}
        override fun onDraw(c: Canvas) {
            super.onDraw(c);val d=resources.displayMetrics.density;val pad=18*d;val w=width-2*pad;val text=12*resources.displayMetrics.scaledDensity
            val ink=MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurface,Color.DKGRAY)
            val grid=(ink and 0x00ffffff) or 0x55000000
            val accent=MaterialColors.getColor(this,com.google.android.material.R.attr.colorPrimary,0xff6750a4.toInt())
            fun label(t: String,start: Float): Float {
                paint.color=ink;paint.textSize=text;paint.style=Paint.Style.FILL;var y=start;var rest=t
                while(rest.isNotEmpty()){val n=paint.breakText(rest,true,w,null).coerceAtLeast(1);c.drawText(rest.take(n),pad,y,paint);rest=rest.drop(n);y+=text*1.35f}
                return y
            }
            fun axes(y: Float,h: Float){paint.color=grid;paint.strokeWidth=d;c.drawRect(pad,y,pad+w,y+h,paint.apply {style=Paint.Style.STROKE});paint.style=Paint.Style.FILL;c.drawLine(pad,y+h/2,pad+w,y+h/2,paint)}
            val eqTitle=if(snapshot==null)"增量均衡目标曲线（48 kHz 参考）"else "扩宽增量均衡频响（−12～+12 dB）"
            val eqTop=label(eqTitle,pad+text)+4*d;val eh=86*d;axes(eqTop,eh)
            var px=0f;var py=0f
            for(i in 0..200) {
                val fs=snapshot?.data?.get(2)?.toDouble()?.takeIf {it>0} ?: 48000.0;val hz=80.0*((min(16000.0,fs*.45)/80).pow(i/200.0));var db=0.0
                for(j in 0..1)if(values["width2_eq${j}_on"]==true) {
                    val freq=((values["width2_eq${j}_hz"] as? Number)?.toDouble() ?: if(j==0)400.0 else 6000.0).coerceAtMost(fs*.45)
                    val gain=((values["width2_eq${j}_gain"] as? Number)?.toDouble() ?: 0.0)/10;val q=((values["width2_eq${j}_q"] as? Number)?.toDouble() ?: 100.0)/100
                    val a=10.0.pow(gain/40);val wc=2*PI*freq/fs;val alpha=sin(wc)/(2*q);val omega=2*PI*hz.coerceAtMost(fs*.499)/fs
                    val b=doubleArrayOf(1+alpha*a,-2*cos(wc),1-alpha*a);val den=doubleArrayOf(1+alpha/a,-2*cos(wc),1-alpha/a)
                    fun power(x: DoubleArray): Double {val re=x[0]+x[1]*cos(omega)+x[2]*cos(2*omega);val im= -x[1]*sin(omega)-x[2]*sin(2*omega);return re*re+im*im}
                    db+=10*log10((power(b)/power(den)).coerceAtLeast(1e-12))
                }
                val x=pad+w*i/200;val y=eqTop+eh/2-(db.coerceIn(-12.0,12.0)/24*eh).toFloat();paint.color=accent;paint.strokeWidth=2*d;if(i>0)c.drawLine(px,py,x,y,paint);px=x;py=y
            }
            val upperHz=min(16000.0,(snapshot?.data?.get(2)?.toDouble()?.takeIf {it>0} ?: 48000.0)*.45)
            val histLabel=label("80 Hz     ${sqrt(80*upperHz).roundToInt()} Hz     ${upperHz.roundToInt()} Hz",eqTop+eh+text+8*d)+8*d
            val top=label("相关度历史（−1～+1）：低蓝／中绿／高橙",histLabel)+4*d;val hh=82*d;axes(top,hh)
            val duration=(values["width2_history"] as? Number)?.toDouble()?.coerceIn(5.0,30.0) ?: 10.0;val now=SystemClock.elapsedRealtime()
            val colors=intArrayOf(0xff1976d2.toInt(),0xff388e3c.toInt(),0xffc06400.toInt())
            for(band in 0..2){var previous: Pair<Float,Float>?=null;history.forEach {(time,v)->val rho=v[10+band];if(rho==Int.MIN_VALUE)previous=null else {val x=pad+w*(1-(now-time)/(duration*1000)).toFloat();val y=top+hh*(1-rho/10000f)/2;paint.color=colors[band];paint.strokeWidth=1.5f*d;previous?.let {c.drawLine(it.first,it.second,x,y,paint)};previous=x to y}}}
            val vectorTop=label("声场矢量图：横向 Side，纵向 Mid",top+hh+text+16*d);val cx=width/2f;val radius=58*d;val cy=vectorTop+radius+8*d
            paint.color=grid;paint.strokeWidth=d;c.drawLine(cx-radius,cy,cx+radius,cy,paint);c.drawLine(cx,cy-radius,cx,cy+radius,paint)
            val v=snapshot?.data;if(v!=null && values["width2_meter_on"]==true){paint.color=accent;for(i in 0 until 64){val l=v[48+2*i]/1000000f;val r=v[49+2*i]/1000000f;c.drawCircle(cx+(l-r)*.5f*radius,cy-(l+r)*.5f*radius,1.5f*d,paint)}}
            if(v==null || values["width2_meter_on"]!=true)label("分段监测关闭或暂无有效音频",cy+radius+text+12*d)
        }
    }
}
