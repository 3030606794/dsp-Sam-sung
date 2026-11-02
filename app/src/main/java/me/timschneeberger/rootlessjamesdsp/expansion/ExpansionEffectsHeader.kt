package me.timschneeberger.rootlessjamesdsp.expansion

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
import me.timschneeberger.rootlessjamesdsp.effectvisuals.EffectVisualView
import me.timschneeberger.rootlessjamesdsp.ui.StableDashboardReadouts
import me.timschneeberger.rootlessjamesdsp.effectvisuals.EffectVisualPair
import java.util.Locale
import kotlin.math.min

/** Presents already calculated models; the original editor and independent sampler own audio. */
internal class ExpansionEffectsHeader(context: Context, private val kind: ExpansionEffectsKind, power: Boolean, pinned: Boolean, expanded: Boolean,
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
    private val parameter=EffectVisualView(context,if(kind==ExpansionEffectsKind.SMART_ADAPT)12f else 11f)
    private val illustration=EffectVisualView(context,if(kind==ExpansionEffectsKind.SMART_ADAPT)12f else 11f)
    private val chart=ExpansionEffectsRealtimeChart(context, kind)
    private val dynamicBody=LinearLayout(context)
    private val readouts=if(kind==ExpansionEffectsKind.SMART_ADAPT) StableDashboardReadouts(context,12f,12f,12f)
        else StableDashboardReadouts(context,12f,11f,11f)
    private var powered=power
    private var pinnedState=pinned
    private var dynamicExpanded=expanded
    private var lockedState=locked
    
    private var latest:ExpansionEffectsRuntime?=null
    private var zeroMixBypass=false
    
    
    private var pauseReason="等待实际音频处理"
    private var lastCap=-1
    private var closed=false
    private fun dp(value:Int)=(value*resources.displayMetrics.density+.5f).toInt()
    private fun heading()=TextView(context).apply {
        textSize=13f;setTypeface(null,Typeface.BOLD);setTextColor(ink)
        includeFontPadding=false;minHeight=dp(18);fontFeatureSettings="tnum"
        setSingleLine(false);maxLines=2;ellipsize=TextUtils.TruncateAt.END;gravity=Gravity.CENTER_VERTICAL
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
        liveTitle.text=ExpansionEffectsVisuals.liveTitle(kind)
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
    fun setConfiguration(pair:EffectVisualPair,values:Map<String,*>,focusKey:String?){
        if(closed)return
        setTitle(leftTitle,pair.professional.title)
        setTitle(rightTitle,pair.illustration.title)
        parameter.setVisual(pair.professional);illustration.setVisual(pair.illustration)
        zeroMixBypass=kind in setOf(ExpansionEffectsKind.HEADTRACK,ExpansionEffectsKind.VOCAL)&&
            ExpansionEffectsParameters.raw(kind,values,"mix")<=0.0
        chart.setConfiguration(values,focusKey)
    }
    fun showSample(sample:ExpansionEffectsRuntime){
        if(closed||!powered)return
        latest=sample;chart.showSample(sample);setTitle(liveTitle,chart.visualTitle);refreshOriginalStatus()
    }
    fun showPaused(reason:String){
        if(closed)return
        pauseReason=reason;latest=null;chart.pause(reason);refreshOriginalStatus()
    }
    private fun refreshOriginalStatus(){
        if(closed)return
        if(kind==ExpansionEffectsKind.SPEAKER_OPTIMIZATION && latest==null && pauseReason.contains("已旁路")) {
            readouts.setLines(listOf(pauseReason,"开关与参数选择保留 · 当前输出原声","切回适用输出后自动恢复处理"))
            return
        }
        if(zeroMixBypass){
            readouts.setLines(listOf("混合 0% · 当前保持原声","此时不显示旧的姿态或分离电平","调高干湿比例后读取实际处理状态"))
            return
        }
        val sample=latest.takeIf {powered}
        fun number(value:Float?)=value?.takeIf {it.isFinite()}?.let {String.format(Locale.ROOT,"%.1f",it)}?:"—"
        val live=sample?.let { ExpansionEffectsTelemetry.readout(it) } ?: "等待实际音频处理"
        readouts.setLines(listOf("输入 ${number(sample?.inputPeakDb)} dBFS · 输出 ${number(sample?.outputPeakDb)} dBFS",
            live,if(sample!=null) ExpansionEffectsTelemetry.unavailable(sample) ?: "${sample.sampleRate} Hz · 驱动实际处理" else pauseReason))
    }
    private fun setTitle(view:TextView,value:String){if(view.text.toString()!=value)view.text=value}
    private fun updateActiveCharts(){
        parameter.setActive(isAttachedToWindow&&!closed);illustration.setActive(isAttachedToWindow&&!closed)
        chart.setActive(isAttachedToWindow&&dynamicExpanded&&!closed)
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        lastCap=halfWindowHeight()
        val width=(MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight).coerceAtLeast(1)
        val columnWidth=MeasureSpec.makeMeasureSpec(((width-dp(8))/2).coerceAtLeast(1),MeasureSpec.EXACTLY)
        val fullWidth=MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY)
        val unbounded=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED)
        leftTitle.measure(columnWidth,unbounded);rightHeading.measure(columnWidth,unbounded);liveHeading.measure(fullWidth,unbounded)
        val titleHeight=maxOf(leftTitle.measuredHeight,rightHeading.measuredHeight)
        leftTitle.layoutParams.height=titleHeight;rightHeading.layoutParams.height=titleHeight
        val fixed=paddingTop+paddingBottom+titleHeight+liveHeading.measuredHeight+
            if(dynamicExpanded)readouts.fixedContentHeight else 0
        // The two-static/one-live structure remains fixed. Increase the panel with font scale;
        // do not force it into half a window by shrinking axis labels or crushing plot geometry.
        val staticHeight=maxOf(dp(168),parameter.minimumReadableHeight,illustration.minimumReadableHeight)
        val liveHeight=if(dynamicExpanded)maxOf(dp(192),chart.minimumReadableHeight)else 0
        parameter.layoutParams.height=staticHeight;illustration.layoutParams.height=staticHeight;chart.layoutParams.height=liveHeight
        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(fixed+staticHeight+liveHeight,MeasureSpec.EXACTLY))
    }
    fun close(){if(closed)return;closed=true;chart.pause("页面已关闭");updateActiveCharts()}
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
