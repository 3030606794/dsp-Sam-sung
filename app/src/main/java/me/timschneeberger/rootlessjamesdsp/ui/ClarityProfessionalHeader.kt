package me.timschneeberger.rootlessjamesdsp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fusion.ProfessionalRuntimeMonitor
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogChartPair
import java.util.Locale
import kotlin.math.min

/** Presents already calculated models; the original editor and independent sampler own audio. */
internal class ClarityProfessionalHeader(context: Context, power: Boolean, pinned: Boolean, expanded: Boolean,
    private val onPowerChanged: (Boolean) -> Unit,
    private val onPinChanged: (Boolean) -> Unit,
    private val onExpandedChanged: (Boolean) -> Unit,
    locked: Boolean = false,
    private val onLockChanged: (Boolean) -> Unit = {},
) : LinearLayout(context) {
    private val ink=MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurface)
    private val accent=MaterialColors.getColor(this,com.google.android.material.R.attr.colorPrimary)
    private val surface=MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurface)
    private val powerButton=ImageButton(context)
    private val pinButton=ImageButton(context)
    val toolbarActions:List<View> = listOf(powerButton,pinButton)
    private val leftTitle=heading()
    private val rightTitle=heading()
    private val lockText=heading()
    private val rightHeading=LinearLayout(context)
    private val liveTitle=heading()
    private val foldText=heading()
    private val liveHeading=LinearLayout(context)
    private val parameter=LiveprogStaticChart(context)
    private val illustration=LiveprogStaticChart(context)
    private val chart=ClarityRealtimeChart(context)
    private val dynamicBody=LinearLayout(context)
    private val readouts=StableDashboardReadouts(context)
    private var powered=power
    private var pinnedState=pinned
    private var dynamicExpanded=expanded
    private var lockedState=locked
    private var configuration:ClarityPreviewModel?=null
    private var actualRate=false
    private var latest:ProfessionalRuntimeMonitor.ClaritySample?=null
    private var current:ClarityRealtimeChart.Current?=null
    private var cursorText:String?=null
    private var pauseReason="等待真实增强与动态衰减"
    private var lastCap=-1
    private var closed=false
    private fun dp(value:Int)=(value*resources.displayMetrics.density+.5f).toInt()
    private fun heading()=TextView(context).apply {
        textSize=11f;setTypeface(null,Typeface.BOLD);setTextColor(ink)
        includeFontPadding=false;minHeight=dp(18);fontFeatureSettings="tnum"
        setSingleLine(true);ellipsize=TextUtils.TruncateAt.END;gravity=Gravity.CENTER_VERTICAL
    }
    init {
        orientation=VERTICAL;isBaselineAligned=false
        setPadding(dp(12),dp(4),dp(12),dp(6));setBackgroundColor(surface)
        listOf(powerButton,pinButton).forEach {button->
            button.minimumWidth=0;button.minimumHeight=0
            button.setPadding(dp(8),dp(8),dp(8),dp(8))
            button.background=ColorDrawable(Color.TRANSPARENT);button.backgroundTintList=null;button.foreground=null
        }
        powerButton.setImageResource(R.drawable.ic_twotone_power_settings_24dp)
        powerButton.setOnClickListener {if(!closed)onPowerChanged(!powered)}
        pinButton.setImageResource(R.drawable.ic_peq_pin)
        pinButton.setOnClickListener {if(!closed)onPinChanged(!pinnedState)}
        leftTitle.text="参数响应";rightTitle.text="作用示意"
        leftTitle.setPadding(0,0,0,dp(4));rightTitle.setPadding(0,0,0,dp(4))
        lockText.setPadding(dp(5),0,0,dp(4));lockText.setTextColor(accent)
        lockText.background=ColorDrawable(Color.TRANSPARENT)
        lockText.setOnClickListener {if(!closed)onLockChanged(!lockedState)}
        rightHeading.orientation=HORIZONTAL;rightHeading.gravity=Gravity.CENTER_VERTICAL;rightHeading.isBaselineAligned=true
        rightHeading.addView(rightTitle,LayoutParams(0,-2,1f));rightHeading.addView(lockText,LayoutParams(-2,-2))
        val leftColumn=LinearLayout(context).apply {orientation=VERTICAL}
        val rightColumn=LinearLayout(context).apply {orientation=VERTICAL}
        leftColumn.addView(leftTitle,LayoutParams(-1,-2));leftColumn.addView(parameter,LayoutParams(-1,dp(144)))
        rightColumn.addView(rightHeading,LayoutParams(-1,-2));rightColumn.addView(illustration,LayoutParams(-1,dp(144)))
        val pair=LinearLayout(context).apply {orientation=HORIZONTAL;isBaselineAligned=false}
        pair.addView(leftColumn,LayoutParams(0,-2,1f));pair.addView(View(context),LayoutParams(dp(8),1))
        pair.addView(rightColumn,LayoutParams(0,-2,1f));addView(pair,LayoutParams(-1,-2))
        liveTitle.text="实时增强与抑制 · 最近20秒"
        liveTitle.setPadding(0,dp(5),0,dp(3));foldText.setPadding(dp(8),dp(5),0,dp(3))
        foldText.setTextColor(accent);foldText.background=ColorDrawable(Color.TRANSPARENT)
        foldText.setOnClickListener {if(!closed)onExpandedChanged(!dynamicExpanded)}
        liveHeading.orientation=HORIZONTAL;liveHeading.gravity=Gravity.CENTER_VERTICAL;liveHeading.isBaselineAligned=true
        liveHeading.addView(liveTitle,LayoutParams(0,-2,1f));liveHeading.addView(foldText,LayoutParams(-2,-2))
        addView(liveHeading,LayoutParams(-1,-2))
        dynamicBody.orientation=VERTICAL
        dynamicBody.addView(chart,LayoutParams(-1,dp(156)))
        dynamicBody.addView(readouts,LayoutParams(-1,readouts.fixedContentHeight))
        addView(dynamicBody,LayoutParams(-1,-2))
        chart.onCursor={value->cursorText=value;refreshOriginalStatus()}
        ViewCompat.setOnApplyWindowInsetsListener(this){_,insets->if(lastCap!=halfWindowHeight())requestLayout();insets}
        setDisplay(power,pinned,expanded,locked);refreshOriginalStatus()
    }
    fun setDisplay(power:Boolean,pinned:Boolean,expanded:Boolean,locked:Boolean=this.lockedState){
        if(closed)return
        val resize=dynamicExpanded!=expanded
        powered=power;pinnedState=pinned;dynamicExpanded=expanded;lockedState=locked
        dynamicBody.visibility=if(expanded)VISIBLE else GONE
        foldText.text=if(expanded)"折叠"else"展开"
        foldText.contentDescription="${foldText.text}实时图与读数；顶部电源独立控制采样"
        lockText.text=if(locked)"取消定位"else"定位"
        lockText.contentDescription=if(locked)"取消两个静图的共同定位"else"共同定位两个静图；参数数据继续更新"
        powerButton.setColorFilter(if(power)accent else ColorUtils.setAlphaComponent(ink,100))
        powerButton.contentDescription=if(power)"实时采样已开启，点击关闭"else"实时采样已关闭，点击开启"
        pinButton.setColorFilter(if(pinned)accent else ColorUtils.setAlphaComponent(ink,150))
        pinButton.contentDescription=if(pinned)"图纸已固定，点击随页面滚动"else"固定整个图纸区域"
        updateActiveCharts();refreshOriginalStatus();if(resize)requestLayout()
    }
    fun setConfiguration(model:ClarityPreviewModel,pair:LiveprogChartPair,focusKey:String,actualRate:Boolean){
        if(closed)return
        configuration=model;this.actualRate=actualRate
        setTitle(leftTitle,pair.professional.title)
        setTitle(rightTitle,pair.illustration.title)
        parameter.setPlot(pair.professional);illustration.setPlot(pair.illustration)
        refreshOriginalStatus()
    }
    fun showRuntime(sample:ProfessionalRuntimeMonitor.ClaritySample){
        if(closed||!powered)return
        latest=sample;current=chart.showRuntime(sample,configuration);refreshOriginalStatus()
    }
    fun showPaused(reason:String){
        if(closed)return
        pauseReason=reason;latest=null;current=null;cursorText=null;chart.pause(reason);refreshOriginalStatus()
    }
    fun refreshOriginalStatus(){
        if(closed)return
        val sample=latest.takeIf {powered};val values=current.takeIf {powered}
        fun number(value:Double?)=value?.takeIf {it.isFinite()}?.let {String.format(Locale.ROOT,"%+.1f",it)}?:"—"
        val model=configuration
        val rate=sample?.reading?.rate?.takeIf {it in 8000..768000}?:model?.rate
        readouts.setLines(listOf(cursorText?:"增强 ${number(values?.enhancement)} · 高频 ${number(values?.dynamic)} · 齿音 ${number(values?.deess)} dB",
            "${rate?.let {"$it Hz"}?:"等待采样率"} · ${model?.modeLabel?:"参数预览"}${if(!actualRate)" · 参考"else""}",
            if(!powered)pauseReason else values?.status?:pauseReason))
    }
    private fun setTitle(view:TextView,value:String){if(view.text.toString()!=value)view.text=value}
    private fun updateActiveCharts(){
        parameter.setActive(isAttachedToWindow&&!closed);illustration.setActive(isAttachedToWindow&&!closed)
        chart.setActive(isAttachedToWindow&&dynamicExpanded&&!closed)
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val cap=halfWindowHeight().coerceAtLeast(1);lastCap=cap
        val limit=min(cap,if(MeasureSpec.getMode(heightMeasureSpec)==MeasureSpec.UNSPECIFIED)cap
            else MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1))
        val width=(MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight).coerceAtLeast(1)
        val columnWidth=MeasureSpec.makeMeasureSpec(((width-dp(8))/2).coerceAtLeast(1),MeasureSpec.EXACTLY)
        val fullWidth=MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY)
        val unbounded=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED)
        leftTitle.measure(columnWidth,unbounded);rightHeading.measure(columnWidth,unbounded);liveHeading.measure(fullWidth,unbounded)
        val fixed=paddingTop+paddingBottom+maxOf(leftTitle.measuredHeight,rightHeading.measuredHeight)+liveHeading.measuredHeight+
            if(dynamicExpanded)readouts.fixedContentHeight else 0
        val staticDesired=dp(144);val liveDesired=if(dynamicExpanded)dp(156)else 0
        val budget=min((limit-fixed).coerceAtLeast(0),staticDesired+liveDesired)
        val staticHeight=if(!dynamicExpanded)min(staticDesired,budget)else{
            val staticMinimum=dp(108);val liveMinimum=chart.minimumReadableHeight.coerceAtMost(liveDesired)
            val minimumTotal=staticMinimum+liveMinimum
            if(budget>=minimumTotal){
                val spare=budget-minimumTotal;val staticSpare=staticDesired-staticMinimum
                val totalSpare=staticSpare+liveDesired-liveMinimum
                staticMinimum+if(totalSpare>0)(spare.toLong()*staticSpare/totalSpare).toInt()else 0
            }else(budget.toLong()*staticMinimum/minimumTotal.coerceAtLeast(1)).toInt()
        }
        val liveHeight=if(dynamicExpanded)budget-staticHeight else 0
        parameter.layoutParams.height=staticHeight;illustration.layoutParams.height=staticHeight;chart.layoutParams.height=liveHeight
        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(min(limit,fixed+staticHeight+liveHeight),MeasureSpec.EXACTLY))
    }
    fun close(){if(closed)return;closed=true;chart.onCursor=null;chart.pause("页面已关闭");updateActiveCharts()}
    override fun onAttachedToWindow(){super.onAttachedToWindow();updateActiveCharts()}
    override fun onDetachedFromWindow(){parameter.setActive(false);illustration.setActive(false);chart.setActive(false);super.onDetachedFromWindow()}
    private fun halfWindowHeight():Int{
        var current:Context?=context
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context,Boolean>())
        while(current!=null&&current !is Activity&&seen.add(current))current=(current as? ContextWrapper)?.baseContext
        val host=current as? Activity;val sizes=ArrayList<Int>(4)
        if(host!=null){
            if(Build.VERSION.SDK_INT>=30){val metrics=host.windowManager.currentWindowMetrics
                val insets=metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                sizes+=metrics.bounds.height()-insets.top-insets.bottom}
            val frame=Rect();host.window.decorView.getWindowVisibleDisplayFrame(frame);if(frame.height()>0)sizes+=frame.height()
            host.findViewById<View>(android.R.id.content)?.height?.takeIf {it>0}?.let {sizes+=it}
        }
        if(sizes.isEmpty())sizes+=(resources.configuration.screenHeightDp*resources.displayMetrics.density).toInt()
        return sizes.filter {it>0}.minOrNull()?.div(2)?:resources.displayMetrics.heightPixels/2
    }
}
