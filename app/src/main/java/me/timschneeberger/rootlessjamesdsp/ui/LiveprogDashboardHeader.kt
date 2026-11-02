package me.timschneeberger.rootlessjamesdsp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogAudioFrame
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogChartConfiguration
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogChartModels
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogChartPair
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.log10
import kotlin.math.min

/** Jointly locked static pair and independently collapsible, genuinely captured live audio. */
internal class LiveprogDashboardHeader(context: Context,
    private val onMonitorChanged: (Boolean) -> Unit,
    private val onPinChanged: (Boolean) -> Unit
) : LinearLayout(context) {
    private data class Request(val configuration: LiveprogChartConfiguration, val key: String)
    private val ink=MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurface)
    private val accent=MaterialColors.getColor(this,com.google.android.material.R.attr.colorPrimary)
    private val surface=MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurface)
    private val display=context.applicationContext.getSharedPreferences("liveprog_dashboard_display",Context.MODE_PRIVATE)
    private val power=ImageButton(context)
    private val pin=ImageButton(context)
    val toolbarActions:List<View> = listOf(power,pin)
    private val leftTitle=title()
    private val rightTitle=title()
    private val lockText=title()
    private val liveTitle=title()
    private val foldText=title()
    private val rightHeading=LinearLayout(context)
    private val liveHeading=LinearLayout(context)
    private val parameter=LiveprogStaticChart(context)
    private val illustration=LiveprogStaticChart(context)
    private val realtime=LiveprogRealtimeChart(context)
    private val dynamicBody=LinearLayout(context)
    private val readouts=StableDashboardReadouts(context)
    private val main=Handler(Looper.getMainLooper())
    private var executor:ExecutorService?=null
    private var worker:Future<*>?=null
    private var inFlight:Request?=null
    private var pending:Request?=null
    private var token=0L
    private var frameScheduled=false
    private var configuration:LiveprogChartConfiguration?=null
    private var latest:LiveprogAudioFrame?=null
    private var lastLevels="真实脚本输入／输出 · 等待捕获窗口"
    private var latestStatus="实时采样已关闭"
    private var monitoring=false
    private var pinned=true
    private var expanded=display.getBoolean("expanded",true)
    private var locked=false
    private var staticKey=""
    private var liveKey=""
    private var closed=false
    private val modelFrame=Runnable {
        frameScheduled=false
        if(!closed&&isAttachedToWindow&&worker==null)startWork()
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density+.5f).toInt()
    private fun title()=TextView(context).apply {
        textSize=11f;setTextColor(ink);setTypeface(null,Typeface.BOLD)
        includeFontPadding=false;fontFeatureSettings="tnum";minHeight=dp(18)
        setSingleLine(true);ellipsize=TextUtils.TruncateAt.END;gravity=Gravity.CENTER_VERTICAL
    }
    init {
        orientation=VERTICAL;isBaselineAligned=false
        setPadding(dp(12),dp(4),dp(12),dp(6));setBackgroundColor(surface)
        listOf(power,pin).forEach {button->
            button.minimumWidth=0;button.minimumHeight=0
            button.setPadding(dp(8),dp(8),dp(8),dp(8))
            button.background=ColorDrawable(Color.TRANSPARENT);button.backgroundTintList=null;button.foreground=null
        }
        power.setImageResource(R.drawable.ic_twotone_power_settings_24dp)
        power.setOnClickListener {onMonitorChanged(!monitoring)}
        pin.setImageResource(R.drawable.ic_peq_pin)
        pin.setOnClickListener {onPinChanged(!pinned)}
        val leftColumn=LinearLayout(context).apply {orientation=VERTICAL}
        val rightColumn=LinearLayout(context).apply {orientation=VERTICAL}
        leftTitle.text="脚本参数模型";rightTitle.text="作用示意";liveTitle.text="脚本前后真实音频"
        leftTitle.setPadding(0,0,0,dp(4));rightTitle.setPadding(0,0,0,dp(4))
        rightHeading.orientation=HORIZONTAL;rightHeading.gravity=Gravity.CENTER_VERTICAL;rightHeading.isBaselineAligned=true
        lockText.setPadding(dp(5),0,0,dp(4));lockText.setTextColor(accent)
        lockText.background=ColorDrawable(Color.TRANSPARENT)
        lockText.setOnClickListener {
            locked=!locked
            configuration?.let { display.edit().putBoolean("locked:${it.identity}",locked).apply() }
            updateLock()
        }
        rightHeading.addView(rightTitle,LayoutParams(0,-2,1f));rightHeading.addView(lockText,LayoutParams(-2,-2))
        leftColumn.addView(leftTitle,LayoutParams(-1,-2));leftColumn.addView(parameter,LayoutParams(-1,dp(144)))
        rightColumn.addView(rightHeading,LayoutParams(-1,-2));rightColumn.addView(illustration,LayoutParams(-1,dp(144)))
        val pair=LinearLayout(context).apply {orientation=HORIZONTAL;isBaselineAligned=false}
        pair.addView(leftColumn,LayoutParams(0,-2,1f));pair.addView(View(context),LayoutParams(dp(8),1))
        pair.addView(rightColumn,LayoutParams(0,-2,1f));addView(pair,LayoutParams(-1,-2))
        liveHeading.orientation=HORIZONTAL;liveHeading.gravity=Gravity.CENTER_VERTICAL;liveHeading.isBaselineAligned=true
        liveTitle.setPadding(0,dp(5),0,dp(3));foldText.setPadding(dp(8),dp(5),0,dp(3))
        foldText.setTextColor(accent);foldText.background=ColorDrawable(Color.TRANSPARENT)
        foldText.setOnClickListener {expanded=!expanded;display.edit().putBoolean("expanded",expanded).apply();updateFold()}
        liveHeading.addView(liveTitle,LayoutParams(0,-2,1f));liveHeading.addView(foldText,LayoutParams(-2,-2))
        addView(liveHeading,LayoutParams(-1,-2))
        dynamicBody.orientation=VERTICAL
        dynamicBody.addView(realtime,LayoutParams(-1,dp(190)))
        dynamicBody.addView(readouts,LayoutParams(-1,readouts.fixedContentHeight))
        addView(dynamicBody,LayoutParams(-1,-2))
        updateLock();updateFold();setControls(false,true);renderReadouts()
    }
    fun setConfiguration(value:LiveprogChartConfiguration,focusKey:String?=null){
        if(closed)return
        val old=configuration
        val changed=old!=value
        if(!changed&&focusKey==null)return
        val previousStatic=staticKey
        val previousLive=liveKey
        configuration=value
        val switched=old?.identity!=value.identity
        if(switched){
            val first=value.parameters.firstOrNull()?.key.orEmpty()
            staticKey=display.getString("focus:${value.identity}",first)?.takeIf { saved -> saved.isEmpty() || value.parameters.any {it.key==saved} }?:first
            liveKey=staticKey;locked=display.getBoolean("locked:${value.identity}",false)
            latest=null;lastLevels="真实脚本输入／输出 · 等待捕获窗口"
            realtime.reset("脚本已切换 · 等待新的前后采样");updateLock()
            parameter.setPlot(me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogStaticPlot("当前脚本","正在读取参数模型"))
            illustration.setPlot(me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogStaticPlot("作用示意","正在读取当前脚本"))
        }else{
            val validKeys=value.parameters.map {it.key}
            if(staticKey.isNotEmpty()&&staticKey !in validKeys)staticKey=validKeys.firstOrNull().orEmpty()
            if(liveKey.isNotEmpty()&&liveKey !in validKeys)liveKey=validKeys.firstOrNull().orEmpty()
        }
        if(focusKey!=null){
            val focus=LiveprogChartModels.focus(value,focusKey)
            liveKey=focus.key
            if(!locked&&staticKey!=focus.key){
                staticKey=focus.key
                display.edit().putString("focus:${value.identity}",staticKey).apply()
            }
        }
        if(!changed&&previousStatic==staticKey&&previousLive==liveKey)return
        updateFocusTitles()
        // One parameter edit supplies its values and focus atomically; never build the old focus first.
        if(changed||previousStatic!=staticKey)requestPair()
        renderReadouts()
    }
    fun followParameter(key:String){
        val config=configuration?:return
        setConfiguration(config,key)
    }
    private fun updateFocusTitles(){
        val config=configuration?:return
        val focus=LiveprogChartModels.focus(config,staticKey)
        setTitle(leftTitle,focus.leftTitle);setTitle(rightTitle,focus.rightTitle)
        val live=if(liveKey==staticKey)focus else LiveprogChartModels.focus(config,liveKey)
        setTitle(liveTitle,live.liveTitle);realtime.setMode(live.realtimeMode)
    }
    private fun requestPair(){
        val config=configuration?:return
        val request=Request(config,staticKey)
        if(request==inFlight){
            // Returning to the snapshot already being built also supersedes any older pending edit.
            pending=null
            return
        }
        pending=request
        scheduleModelFrame()
    }
    private fun scheduleModelFrame(){
        if(closed||!isAttachedToWindow||worker!=null||pending==null||frameScheduled)return
        frameScheduled=true
        postOnAnimation(modelFrame)
    }
    private fun canDisplay(request:Request):Boolean{
        val current=configuration?:return false
        val previous=request.configuration
        return staticKey==request.key&&current.identity==previous.identity&&current.path==previous.path&&
            current.source==previous.source&&current.expectedSourceHash==previous.expectedSourceHash&&
            current.rate==previous.rate&&current.actualRate==previous.actualRate&&current.enabled==previous.enabled
    }
    private fun startWork(){
        if(closed||!isAttachedToWindow||worker!=null)return
        val request=pending?:return;pending=null
        inFlight=request
        val workToken=token
        val pool=executor?:Executors.newSingleThreadExecutor {task->Thread(task,"liveprog-static-model").apply {isDaemon=true}}.also {executor=it}
        worker=pool.submit {
            val result:LiveprogChartPair?=runCatching {LiveprogChartModels.pair(request.configuration,request.key)}.getOrNull()
            main.post {
                if(workToken!=token||closed)return@post
                worker=null;inFlight=null
                // Newer parameter values must not starve a completed frame from this same script/focus.
                // A single worker preserves order; the next animation frame then takes the latest edit.
                if(result!=null&&canDisplay(request)){
                    parameter.setPlot(result.professional);illustration.setPlot(result.illustration)
                    setTitle(leftTitle,result.professional.title);setTitle(rightTitle,result.illustration.title)
                }
                scheduleModelFrame()
            }
        }
    }
    fun setControls(monitoring:Boolean,pinned:Boolean){
        this.monitoring=monitoring;this.pinned=pinned
        power.setColorFilter(if(monitoring)accent else ColorUtils.setAlphaComponent(ink,100))
        power.contentDescription=if(monitoring)"实时采样已开启，点击关闭" else "实时采样已关闭，点击开启"
        pin.setColorFilter(if(pinned)accent else ColorUtils.setAlphaComponent(ink,150))
        pin.contentDescription=if(pinned)"图纸已固定，点击随参数滚动" else "固定整个图纸区域"
    }
    fun showSample(frame:LiveprogAudioFrame){
        if(closed||!monitoring)return
        latest=frame;latestStatus=frame.status
        val p=frame.pcm
        if(p!=null&&p.ready&&p.moduleEnabled&&p.executing&&!p.invalidSamples){
            fun db(left:FloatArray,right:FloatArray):String{
                if(left.isEmpty()||left.size!=right.size)return "—"
                var sum=0.0
                for(i in left.indices)sum+=left[i].toDouble()*left[i]+right[i].toDouble()*right[i]
                return if(sum>0)String.format(Locale.ROOT,"%.1f",10*log10(sum/(left.size*2)))else "−∞"
            }
            lastLevels="输入 RMS ${db(p.inputLeft,p.inputRight)} / 输出 RMS ${db(p.outputLeft,p.outputRight)} dBFS"
        }
        realtime.showSample(frame);renderReadouts()
    }
    fun showPaused(reason:String){latestStatus=reason;realtime.pause(reason);renderReadouts()}
    private fun renderReadouts(){
        val config=configuration
        val second=if(config==null)"选择脚本后显示参数模型" else
            "${config.rate} Hz${if(config.actualRate)" 内部处理率" else " 参考处理率"} · ${if(config.knownScript!=null)"内置脚本适配" else "导入脚本通用图"} · ${config.parameters.size}项参数"
        readouts.setLines(listOf(lastLevels,second,latestStatus))
    }
    private fun updateLock(){
        lockText.text=if(locked)"取消定位" else "定位"
        lockText.contentDescription=if(locked)"取消两个静态图的定位，下一次调节恢复自动换图" else "一起定位两个静态图，参数数据仍继续更新"
    }
    private fun setTitle(view:TextView,value:String){if(view.text.toString()!=value)view.text=value}
    private fun updateFold(){
        foldText.text=if(expanded)"折叠" else "展开"
        foldText.contentDescription="${foldText.text}实时图与读数，实时采样由顶部电源独立控制"
        dynamicBody.visibility=if(expanded)VISIBLE else GONE
        updateActive();requestLayout()
    }
    private fun updateActive(){
        parameter.setActive(isAttachedToWindow&&!closed);illustration.setActive(isAttachedToWindow&&!closed)
        realtime.setActive(isAttachedToWindow&&expanded&&!closed)
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val cap=halfWindowHeight().coerceAtLeast(1)
        val limit=min(cap,if(MeasureSpec.getMode(heightMeasureSpec)==MeasureSpec.UNSPECIFIED)cap else MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1))
        val width=(MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight).coerceAtLeast(1)
        val columnWidth=MeasureSpec.makeMeasureSpec(((width-dp(8))/2).coerceAtLeast(1),MeasureSpec.EXACTLY)
        val fullWidth=MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY)
        val unbounded=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED)
        leftTitle.measure(columnWidth,unbounded);rightHeading.measure(columnWidth,unbounded);liveHeading.measure(fullWidth,unbounded)
        val fixed=paddingTop+paddingBottom+maxOf(leftTitle.measuredHeight,rightHeading.measuredHeight)+liveHeading.measuredHeight+
            if(expanded)readouts.fixedContentHeight else 0
        val staticDesired=dp(144);val liveDesired=if(expanded)dp(156)else 0
        val available=(limit-fixed).coerceAtLeast(0)
        val contentBudget=min(available,staticDesired+liveDesired)
        // Reserve both panels together. A live caption must never consume the static pair's height.
        val staticHeight=if(!expanded)min(staticDesired,contentBudget)else{
            val staticMinimum=dp(108)
            val liveMinimum=realtime.minimumReadableHeight.coerceAtMost(liveDesired)
            val minimumTotal=staticMinimum+liveMinimum
            if(contentBudget>=minimumTotal){
                val spare=contentBudget-minimumTotal
                val staticSpare=staticDesired-staticMinimum
                val desiredSpare=staticSpare+liveDesired-liveMinimum
                staticMinimum+if(desiredSpare>0)(spare.toLong()*staticSpare/desiredSpare).toInt()else 0
            }else{
                (contentBudget.toLong()*staticMinimum/minimumTotal.coerceAtLeast(1)).toInt()
            }
        }
        val liveHeight=if(expanded)contentBudget-staticHeight else 0
        parameter.layoutParams.height=staticHeight;illustration.layoutParams.height=staticHeight;realtime.layoutParams.height=liveHeight
        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(min(limit,fixed+staticHeight+liveHeight),MeasureSpec.EXACTLY))
    }
    private fun halfWindowHeight():Int{
        var current:Context?=context
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context,Boolean>())
        while(current!=null&&current !is Activity&&seen.add(current))current=(current as? ContextWrapper)?.baseContext
        val host=current as? Activity
        val sizes=ArrayList<Int>(4)
        if(host!=null){
            if(Build.VERSION.SDK_INT>=30){
                val m=host.windowManager.currentWindowMetrics
                val i=m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                sizes+=m.bounds.height()-i.top-i.bottom
            }
            val frame=Rect();host.window.decorView.getWindowVisibleDisplayFrame(frame)
            if(frame.height()>0)sizes+=frame.height()
            host.findViewById<View>(android.R.id.content)?.height?.takeIf {it>0}?.let {sizes+=it}
        }
        if(sizes.isEmpty())sizes+=(resources.configuration.screenHeightDp*resources.displayMetrics.density).toInt()
        return sizes.filter {it>0}.minOrNull()?.div(2)?:resources.displayMetrics.heightPixels/2
    }
    fun close(){
        if(closed)return
        closed=true;removeCallbacks(modelFrame);frameScheduled=false
        token++;worker?.cancel(true);worker=null;inFlight=null;pending=null
        executor?.shutdownNow();executor=null;realtime.close();updateActive()
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();updateActive();if(configuration!=null&&!closed)requestPair()}
    override fun onDetachedFromWindow(){
        removeCallbacks(modelFrame);frameScheduled=false
        token++;worker?.cancel(true);worker=null;inFlight=null;pending=null;executor?.shutdownNow();executor=null
        parameter.setActive(false);illustration.setActive(false);realtime.setActive(false)
        super.onDetachedFromWindow()
    }
}
