package me.timschneeberger.rootlessjamesdsp.liveprog

import java.util.Locale
import kotlin.math.*

/**
 * Read-only descriptions of the forty shipped programs. A name alone never selects a model:
 * edited/imported programs must pass the same source identity check as the runtime adapter.
 * No EEL is evaluated here, and neither parameter values nor script text are written.
 */
internal object LiveprogChartModels {
    val supportedScriptNames: Set<String> = setOf(
        "3bandSplitting.eel", "8bandSplitting.eel", "Joe0Bloggs DRX10K compander-HR.eel",
        "audioGlitchGenerator.eel", "autoWideness.eel", "autopeakfilter.eel",
        "butterworth3Band.eel", "butterworth8Band.eel", "dc_remove.eel", "decimate.eel",
        "delayChorus.eel", "depthsurround.eel", "downmixer.eel", "fftConvolution2x4x2.eel",
        "fftConvolutionHRTF.eel", "firFilter.eel", "firlsProc.eel", "fractionalDelayline.eel",
        "gainControl.eel", "hadamVerb.eel", "highpass.eel", "lofiDistortionMangler.eel",
        "lowpass.eel", "metallic-reverb.eel", "msCentreBoost.eel", "phaseshifter.eel",
        "pitchDownshift.eel", "polyphaseFilterbank.eel", "polyphaseFilterbankEqualization.eel",
        "stereoFieldManipulator.eel", "stereoPhaseInvert.eel", "stereowide.eel",
        "stftCentreBoost.eel", "stftCentreCut.eel", "stftDenoise.eel", "stftFilter.eel",
        "swapChannels.eel", "timeAdjustment.eel", "viper_dynamicbass.eel", "viper_dynamicbass_preset.eel"
    )

    // The editor verifies source fingerprints in its background load before publishing this
    // immutable configuration. Slider/focus events must not parse or hash the source again.
    private fun recognized(c: LiveprogChartConfiguration): String? = c.knownScript?.takeIf {
        it == c.fileName && it in supportedScriptNames
    }

    fun focus(c: LiveprogChartConfiguration, key: String?): LiveprogChartFocus {
        val k = key?.takeIf { candidate -> c.parameters.any { it.key == candidate } }
            ?: c.parameters.firstOrNull()?.key.orEmpty()
        val name = recognized(c)
        fun f(group: String, left: String, right: String, live: String,
              mode: LiveprogRealtimeMode = LiveprogRealtimeMode.SPECTRUM) =
            LiveprogChartFocus(k, group, left, right, live, mode)
        return when (name) {
            "3bandSplitting.eel", "8bandSplitting.eel" -> f("bands", "分频与分段增益", "各频段作用示意", "实际处理前后频谱")
            "highpass.eel", "lowpass.eel" -> f("midfilter", "中央／侧面响应", "中央声音处理路径", "实际处理前后频谱")
            "gainControl.eel" -> f("gain", "输入与输出电平", "音量放大与缩小", "实际电平与历史", LiveprogRealtimeMode.LEVEL)
            "timeAdjustment.eel" -> if (k == "slider2" || k == "slider3")
                f("mix", "干湿声合成响应", "原声与延迟声混合", "实际前后波形", LiveprogRealtimeMode.WAVEFORM)
                else f("delay", "请求与实际延迟", "声音到达时间", "实际前后波形", LiveprogRealtimeMode.WAVEFORM)
            "fractionalDelayline.eel" -> f("delay", "左右延迟时间", "左右到达时间示意", "实际双声道波形", LiveprogRealtimeMode.WAVEFORM)
            "delayChorus.eel" -> when (k) {
                "slider2" -> f("feedback", "反馈与延迟结构", "重复声音的变化", "实际前后波形", LiveprogRealtimeMode.WAVEFORM)
                "slider3", "slider4", "slider5" -> f("mix", "三路输出增益", "原声／延迟／合唱", "实际电平与历史", LiveprogRealtimeMode.LEVEL)
                "slider6", "slider7" -> f("modulation", "合唱读头调制", "声音时间伸缩示意", "实际前后时频图", LiveprogRealtimeMode.TIME_FREQUENCY)
                else -> f("delay", "延迟与读头范围", "回声到达时间", "实际前后波形", LiveprogRealtimeMode.WAVEFORM)
            }
            "hadamVerb.eel" -> when (k) {
                "slider4", "slider5" -> f("feedback", "阵列反馈系数", "两组反馈阵列", "实际声场与相关度", LiveprogRealtimeMode.STEREO)
                "slider8", "slider9", "slider14" -> f("modulation", "延迟调制范围", "两组延迟的伸缩", "实际前后时频图", LiveprogRealtimeMode.TIME_FREQUENCY)
                "slider20" -> f("gain", "输出增益关系", "阵列输出混合", "实际电平与历史", LiveprogRealtimeMode.LEVEL)
                else -> f("delay", "八路延迟目标", "双阵列扩散示意", "实际声场与相关度", LiveprogRealtimeMode.STEREO)
            }
            "depthsurround.eel" -> f("feedback", "强度与反馈极性", "双延迟环绕路径", "实际声场与相关度", LiveprogRealtimeMode.STEREO)
            "autoWideness.eel" -> when (k) {
                "slider3", "slider4" -> f("time", "宽度启动／释放", "展开与收拢速度", "实际声场与相关度", LiveprogRealtimeMode.STEREO)
                "slider10", "slider11" -> f("detector", "检测支路频响", "检测与声音路径", "实际中侧声场", LiveprogRealtimeMode.STEREO)
                "slider7" -> f("routing", "零宽度声源", "左右声道混合路径", "实际中侧声场", LiveprogRealtimeMode.STEREO)
                else -> f("width", "电平到宽度的映射", "自动展开范围", "实际声场与相关度", LiveprogRealtimeMode.STEREO)
            }
            "stereoFieldManipulator.eel" -> f(k, when (k) {
                "slider1" -> "声道旋转关系"; "slider2" -> "中侧声道宽度"
                "slider3" -> "中央与侧面平衡"; else -> "左右声道平衡"
            }, "中央与两侧变化", "实际声场与相关度", LiveprogRealtimeMode.STEREO)
            "msCentreBoost.eel" -> f("ms", "中侧增益与路由", "中央与两侧的比例", "实际中侧声场", LiveprogRealtimeMode.STEREO)
            "stereoPhaseInvert.eel" -> f("polarity", "左右声道极性", "正负波形示意", "实际波形与极性", LiveprogRealtimeMode.WAVEFORM)
            "swapChannels.eel" -> f("swap", "交换后的输出增益", "左右交换路径", "实际双声道波形", LiveprogRealtimeMode.WAVEFORM)
            "stftCentreBoost.eel" -> f("centre", "中心增强的内容规则", "中央与分散声音", "实际中侧声场", LiveprogRealtimeMode.STEREO)
            "stftFilter.eel" -> f("fftindices", "实际频格处理范围", "实部／虚部作用位置", "实际处理前后频谱")
            "Joe0Bloggs DRX10K compander-HR.eel" -> if (k == "bass_freq" || k == "treble_freq")
                f("bands", "三段动态作用范围", "低／中／高频分工", "实际处理前后频谱")
                else if (k == "gaindB") f("gain", "总输出增益", "动态处理与音量", "实际电平与历史", LiveprogRealtimeMode.LEVEL)
                else f("dynamics", "三段动态规则", "突出声音与背景声音", "实际处理前后频谱")
            "viper_dynamicbass.eel", "viper_dynamicbass_preset.eel" -> f("bass", "实际分支频响", "低音与其他频段", "实际低频与全频频谱")
            "lofiDistortionMangler.eel" -> when (k) {
                "slider1", "slider2", "slider3" -> f("mix", "输入与干湿声增益", "原声与染色混合", "实际电平与历史", LiveprogRealtimeMode.LEVEL)
                "slider4" -> f("resample", "采样保持间隔", "波形颗粒示意", "实际前后波形", LiveprogRealtimeMode.WAVEFORM)
                "slider5", "slider6", "slider7" -> f("quantize", "量化与位破坏设置", "波形阶梯示意", "实际前后波形", LiveprogRealtimeMode.WAVEFORM)
                "slider8" -> f("gate", "门限与保持时间", "声音开门与衰减", "实际电平与历史", LiveprogRealtimeMode.LEVEL)
                else -> f("color", "染色与波形整形", "染色处理路径", "实际处理前后频谱")
            }
            "audioGlitchGenerator.eel" -> when (k) {
                "slider4", "slider5", "slider6", "slider7" -> f("probability", "各效果触发概率", "随机切片的选择", "实际前后波形", LiveprogRealtimeMode.WAVEFORM)
                "slider8", "slider9" -> f("trigger", "触发与起始设置", "录制与播放控制", "实际电平与历史", LiveprogRealtimeMode.LEVEL)
                "slider10" -> f("arpeggio", "琶音变调比例", "切片音高变化", "实际前后时频图", LiveprogRealtimeMode.TIME_FREQUENCY)
                "slider2" -> f("mix", "干湿声混合比例", "原声与切片混合", "实际前后波形", LiveprogRealtimeMode.WAVEFORM)
                else -> f("buffer", "切片缓冲与节拍", "切片录制时间线", "实际前后波形", LiveprogRealtimeMode.WAVEFORM)
            }
            "dc_remove.eel" -> f("dc", "直流滤除响应", "波形回到零附近", "实际前后波形", LiveprogRealtimeMode.WAVEFORM)
            "firFilter.eel" -> f("fir", "实际 FIR 频响", "固定脉冲系数", "实际处理前后频谱")
            "firlsProc.eel" -> f("design", "FIR 设计目标", "设计与声音路径", "实际处理前后频谱")
            "butterworth3Band.eel", "butterworth8Band.eel" -> f("fixedbands", "固定分频与增益", "分频处理路径", "实际处理前后频谱")
            "polyphaseFilterbank.eel", "polyphaseFilterbankEqualization.eel" -> f("polyphase", "固定子带开关", "分析与重建路径", "实际处理前后频谱")
            "fftConvolution2x4x2.eel", "fftConvolutionHRTF.eel" -> f("convolution", "四路卷积结构", "双输入到双输出", "实际声场与相关度", LiveprogRealtimeMode.STEREO)
            "autopeakfilter.eel" -> f("follow", "搜索范围与跟随", "突出频段的选择", "实际前后时频图", LiveprogRealtimeMode.TIME_FREQUENCY)
            "metallic-reverb.eel" -> f("waveguide", "固定反馈延迟", "回声循环路径", "实际前后波形", LiveprogRealtimeMode.WAVEFORM)
            "phaseshifter.eel" -> f("phaser", "六级移相扫描范围", "原声与移相支路", "实际前后时频图", LiveprogRealtimeMode.TIME_FREQUENCY)
            "pitchDownshift.eel" -> f("pitch", "分块与降调比例", "切片读写路径", "实际前后时频图", LiveprogRealtimeMode.TIME_FREQUENCY)
            "decimate.eel" -> f("decimate", "固定采样与量化", "采样保持示意", "实际前后波形", LiveprogRealtimeMode.WAVEFORM)
            "stereowide.eel", "downmixer.eel" -> f("fixedms", "中侧声道转换", "中央与两侧去留", "实际声场与相关度", LiveprogRealtimeMode.STEREO)
            "stftCentreCut.eel" -> f("cut", "中心消除的内容规则", "中央与分散声音", "实际中侧声场", LiveprogRealtimeMode.STEREO)
            "stftDenoise.eel" -> f("denoise", "频格降噪处理结构", "噪声估计与抑制", "实际前后时频图", LiveprogRealtimeMode.TIME_FREQUENCY)
            else -> f("generic", "脚本参数与范围", "当前调节作用位置", "实际脚本前后音频", LiveprogRealtimeMode.SPECTRUM)
        }
    }

    fun pair(c: LiveprogChartConfiguration, key: String?): LiveprogChartPair = build(c, focus(c, key))

    fun build(c: LiveprogChartConfiguration, f: LiveprogChartFocus): LiveprogChartPair {
        val result = when (val name = recognized(c)) {
            "3bandSplitting.eel" -> splitting(c, f, 3)
            "8bandSplitting.eel" -> splitting(c, f, 8)
            "highpass.eel", "lowpass.eel" -> midFilter(c, f, name == "highpass.eel")
            "gainControl.eel" -> gain(c, f, c.v("dB", -8.0))
            "timeAdjustment.eel" -> timeAdjustment(c, f)
            "fractionalDelayline.eel" -> fractionalDelay(c, f)
            "delayChorus.eel" -> chorus(c, f)
            "hadamVerb.eel" -> hadamard(c, f)
            "depthsurround.eel" -> depth(c, f)
            "autoWideness.eel" -> autoWidth(c, f)
            "stereoFieldManipulator.eel" -> field(c, f)
            "msCentreBoost.eel" -> ms(c, f)
            "stereoPhaseInvert.eel" -> polarity(c, f)
            "swapChannels.eel" -> swap(c, f)
            "stftCentreBoost.eel", "stftCentreCut.eel" -> centre(c, f, name == "stftCentreCut.eel")
            "stftFilter.eel" -> stftFilter(c, f)
            "Joe0Bloggs DRX10K compander-HR.eel" -> drx(c, f)
            "viper_dynamicbass.eel", "viper_dynamicbass_preset.eel" -> bass(c, f, name.endsWith("preset.eel"))
            "lofiDistortionMangler.eel" -> mangler(c, f)
            "audioGlitchGenerator.eel" -> glitch(c, f)
            "dc_remove.eel" -> dc(c, f)
            "firFilter.eel" -> fir(c, f)
            "firlsProc.eel" -> firDesign(c, f)
            "butterworth3Band.eel" -> fixedSplitter(c, f, 3)
            "butterworth8Band.eel" -> fixedSplitter(c, f, 8)
            "polyphaseFilterbank.eel" -> polyphase(c, f, false)
            "polyphaseFilterbankEqualization.eel" -> polyphase(c, f, true)
            "fftConvolution2x4x2.eel", "fftConvolutionHRTF.eel" -> convolution(c, f, name.endsWith("HRTF.eel"))
            "autopeakfilter.eel" -> peakFollower(c, f)
            "metallic-reverb.eel" -> waveguide(c, f)
            "phaseshifter.eel" -> phaser(c, f)
            "pitchDownshift.eel" -> pitch(c, f)
            "decimate.eel" -> decimate(c, f)
            "stereowide.eel", "downmixer.eel" -> fixedMs(c, f, name == "downmixer.eel")
            "stftDenoise.eel" -> denoise(c, f)
            else -> generic(c, f)
        }
        fun decorate(plot: LiveprogStaticPlot) = plot.copy(
            inactive = plot.inactive || !c.enabled,
            notes = plot.notes + listOf(if (c.actualRate) "按驱动实际 ${c.rate} Hz 计算。" else "按配置 ${c.rate} Hz 预览；实际采样率尚未确认。",
                "静图读取当前脚本参数；下方动图读取真实处理前后音频。"))
        return LiveprogChartPair(decorate(result.professional), decorate(result.illustration))
    }

    private fun splitting(c: LiveprogChartConfiguration, f: LiveprogChartFocus, count: Int): LiveprogChartPair {
        val defaults = if (count == 3) doubleArrayOf(400.0, 10000.0) else doubleArrayOf(180.0,800.0,1600.0,2700.0,5600.0,6500.0,10000.0)
        val boundaries = listOf(0.0) + defaults.mapIndexed { i, d -> c.v("freqSplit${i + 1}", d) } + c.rate / 2.0
        val gains = List(count) { i -> c.v("bandPart${i + 1}", if (count == 3) doubleArrayOf(-6.0,0.0,-13.0)[i] else if (i == 0) 6.0 else 0.0) }
        val p = bands(f.leftTitle, "原生分频点与各段目标增益", boundaries, gains,
            notes = listOf("色块显示各分支的边界与增益，不把理想矩形当作 IIR 最终合成频响。", "分频器实际过渡、相位和相加结果见真实前后频谱；交错分频点保持原请求，不替用户排序写回。"))
        val bars = gains.mapIndexed { i, g -> LiveprogBar(if (count == 3) listOf("低频","中频","高频")[i] else "第${i + 1}段", g, i % 3) }
        return LiveprogChartPair(p, barPlot(f.rightTitle, "每段增强／衰减 · dB", bars, -30.0, 15.0))
    }

    private fun midFilter(c: LiveprogChartConfiguration, f: LiveprogChartFocus, high: Boolean): LiveprogChartPair {
        val hz = c.v("freq", if (high) 250.0 else 55.0); val q = c.v("qFactor", 2.0)
        val curves = listOf(freqCurve(c, "中央 M", 0) { hz2 -> C.ONE + filter(hz2, hz, q, c.rate, high) * (if (high) -2.0 else 2.0) },
            freqCurve(c, "侧面 S", 1) { C.ONE })
        val p = frequencyPlot(c, f.leftTitle, "${fmt(hz)} Hz · Q ${fmt(q)} · 原系数模型", curves,
            notes = listOf("脚本过滤 L+R 并${if (high) "减去" else "加回"}两耳：Mid = 1 ${if (high) "−" else "+"} 2H，Side = 1。",
                "严格使用脚本的 b0/b1/b2/a1/a2；高通 b1 是 −2cos(ω)，并非替换成标准教科书高通系数。", "这是中央／侧面输入基底的传递函数，不代表每首歌的声压。"))
        val r = routing(f.rightTitle, "只改变中央分量 · 两侧分量保留", listOf("左右输入", "L＋R", "${if (high) "高通" else "低通"} H", "${if (high) "减回" else "加回"}双耳"),
            listOf("L+R → H → ${if (high) "−" else "+"}L/R；左右差分相消，因此 Side 保持。"))
        return LiveprogChartPair(p, r)
    }

    private fun gain(c: LiveprogChartConfiguration, f: LiveprogChartFocus, g: Double): LiveprogChartPair {
        val p = LiveprogStaticPlot(f.leftTitle, "恒定增益 ${signed(g)} dB", xLabel = "输入 dBFS", yLabel = "输出 dBFS",
            xMinimum = -90.0, xMaximum = 0.0, yMinimum = -90.0, yMaximum = 24.0,
            curves = listOf(curve("保持", -90.0, 0.0, 1, true) { it }, curve("当前", -90.0, 0.0) { it + g }),
            notes = listOf("静图为脚本的电平比例；0 dBFS 以上表示数学运算可能超满刻度，不假设后续链路是否削波。"))
        return LiveprogChartPair(p, barPlot(f.rightTitle, "振幅倍率 · ×", listOf(LiveprogBar("输入", 1.0,1), LiveprogBar("输出", db(g),0)), 0.0, max(2.0,db(g)*1.1)))
    }

    private fun timeAdjustment(c: LiveprogChartConfiguration, f: LiveprogChartFocus): LiveprogChartPair {
        val request = (c.v("slider1",0.0)*c.rate*.001+c.v("slider4",0.0)).toLong().toDouble()
        val actual = request.coerceIn(0.0, c.rate*4.0-1)
        val wet = pow2(c.v("slider2",0.0)); val dry = pow2(c.v("slider3",-120.0))
        val millis = actual/c.rate*1000.0
        val p = if (f.group == "mix") frequencyPlot(c,f.leftTitle,"干／湿增益 + 实际 ${fmt(millis)} ms", listOf(
            freqCurve(c,"合成",0){ hz -> C(dry,0.0)+delay(hz,actual,c.rate)*wet }), notes=listOf("按 dry + wet·exp(−jωD) 计算，延迟先截断为整数采样并限制到固定环形缓冲。"))
        else barPlot(f.leftTitle,"请求与实际缓冲延迟 · 采样",listOf(LiveprogBar("请求",request,1),LiveprogBar("缓冲",actual,0),LiveprogBar("PDC",max(0.0,-request),2)),min(0.0,request),max(1.0,max(abs(request),actual)*1.1),
            listOf("负延迟请求会设置 PDC，但实际缓冲 delaylen = 0；图不把补偿声明画成负时间声波。", "本运行副本每次参数更新重置 PDC，不累计旧请求。"))
        val maxTime = max(1.0,millis*1.3)
        val r = LiveprogStaticPlot(f.rightTitle,"原声 ${fmt(dry)}× · 延迟声 ${fmt(wet)}×",LiveprogPlotStyle.TIMELINE,"ms","振幅 ×",0.0,maxTime,0.0,max(1.1,max(wet,dry)*1.1),
            marks=listOf(LiveprogMark(0.0,dry,"原声",1),LiveprogMark(millis,wet,"延迟声",0)),notes=listOf("标记位置表示实际缓冲时间；不是房间距离，也不是实测峰值。"))
        return LiveprogChartPair(p,r)
    }

    private fun fractionalDelay(c: LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val l=c.v("leftDelay",15.1); val r=c.v("rightDelay",4.2)
        val p=barPlot(f.leftTitle,"分数采样目标 · 采样",listOf(LiveprogBar("左",l,0),LiveprogBar("右",r,1)),0.0,max(1.0,max(l,r)*1.2),
            listOf("原生分数延迟函数内部插值响应不在此臆造；显示请求采样数及其时间换算。"))
        val right=LiveprogStaticPlot(f.rightTitle,"差值 ${fmt(abs(l-r)/c.rate*1000.0)} ms",LiveprogPlotStyle.TIMELINE,"ms","",0.0,max(1.0,max(l,r)/c.rate*1200.0),0.0,1.2,
            marks=listOf(LiveprogMark(l/c.rate*1000.0,1.0,"左",0),LiveprogMark(r/c.rate*1000.0,.6,"右",1)))
        return LiveprogChartPair(p,right)
    }

    private fun chorus(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val ms=min(c.v("slider1",300.0),500000.0/c.rate*1000.0)
        val samples=ms*c.rate/1000; val depth=min(max(c.v("slider7",2.0)/max(.001,ms),0.0),1.0)*samples*.5-1.0
        val period=c.v("slider6",500.0); val fb=pow2(c.v("slider2",-6.0))
        val values=listOf(LiveprogBar("干声",c.v("slider5",0.0),1),LiveprogBar("纯延迟",c.v("slider4",-6.0),0),LiveprogBar("合唱",c.v("slider3",-6.0),2))
        val p=when(f.group){
            "mix" -> barPlot(f.leftTitle,"输出三路 · dB",values,-120.0,12.0)
            "feedback" -> barPlot(f.leftTitle,"反馈倍率 ${fmt(fb)}× · 写入限制 ±4",listOf(LiveprogBar("反馈",fb,0),LiveprogBar("原声写入",1.0,1)),0.0,max(1.2,fb*1.2),listOf("反馈写入先限制到 −4..4；因此反馈高时不能用线性指数回声冒充真实结果。"))
            else -> LiveprogStaticPlot(f.leftTitle,"周期参数 ${fmt(period)} ms · 正弦实际周期 ${fmt(period*2)} ms",LiveprogPlotStyle.LINES,"ms","读头回看 ms",0.0,max(1.0,period*2),min(0.0,(1+2*depth)/c.rate*1000),max(1.0,ms),
                curves=listOf(curve("合唱读头",0.0,max(1.0,period*2)){t->(1+(sin(PI*t/max(.001,period))+1)*depth)/c.rate*1000},line("纯延迟",0.0,max(1.0,period*2),ms,1,true)),
                notes=listOf("按实际 dppossc = π/周期参数 计算：正弦完整周期为该参数的两倍。深度为零时保留原脚本 dpbacksc = −1 的读头边界行为。"))
        }
        val right=LiveprogStaticPlot(f.rightTitle,"原声＋延迟＋合唱 · 并行混合",LiveprogPlotStyle.ROUTING,
            nodes=listOf(LiveprogNode("in","原声",.12,.15,1),LiveprogNode("buffer","延迟缓冲",.12,.78,0),
                LiveprogNode("clean","固定读头",.5,.46,0),LiveprogNode("chorus","合唱读头",.5,.86,2),LiveprogNode("out","输出叠加",.88,.15,0)),
            edges=listOf(LiveprogEdge("in","out","干声 ${c.v("slider5",0.0)} dB",1),LiveprogEdge("in","buffer","原声写入 1×",1),
                LiveprogEdge("buffer","clean","固定延迟 ${ms} ms",0),LiveprogEdge("buffer","chorus","正弦移动读头；周期 ${period*2} ms",2),
                LiveprogEdge("clean","out","纯延迟 ${c.v("slider4",-6.0)} dB",0),LiveprogEdge("chorus","out","合唱 ${c.v("slider3",-6.0)} dB",2),
                LiveprogEdge("clean","buffer","固定读头反馈 ${fb}×；写回先限幅 ±4",0,true)),
            notes=listOf("原声、固定延迟读头和合唱读头分别乘自己的输出系数后并行相加，合唱不是串在纯延迟输出后面。",
                "两读头共享同一环形缓冲；反馈使用固定读头 oss，不是合唱 os。写入为原声＋oss×反馈并限制到 −4…4。",
                "输出系数沿用原脚本 2^(参数/6)，不擅自改成标准 10^(dB/20)；箭头表示音频结构，非房间距离。"))
        return LiveprogChartPair(p,right)
    }

    private fun hadamard(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val base=c.v("slider1",.2)*1000; val factor=c.v("slider12",10.0)
        val d1=doubleArrayOf(.3,.4,1.5,2.0).map{it*base/factor}; val d2=doubleArrayOf(1.13,.83,1.32,.57).map{it*base}
        val dep=c.v("slider14",.01); val a=c.v("slider8",30.0); val b=c.v("slider9",20.0)
        val p=when(f.group){
            "gain" -> gain(c,f,20*log10(pow2(c.v("slider20",0.0)))).professional
            "feedback" -> {
                val x=1/(1+c.v("slider4",2.0));val y=1/(1+c.v("slider5",2.0))
                barPlot(f.leftTitle,"延迟单元内部反馈系数",(listOf(1.0,1.0,1.17,1.27).mapIndexed{i,z->LiveprogBar("A${i+1}",(1-z*x)*.5,0)}+
                    listOf(1.0,1.0,1.27,1.30).mapIndexed{i,z->LiveprogBar("B${i+1}",(1-z*y)*.5,1)}),-.2,.5,listOf("Fb 参数先变为 1/(1+参数)，单元反馈系数再为 (1−系数)×0.5；不将 Fb 直接标成反馈百分比。"))
            }
            else -> LiveprogStaticPlot(f.leftTitle,"A 周期 ${fmt(a)} · B 周期 ${fmt(b)} · 调制 ±${fmt(dep*100)}%",LiveprogPlotStyle.TIMELINE,"ms","",0.0,max(1.0,d2.maxOrNull()!!*(1+dep)*1.1),0.0,1.0,
                marks=(d1.mapIndexed{i,d->LiveprogMark(d,.75,"A${i+1}",0)}+d2.mapIndexed{i,d->LiveprogMark(d,.3,"B${i+1}",1)}),
                regions=(d1.map{LiveprogRegion(it*(1-dep),it*(1+dep),"",0)}+d2.map{LiveprogRegion(it*(1-dep),it*(1+dep),"",1)}),
                notes=listOf("标出八个目标读头和正弦调制范围；真正运行读头还以 0.0001 系数平滑。", "周期 0 会使原式除零；图保留请求值，不模拟不存在的安全回退。阵列循环长度 65536 采样，图示目标不是实测 RT60。"))
        }
        val right=if(f.group=="gain")gain(c,f,20*log10(pow2(c.v("slider20",0.0)))).illustration
            else routing(f.rightTitle,"两组四路混合 · 交叉反馈",listOf("双耳输入","阵列 A ×4","阵列 B ×4","双耳输出"),listOf("A、B 使用 Hadamard 矩阵相加和相减；B 的后两路再返回 A。箭头表示结构，不是房间反射实测。"))
        return LiveprogChartPair(p,right)
    }

    private fun depth(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val s=c.v("strength",800.0); val g=min(1.0,db(s/100-15));val negative=s>=500
        val p=barPlot(f.leftTitle,"强度 ${fmt(s)} · 右反馈${if(negative)"反向" else "同向"}",listOf(LiveprogBar("反馈 1",g,0),LiveprogBar("反馈 2",if(negative)-g else g,1)),-1.0,1.0,
            listOf("gain = 10^((strength/100−15)/20)，强度达到 500 后第二路取反。", "旧脚本输出引用 sampleLeft/sampleRight，而函数形参是 left/right；图只陈述可确认的双延迟反馈结构，不私改算法或补画假干声。"))
        return LiveprogChartPair(p,routing(f.rightTitle,"固定读头 20／140 采样",listOf("左输入","延迟 20","延迟 140","左右输出")))
    }

    private fun autoWidth(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val low=c.v("slider1",-18.0);val high=c.v("slider2",-6.0);val mini=c.v("slider5",0.0);val maxi=c.v("slider6",100.0);val reverse=c.v("slider8",0.0)>.5
        val p=when(f.group){
            "time" -> {
                val attack=max(1.0,ceil(c.v("slider3",1.0)/1000*c.rate));val release=max(1.0,ceil(c.v("slider4",250.0)/1000*c.rate));val end=max(20.0,c.v("slider4",250.0)*4)
                LiveprogStaticPlot(f.leftTitle,"启动 ${fmt(c.v("slider3",1.0))} / 释放 ${fmt(c.v("slider4",250.0))} ms",xLabel="ms",yLabel="归一化宽度",xMinimum=0.0,xMaximum=end,yMaximum=1.0,
                    curves=listOf(curve("展开",0.0,end,0){t->1-(1-1/attack).pow(t*c.rate/1000)},curve("收拢",0.0,end,1){t->(1-1/release).pow(t*c.rate/1000)}),
                    notes=listOf("按原样本更新方程的阶跃目标示意，步长差先除以 ceil(时间×采样率)；不是实时测得的自动宽度。"))
            }
            "detector" -> frequencyPlot(c,f.leftTitle,"只影响检测 · 不直接切除音乐频段",listOf(freqCurve(c,"检测",0){hz->
                val hp=c.v("slider10",20.0);val lp=c.v("slider11",20000.0)
                val h=if(hp>20) standardFilter(hz,hp,1/sqrt(2.0),c.rate,true) else C.ONE
                h*(if(lp<20000) standardFilter(hz,lp,1.0,c.rate,false) else C.ONE)
            }),notes=listOf("控制信号先高通再低通，仅用来决定目标宽度；声音主路径仍由未滤波 L/R 与零宽度信号混合。"))
            "routing" -> routing(f.leftTitle,"零宽度来源：${listOf("左右平均","左声道","右声道")[c.v("slider7",0.0).toInt().coerceIn(0,2)]}",listOf("原始 L/R","检测 HP/LP","宽度平滑","声音混合"))
            else -> LiveprogStaticPlot(f.leftTitle,"电平边界 ${fmt(low)} / ${fmt(high)} dB · ${if(reverse)"反向" else "正向"}",xLabel="检测电平 dB",yLabel="宽度 %",xMinimum=-60.0,xMaximum=24.0,yMinimum=0.0,yMaximum=max(100.0,maxi),
                curves=if(abs(high-low)<1e-12) emptyList() else listOf(curve("稳态目标",-60.0,24.0){level->
                    var w=(level-low)/(high-low)
                    w=min(if(reverse)1-mini/100 else maxi/100,w)
                    w=max(if(reverse)1-maxi/100 else mini/100,w)
                    (if(reverse)1-w else w)*100
                }), notes=listOf("按原目标和钳位顺序计算；检测 dB 使用 6log₂，图横轴是同一刻度。", if(abs(high-low)<1e-12)"两个边界相同，原式分母为零；不伪造有效宽度曲线。" else "静态曲线给出稳定输入下的目标，实时宽度还受启动与释放影响。"))
        }
        val r=if(f.group=="detector"||f.group=="routing") LiveprogStaticPlot(f.rightTitle,"音乐直达混合 · 检测支路只作控制",LiveprogPlotStyle.ROUTING,
            nodes=listOf(LiveprogNode("in","音乐输入",.12,.22,0),LiveprogNode("out","未滤波混合",.88,.22,0),
                LiveprogNode("detect","检测 HP/LP",.22,.76,1),LiveprogNode("width","宽度控制",.72,.76,2)),
            edges=listOf(LiveprogEdge("in","out","原音乐",0),LiveprogEdge("in","detect","检测副本",1),
                LiveprogEdge("detect","width","电平检测",1),LiveprogEdge("width","out","控制宽度",2,true)),
            notes=listOf("HP/LP 仅过滤检测副本，未经此滤波的音乐参与最终混合；虚线为控制关系。"))
            else barPlot(f.rightTitle,"宽度范围 ${fmt(mini)}–${fmt(maxi)}%",listOf(
                LiveprogBar("最小宽度",mini,1),LiveprogBar("最大宽度",maxi,0)),0.0,500.0,
                listOf("1 对应最小宽度 ${mini}%；2 对应最大宽度 ${maxi}%。固定 0…500% 刻度；两个数是同一个宽度控制的上下限，不是 Mid／Side 两个增益。",
                    "脚本默认最小 0%、最大 100%。当前启动 ${c.v("slider3",1.0)} ms、释放 ${c.v("slider4",250.0)} ms，${if(reverse)"反向"else"正向"}映射。",
                    "实时宽度由检测电平与逐采样平滑决定，静图不把配置范围当作音乐当前宽度，不表示距离。"))
        return LiveprogChartPair(p,r)
    }

    private fun field(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val rotation=c.v("slider1",0.0);val width=c.v("slider2",100.0);val centre=c.v("slider3",0.0);val balance=c.v("slider4",0.0)
        fun transform(l:Double,r:Double,angle:Double,w:Double,center:Double,lrPercent:Double):Pair<Double,Double>{
            val radians=angle*.017453292;val scale=w/200;val midScale=min(center/100+1,1.0);val sideScale=1-center/100
            val lr=lrPercent/100;val left=-min(lr,0.0);val left1=-max(lr-1,-1.0);val right=max(lr,0.0);val right1=min(1+lr,1.0)
            val rl=l*cos(radians)-r*sin(radians);val rr=r*cos(radians)+l*sin(radians)
            val m=(rl+rr)*.5*midScale;val s=(rl-rr)*sideScale
            return (m+(s*left1-s*right)*scale)/max(scale,1.0) to (m+(-s*right1+s*left)*scale)/max(scale,1.0)
        }
        fun process(l:Double,r:Double)=transform(l,r,rotation,width,centre,balance)
        fun reference(l:Double,r:Double)=transform(l,r,0.0,100.0,0.0,0.0)
        val a=process(1.0,0.0);val b=process(0.0,1.0)
        val notes=listOf("四柱依次为 1 左输入→左输出、2 左输入→右输出、3 右输入→左输出、4 右输入→右输出；保留系数正负，负值表示反相。",
            "当前旋转 ${rotation}°、宽度 ${width}%、Center ${centre}%、Left/Right ${balance}%；默认依次为 0°、100%、0%、0%。",
            "按旋转、中央／差异比例、左右来源混合的原顺序计算，不改变脚本。旋转为声道向量变换，不表示真实声源角度。",
            "Center 为正时中央系数保持 1、差异系数降低；为负时中央系数降低、差异系数增大，不能把正值画成中央绝对增益增加。",
            "Left/Right 调整的是差异分量在两个输出中的混合。其它参数默认时，+100% 让两个输出均来自原右输入，−100% 均来自原左输入，不是普通左右声像推子。")
        val p=barPlot(f.leftTitle,"四路系数 · 负值表示反相",listOf(LiveprogBar("左输入→左输出",a.first,0),LiveprogBar("左输入→右输出",a.second,1),LiveprogBar("右输入→左输出",b.first,0),LiveprogBar("右输入→右输出",b.second,1)),-3.0,3.0,notes)
        val emphasis=when(f.key){"slider1"->"rotation";"slider2"->"width";"slider3"->"center-balance";"slider4"->"balance";else->"all"}
        val subtitle=when(emphasis){
            "rotation"->"声道旋转 ${signed(rotation)}°"
            "width"->"差异宽度 ${fmt(width)}%"
            "center-balance"->"中央 ${fmt(min(centre/100+1,1.0))}× · 差异 ${fmt(1-centre/100)}×"
            "balance"->"左右来源 ${signed(balance)}%"
            else->"中央／两侧 · 对比默认"
        }
        return LiveprogChartPair(p,stereoDiagramPlot(f.rightTitle,subtitle,::process,::reference,emphasis,4.0,notes))
    }

    private fun ms(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val m=db(c.v("midBoostDB",3.0));val s=db(c.v("sideBoostDB",0.0))
        val p=barPlot(f.leftTitle,"Mid／Side 目标增益 · dB",listOf(LiveprogBar("Mid",c.v("midBoostDB",3.0),0),LiveprogBar("Side",c.v("sideBoostDB",0.0),1)),-15.0,15.0,
            listOf("脚本重建为 L=Mid−Side、R=Mid+Side；当两个增益为零 dB 时相对常用的 L=Mid+Side 定义发生左右交换。保留原路由。"))
        fun process(l:Double,r:Double):Pair<Double,Double>{val mid=(l+r)*.5*m;val side=(l-r)*.5*s;return mid-side to mid+side}
        fun reference(l:Double,r:Double):Pair<Double,Double>{val mid=(l+r)*.5*db(3.0);val side=(l-r)*.5;return mid-side to mid+side}
        return LiveprogChartPair(p,stereoDiagramPlot(f.rightTitle,"中央 ${signed(c.v("midBoostDB",3.0))} · 两侧 ${signed(c.v("sideBoostDB",0.0))} dB",::process,::reference,
            if(f.key=="midBoostDB")"center"else"width",6.0,p.notes+listOf("灰色为本脚本默认 Mid +3 dB、Side 0 dB 的结果。当前 Mid ${c.v("midBoostDB",3.0)} dB、Side ${c.v("sideBoostDB",0.0)} dB。",
                "统一 Side=(左输出−右输出)/2；本脚本按 L=Mid−Side 重建，所以两侧参考探针方向为负，默认也保留这个方向，不私自交换回来。")))
    }

    private fun polarity(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val mode=c.v("mode",0.0).toInt();val l=if(mode and 1!=0)-1.0 else 1.0;val r=if(mode and 2!=0)-1.0 else 1.0
        return LiveprogChartPair(barPlot(f.leftTitle,"+1 保持 · −1 反相",listOf(LiveprogBar("左",l,0),LiveprogBar("右",r,1)),-1.2,1.2),
            LiveprogStaticPlot(f.rightTitle,"左右极性 · 演示波形",xLabel="周期",yLabel="振幅",xMinimum=0.0,xMaximum=1.0,yMinimum=-1.2,yMaximum=1.2,
                curves=listOf(curve("左",0.0,1.0,0){sin(it*2*PI)*l},curve("右",0.0,1.0,1){sin(it*2*PI)*r}),notes=listOf("同一正弦用于展示符号变化；不冒充真实输入。反相不会独自改变该声道幅度频响。")))
    }

    private fun swap(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair = LiveprogChartPair(
        barPlot(f.leftTitle,"交换后各输出 · dB",listOf(LiveprogBar("左输出",c.v("leftGain",0.0),0),LiveprogBar("右输出",c.v("rightGain",0.0),1)),-30.0,10.0),
        LiveprogStaticPlot(f.rightTitle,"左右交换 · 输出各自调增益",LiveprogPlotStyle.ROUTING,
            nodes=listOf(LiveprogNode("leftIn","左输入",.12,.24,0),LiveprogNode("rightIn","右输入",.12,.76,1),
                LiveprogNode("leftOut","左输出",.88,.24,0),LiveprogNode("rightOut","右输出",.88,.76,1)),
            edges=listOf(LiveprogEdge("leftIn","rightOut","右输出增益 ${c.v("rightGain",0.0)} dB",1),
                LiveprogEdge("rightIn","leftOut","左输出增益 ${c.v("leftGain",0.0)} dB",0)),
            notes=listOf("只有两条独立交叉路径：左输入→右输出、右输入→左输出，不能串成四个节点的一条路径。",
                "增益位于交换之后；左输出=${db(c.v("leftGain",0.0))}×原右输入，右输出=${db(c.v("rightGain",0.0))}×原左输入，没有自动响度补偿。")))

    private fun centre(c:LiveprogChartConfiguration,f:LiveprogChartFocus,cut:Boolean):LiveprogChartPair {
        val g=if(cut)-1.0 else db(c.v("gaindb",3.0))-1
        val p=LiveprogStaticPlot(f.leftTitle,"按频格的 Side／Mid 比例决定处理",xLabel="|Side| / |Mid|",yLabel="Mid 变化 ×",xMinimum=0.0,xMaximum=2.0,yMinimum=min(0.0,1-g),yMaximum=max(2.0,1+g),
            curves=listOf(curve("当前规则",0.0,2.0){ratio->1+(1-ratio)*g}),
            notes=listOf("原式 alpha=0.5−0.5√(diffSq/sumSq)，中心修正加入两个声道；以 Mid 非零、Side／Mid 比例为横轴。", "同一频率遇到不同左右相位会产生不同结果，故不画固定 EQ。Mid 接近零时还有原脚本 EPS 分支。"))
        return LiveprogChartPair(p,routing(f.rightTitle,if(cut)"估计中央分量并消除" else "增强或削弱估计中央分量",listOf("左右频格","和／差比较","中心估计","双耳重建")))
    }

    private fun stftFilter(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val low=min(c.v("bandEdge1",100.0).floorSafe(),c.v("bandEdge2",480.0).floorSafe()).coerceIn(0.0,512.0)
        val high=max(c.v("bandEdge1",100.0).floorSafe(),c.v("bandEdge2",480.0).floorSafe()).coerceIn(0.0,512.0)
        val attenuation=20*log10(pow2(-80.0)); val loHz=floor(low/2)*c.rate/1024.0;val hiHz=ceil(high/2)*c.rate/1024.0
        val p=LiveprogStaticPlot(f.leftTitle,"标注 Hz，实际使用数组索引 ${fmt(low)}…${fmt(high)}",LiveprogPlotStyle.BANDS,"scalar 索引","衰减 dB",0.0,512.0,-90.0,0.0,
            regions=listOf(LiveprogRegion(low,high,"作用区",0)),curves=listOf(LiveprogCurve("标量作用",listOf(LiveprogPoint(0.0,0.0),LiveprogPoint(low,0.0),LiveprogPoint(low,attenuation),LiveprogPoint(high,attenuation),LiveprogPoint(high,0.0),LiveprogPoint(512.0,0.0)))),
            notes=listOf("1024 点 STFT 的输出数组按实部／虚部交错。脚本从 low 到 high−1 逐标量相乘，不能把参数直接当作 Hz 或完整复数频格。", "运行副本先 floor、限制 0..512，再排序。完整频格约覆盖 ${fmt(loHz)}..${fmt(hiHz)} Hz；奇数边界只处理某端一个分量，幅相依输入而变。", "实际倍率 2^(−80/6)，换算为 ${fmt(attenuation)} dB。"))
        val r=LiveprogStaticPlot(f.rightTitle,"约 ${fmt(loHz)}–${fmt(hiHz)} Hz · 边界分量可能不成对",LiveprogPlotStyle.BARS,yLabel="衰减 dB",yMinimum=-90.0,yMaximum=0.0,
            bars=listOf(LiveprogBar("边界前",0.0,1),LiveprogBar("作用区",attenuation,0),LiveprogBar("边界后",0.0,1)),notes=p.notes)
        return LiveprogChartPair(p,r)
    }

    private fun drx(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val bass=c.v("bass",.7);val mid=c.v("mids",0.0);val treble=c.v("treble",.4);val gain=c.v("gaindB",-1.0)
        val head=max(10.0,max(bass,max(mid,treble))*12)
        val p=when(f.group){
            "gain" -> gain(c,f,gain).professional
            "bands" -> bands(f.leftTitle,"低／中／高频动态系数",listOf(0.0,c.v("bass_freq",95.0),c.v("treble_freq",8000.0),c.rate/2.0),listOf(bass,mid,treble),listOf("色块表示系数，不是恒定 EQ 增益；真实实现还对频格系数双向平滑。"),"动态系数 ×")
            else -> LiveprogStaticPlot(f.leftTitle,"上限 ${fmt(head)} dB · 总增益 ${signed(gain)} dB",xLabel="突出背景 dB",yLabel="动态修正 dB",xMinimum=0.0,xMaximum=12.0,yMinimum=min(-12.0,min(bass,min(mid,treble))*12),yMaximum=max(12.0,head),
                curves=listOf(curve("低频",0.0,12.0,1){min(it*bass,head)},curve("中频",0.0,12.0,0){min(it*mid,head)},curve("高频",0.0,12.0,2){min(it*treble,head)}),
                notes=listOf("频格当前幅度相对平滑历史突出部分先限制到 12 dB，再乘分段系数并受 headRoomdB 限制。", "这是条件关系；没有读取脚本内部历史，不把它当作实际增益减少量。"))
        }
        return LiveprogChartPair(p,barPlot(f.rightTitle,"动态强度系数 · 负值收拢，正值突出",listOf(LiveprogBar("低频",bass,1),LiveprogBar("中频",mid,0),LiveprogBar("高频",treble,2)),-1.0,2.0))
    }

    private val bassPresets = arrayOf(
        doubleArrayOf(140.0,6200.0,40.0,60.0,10.0,80.0),doubleArrayOf(180.0,5800.0,55.0,80.0,10.0,70.0),
        doubleArrayOf(300.0,5600.0,60.0,105.0,10.0,50.0),doubleArrayOf(600.0,5400.0,60.0,105.0,10.0,20.0),
        doubleArrayOf(100.0,5600.0,40.0,80.0,50.0,50.0),doubleArrayOf(1200.0,6200.0,40.0,80.0,0.0,20.0),
        doubleArrayOf(1000.0,6200.0,40.0,80.0,0.0,10.0),doubleArrayOf(800.0,6200.0,40.0,80.0,10.0,0.0),
        doubleArrayOf(400.0,6200.0,40.0,80.0,10.0,0.0),doubleArrayOf(1200.0,6200.0,50.0,90.0,15.0,10.0),
        doubleArrayOf(1000.0,6200.0,50.0,90.0,30.0,10.0),doubleArrayOf(1100.0,6200.0,60.0,100.0,20.0,0.0),
        doubleArrayOf(1200.0,6200.0,50.0,100.0,10.0,50.0),doubleArrayOf(1200.0,6200.0,60.0,100.0,0.0,30.0),
        doubleArrayOf(1200.0,6200.0,40.0,80.0,0.0,30.0),doubleArrayOf(1000.0,6200.0,60.0,100.0,0.0,0.0),
        doubleArrayOf(1000.0,6200.0,60.0,120.0,0.0,0.0),doubleArrayOf(1000.0,6200.0,80.0,140.0,0.0,0.0),
        doubleArrayOf(800.0,6200.0,80.0,140.0,0.0,0.0))

    private fun bass(c:LiveprogChartConfiguration,f:LiveprogChartFocus,preset:Boolean):LiveprogChartPair {
        val settings=if(preset)bassPresets[c.v("dPreset",0.0).toInt().coerceIn(0,18)] else doubleArrayOf(c.v("dX1",800.0),c.v("dX2",6200.0),c.v("dY1",80.0),c.v("dY2",140.0),c.v("dSideGainX",20.0),c.v("dSideGainY",0.0))
        val strength=c.v("dBassGain",5.0);val boost=1+strength*.2;val special=settings[0]<=120
        val q=min(1600.0,(boost-1)/20*1600)/666+.5
        fun transfer(hz:Double):C {
            if(special)return C.ONE+standardFilter(hz,55.0,q,c.rate,false)*2.0
            val xlo=poles(hz,settings[0],c.rate);val xhi=poles(hz,settings[1],c.rate)
            val ylo=poles(hz,settings[2],c.rate);val yhi=poles(hz,settings[3],c.rate);val z3=delay(hz,3.0,c.rate)
            return z3-xlo+xlo*(yhi-ylo+(z3-yhi)*(settings[4]*.01)+ylo*(settings[5]*.01))*boost
        }
        val curves=if(special)listOf(freqCurve(c,"中央 M",0,::transfer),freqCurve(c,"侧面 S",1){C.ONE}) else listOf(freqCurve(c,"左右各自",0,::transfer))
        val p=frequencyPlot(c,f.leftTitle,if(special)"55 Hz 中央分支 · Q ${fmt(q)}" else "四级极点分支 · 强度 ${fmt(strength)}%",curves,
            notes=listOf("该脚本没有音乐包络或自动频率追踪；静图是当前固定参数的线性分支传递函数。", "普通分支严格包含三采样旧输入和四级一阶极点链，非理想带通。",
                if(special)"X1≤120 时进入 55 Hz LowPass(L+R) 加回分支；强度只改变 Q，X2、Y1、Y2、两个 Side gain 不参与当前声音。" else "X/Y 分频、强度和 Side gain 全部参与当前分支。"))
        val b=listOf(LiveprogBar("强度",strength,0),LiveprogBar("X侧路",settings[4],1,special),LiveprogBar("Y侧路",settings[5],2,special))
        val r=if(special)routing(f.rightTitle,"当前只用 55 Hz 中央增强",listOf("左右输入","L＋R","55 Hz / Q","加回双耳"),p.notes)
            else barPlot(f.rightTitle,"强度／侧路百分比 · 原脚本比例",b,0.0,100.0,p.notes)
        return LiveprogChartPair(p,r)
    }

    private fun mangler(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val bit=c.v("slider7",8.0);val rate=c.v("slider4",12000.0);val crushing=c.v("slider5",1.0)>0
        val gate=c.v("slider8",0.0)/100
        val p=when(f.group){
            "mix" -> barPlot(f.leftTitle,"输入与输出三路 · dB",listOf(LiveprogBar("输入",c.v("slider1",0.0),0),LiveprogBar("原声",c.v("slider2",-3.0),1),LiveprogBar("染色",c.v("slider3",-3.0),2)),-96.0,12.0)
            "resample" -> LiveprogStaticPlot(f.leftTitle,"目标 ${fmt(rate)} Hz · 输入 ${c.rate} Hz",LiveprogPlotStyle.QUANTIZATION,"ms","演示波形",0.0,2.0,-1.1,1.1,
                curves=listOf(curve("原形",0.0,2.0,1){sin(it*2*PI)},curve("保持示意",0.0,2.0,0){t->sin(floor(t*.001*rate)/rate*1000*2*PI)}),
                notes=listOf("演示正弦的采样保持；实际脚本带随机／非线性后续处理，不假定它是完整输出。"))
            "quantize" -> LiveprogStaticPlot(f.leftTitle,"${fmt(bit)} 位 · 位破坏 ${fmt(c.v("slider6",0.0))}",LiveprogPlotStyle.QUANTIZATION,"输入","基础量化",-1.0,1.0,-1.1,1.1,
                curves=listOf(curve("输入",-1.0,1.0,1){it},curve("基础阶梯",-1.0,1.0,0){if(crushing)floor(it*2.0.pow(bit-1))/2.0.pow(bit-1)else it}),
                notes=listOf("仅画原式基础量化步长；Thermonuclear War 还经 17 种位表插值，On+Noisier 和后续整形含额外变化，不用阶梯冒充最终音色。", "Bitcrusher 关闭时，基础量化曲线回到直通。"))
            "gate" -> barPlot(f.leftTitle,"保持 ${fmt((.05+(1-gate)*.3)*1000)} ms · 纵轴线性振幅",listOf(LiveprogBar("阈值",.15+gate*.25,0),LiveprogBar("泄漏",if(gate>.5)0.0 else (1-gate)*.2,2)),0.0,.5,
                listOf("门控还有依输入触发和渐变状态；滑杆百分比不是 dB 门限。"))
            else -> barPlot(f.leftTitle,"滤波阶段：${listOf("旁路","低通","低通＋高通","高通")[c.v("slider15",1.0).toInt().coerceIn(0,3)]} · 纵轴 %",listOf(LiveprogBar("Love",c.v("slider13",75.0),0),LiveprogBar("Jive",c.v("slider14",15.0),1)),0.0,150.0,
                listOf("Love、Jive 控制滤波形状，Attitude 选择离散滤波路径；百分比不等于频响增益。"))
        }
        return LiveprogChartPair(p,routing(f.rightTitle,"输入 → 颗粒／位处理 → 染色 → 混合",listOf("输入增益","保持／位表","门控与染色","干湿混合"),listOf("静图解释所选环节；实际噪声、谐波和完整输出见下方真实采样。")))
    }

    private fun glitch(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val length=c.v("slider1",50.0);val buffers=c.v("slider3",8.0).toInt().coerceIn(1,16);val sync=c.v("slider11",0.0);val mix=c.v("slider2",90.0)
        val p=when(f.group){
            "probability" -> barPlot(f.leftTitle,"触发设置 · %（彼此不相加）",listOf(LiveprogBar("重复",c.v("slider4",70.0),0),LiveprogBar("变调",c.v("slider5",5.0),1),LiveprogBar("反向",c.v("slider6",10.0),2),LiveprogBar("淡出",c.v("slider7",18.0),0)),0.0,100.0,
                listOf("四个是各自的随机条件，不是总和为 100% 的互斥饼图；当前实际随机选择未作为内部指标导出。"))
            "trigger" -> barPlot(f.leftTitle,"触发与起始控制 · %",listOf(LiveprogBar("阈值",c.v("slider8",8.0),0),LiveprogBar("起始",c.v("slider9",15.0),1)),0.0,100.0)
            "mix" -> barPlot(f.leftTitle,"原声与效果的混合设置 · %",listOf(LiveprogBar("原声",100-mix,1),LiveprogBar("切片",mix,0)),0.0,100.0,listOf("为混合控制读数；不是两路音乐的实测能量占比。"))
            "arpeggio" -> {
                val mode=c.v("slider10",0.0).toInt().coerceIn(0,4)
                val ratios=when(mode){1->listOf(1.0,1.25993,1.49830,2.0);2->listOf(1.0,1.18920,1.49830,2.0);3->listOf(1.0,1.49830,2.0);4->listOf(1.0,2.0,3.0);else->listOf(1.0)}
                barPlot(f.leftTitle,"${listOf("关闭","大调","小调","五度","八度")[mode]} · 读出倍率",ratios.mapIndexed{i,r->LiveprogBar("音${i+1}",r,i%3)},0.0,3.2,
                    listOf("倍率直接来自脚本的音表；八度选项最后一个值为 3×，按原脚本显示。"))
            }
            else -> LiveprogStaticPlot(f.leftTitle,if(sync>0)"节拍同步 ${fmt(sync)} × 64分音符" else "每块 ${fmt(length)} ms · ${buffers} 块",LiveprogPlotStyle.TIMELINE,"ms","缓冲槽",0.0,max(1.0,length*buffers),0.0,1.0,
                regions=List(buffers){i->LiveprogRegion(i*length,(i+1)*length,"${i+1}",i%3)},
                notes=listOf("显示设置的缓冲槽时间，不假装知道当前正在录制／播放哪一块。", if(sync>0)"存在有效 tempo 时，原脚本会按 tempo 改写 slider1 的运行值；这里没有实时 BPM 遥测，仍显示保存的长度，不能冒称同步后的实际长度。" else "未启用节拍同步，长度按毫秒请求。"))
        }
        return LiveprogChartPair(p,routing(f.rightTitle,"录制、选择、变换、混合",listOf("音频触发","切片缓冲","随机变换","原声＋切片")))
    }

    private fun dc(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val p=frequencyPlot(c,f.leftTitle,"H(z) = (1−z⁻¹)/(1−0.999z⁻¹)",listOf(freqCurve(c,"滤除后",0){hz->val z=delay(hz,1.0,c.rate);(C.ONE-z)/(C.ONE-z*.999)}),minimumHz=.1,
            notes=listOf("按两个声道独立的一阶原方程计算；滤除直流不等于低频棚架均衡。"))
        val r=LiveprogStaticPlot(f.rightTitle,"偏移消退 · 阶跃示意",xLabel="ms",yLabel="偏移量",xMinimum=0.0,xMaximum=200.0,yMinimum=0.0,yMaximum=1.1,
            curves=listOf(line("固定偏移",0.0,200.0,1.0,1,true),curve("滤除后",0.0,200.0){t->.999.pow(t*c.rate/1000)}))
        return LiveprogChartPair(p,r)
    }

    private fun fir(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val h=importedArrays(c.source).firstOrNull().orEmpty()
        if(h.isEmpty())return generic(c,f)
        val p=frequencyPlot(c,f.leftTitle,"${h.size} 个原脚本系数",listOf(freqCurve(c,"FIR",0){hz->h.foldIndexed(C.ZERO){i,sum,a->sum+delay(hz,i.toDouble(),c.rate)*a}}),
            notes=listOf("直接读取原脚本 importFLTFromStr 的固定 FIR 系数，计算离散傅里叶和；不执行脚本。"))
        return LiveprogChartPair(p,LiveprogStaticPlot(f.rightTitle,"脉冲系数 · 长度 ${h.size} 采样",LiveprogPlotStyle.LINES,"采样","振幅",0.0,max(1.0,h.size-1.0),min(-.05,h.minOrNull()!!),max(.05,h.maxOrNull()!!),
            curves=listOf(LiveprogCurve("系数",h.mapIndexed{i,a->LiveprogPoint(i.toDouble(),a)}))))
    }

    private fun firDesign(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val arrays=importedArrays(c.source);val x=arrays.getOrNull(0).orEmpty();val y=arrays.getOrNull(1).orEmpty()
        val points=x.zip(y){a,b->LiveprogPoint(a*c.rate/2,b)}
        val p=LiveprogStaticPlot(f.leftTitle,"53 阶最小二乘设计 · 目标网格",xLabel="Hz",yLabel="目标振幅",xMinimum=0.0,xMaximum=c.rate/2.0,yMinimum=-.3,yMaximum=2.5,
            curves=listOf(LiveprogCurve("设计目标",points)),notes=listOf("这是原脚本输入给 firls 的 F/M 设计网格；运行时求得的 h 未导出，不把目标曲线标为实际 FIR 频响。"))
        return LiveprogChartPair(p,routing(f.rightTitle,"目标 → 求解 → 固定 FIR → 双耳",listOf("F/M 网格","firls 求解","FIR 系数","左右处理"),p.notes))
    }

    private fun fixedSplitter(c:LiveprogChartConfiguration,f:LiveprogChartFocus,count:Int):LiveprogChartPair {
        val boundaries=if(count==3)listOf(0.0,4000.0,12000.0,c.rate/2.0) else listOf(0.0,180.0,800.0,1600.0,2700.0,5600.0,6500.0,10000.0,c.rate/2.0)
        // Read the literal eqGain assignments: these examples have no parameter annotations.
        val literal=Regex("eqGain\\[(\\d+)]\\s*=\\s*([+\\-]?[0-9.]+)").findAll(c.source).associate{it.groupValues[1].toInt() to (it.groupValues[2].toDoubleOrNull()?:0.0)}
        val gains=List(count){literal[it]?:0.0}
        val p=bands(f.leftTitle,"固定分支 · 增益倍率",boundaries,gains,listOf("仅表示脚本分频与分支系数，不绘制未验证的合成传递函数。", if(count==3)"此三段示例对左右调用共享同一个分频器状态，不能当作两个完全独立的 LTI 通道。" else "此八段示例使用两套独立的声道分频状态。"),"倍率 ×")
        return LiveprogChartPair(p,barPlot(f.rightTitle,"保留／关闭的各分支",gains.mapIndexed{i,v->LiveprogBar("段${i+1}",v,i%3)},0.0,max(1.1,(gains.maxOrNull()?:1.0)*1.1),p.notes))
    }

    private fun polyphase(c:LiveprogChartConfiguration,f:LiveprogChartFocus,eq:Boolean):LiveprogChartPair {
        val n=if(eq)8 else 5
        val v=if(eq)listOf(1.0,0.0,1.0,0.0,1.0,0.0,1.0,0.0)else listOf(0.0,1.0,1.0,0.0,1.0)
        val p=barPlot(f.leftTitle,"${n} 个子带 · ${if(eq)"0.99"else"1.00"} 变形系数",v.mapIndexed{i,a->LiveprogBar("子带${i+1}",a,i%3)},0.0,1.1,
            listOf("子带位置取决于原生多相分析／重建和 warping；没有把它们均匀映射为假频段。",if(eq)"脚本没有赋值 eqGain[7]；新 VM 内存默认零，保留第八带为零，不自行补成 1。" else "五个子带系数直接按原程序显示。"))
        return LiveprogChartPair(p,routing(f.rightTitle,"分析 → 分带控制 → 相位重建",listOf("双耳输入","多相分析","子带乘法","多相重建"),p.notes))
    }

    private fun convolution(c:LiveprogChartConfiguration,f:LiveprogChartFocus,hrtf:Boolean):LiveprogChartPair {
        val p=LiveprogStaticPlot(f.leftTitle,if(hrtf)"内嵌 HRTF 四核卷积"else"内嵌四核 FFT 卷积",LiveprogPlotStyle.ROUTING,
            nodes=listOf(LiveprogNode("in","输入 L/R",.08,.5,0),LiveprogNode("a","LL／LR 核",.5,.2,0),
                LiveprogNode("b","RL／RR 核",.5,.8,1),LiveprogNode("out","输出 L/R",.92,.5,0)),
            edges=listOf(LiveprogEdge("in","a",colorIndex=0),LiveprogEdge("in","b",colorIndex=1),
                LiveprogEdge("a","out",colorIndex=0),LiveprogEdge("b","out",colorIndex=1)),
            notes=listOf("程序在初始化中导入内嵌四路脉冲并建立 FFT 卷积；四核路径并行相加，不是串联两次卷积。", "此图显示核与路由结构；实际音乐前后变化见实时区，不凭扬声器示意声称测得 HRTF 空间距离。"))
        val nodes=listOf(LiveprogNode("l","左输入",.05,.2,0),LiveprogNode("r","右输入",.05,.8,1),LiveprogNode("ol","左输出",.95,.2,0),LiveprogNode("or","右输出",.95,.8,1))
        return LiveprogChartPair(p,LiveprogStaticPlot(f.rightTitle,"直达与交叉路径同时参与",LiveprogPlotStyle.ROUTING,nodes=nodes,
            edges=listOf(LiveprogEdge("l","ol","LL",0),LiveprogEdge("l","or","LR",0,true),LiveprogEdge("r","ol","RL",1,true),LiveprogEdge("r","or","RR",1)),notes=p.notes))
    }

    private fun peakFollower(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val p=LiveprogStaticPlot(f.leftTitle,"固定搜索 60–8000 Hz · FFT 2048",LiveprogPlotStyle.BANDS,"Hz","",20.0,min(20000.0,c.rate/2.0),0.0,1.0,true,
            regions=listOf(LiveprogRegion(60.0,8000.0,"搜索区",0)),notes=listOf("脚本搜索加权最大频格，120 ms 跟随，带宽参数 2 倍频程。实际中心位置是内部状态，没有导出；图不编造当前跟随峰。", "高频斜率权重和非峰值 −120 原刻度参与真实效果，见前后时频图。"))
        return LiveprogChartPair(p,routing(f.rightTitle,"寻找突出峰 → 平滑 → 频格选择",listOf("输入 FFT","范围内找峰","位置平滑","频格输出"),p.notes))
    }

    private fun waveguide(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val ms=1000.0/c.rate*1000
        val p=LiveprogStaticPlot(f.leftTitle,"延迟 1000 采样 · 反馈 0.6",LiveprogPlotStyle.TIMELINE,"ms","结构性回声倍率",0.0,ms*8,0.0,1.1,
            marks=List(8){i->LiveprogMark(ms*(i+1),.6.pow(i),"${i+1}",i%3)},
            notes=listOf("这些标记展示单次调用的反馈循环目标；该旧脚本左右两次 process 共享 counter/buffer，不能把图当作完整双声道脉冲响应。"))
        return LiveprogChartPair(p,routing(f.rightTitle,"缓冲读头 → 输出 → 反馈写回",listOf("输入","插值读头","延迟输出","0.6 反馈"),p.notes))
    }

    private fun phaser(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val p=LiveprogStaticPlot(f.leftTitle,"440–1600 Hz · 0.5 周期／秒",xLabel="s",yLabel="扫描参数 Hz",xMinimum=0.0,xMaximum=2.0,yMinimum=0.0,yMaximum=2000.0,
            curves=listOf(curve("范围扫描",0.0,2.0){440+(1600-440)*(sin(PI*it)+1)*.5}),
            notes=listOf("扫描曲线来自固定 LFO 参数；不是音乐频率跟踪或瞬时幅频响应。", "旧脚本用 phaserFeedback 调用而初始化的是 phaserFb，且 allpass 名称可能共享状态；不将理想六级移相器频响当作这个程序的精确结果。"))
        return LiveprogChartPair(p,routing(f.rightTitle,"原声叠加六级移相支路",listOf("音乐输入","六级全通","慢速扫描","原声＋移相"),p.notes))
    }

    private fun pitch(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val p=barPlot(f.leftTitle,"固定降低一八度 · 读速 0.5×",listOf(LiveprogBar("原始读速",1.0,1),LiveprogBar("降调读速",.5,0)),0.0,1.1,
            listOf("脚本固定 octave=1、半音=0、音分=0；块长 250 ms，重叠 0.5，4 重细分、比例 0.9。", "读速与重叠结构是确定参数；实际移频谐波、瞬态和重叠伪影由前后时频图显示。"))
        return LiveprogChartPair(p,LiveprogStaticPlot(f.rightTitle,"四层切片 · 逐层缩短 0.9×",LiveprogPlotStyle.TIMELINE,"ms","",0.0,250.0,0.0,1.0,
            regions=List(4){i->LiveprogRegion(0.0,250*.9.pow(i),"层${i+1}",i%3)},notes=p.notes))
    }

    private fun decimate(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val p=LiveprogStaticPlot(f.leftTitle,"量化 16 bit · 隔次调用更新",LiveprogPlotStyle.TEXT,
            bars=listOf(LiveprogBar("固定量化位数（bit）",16.0,0),LiveprogBar("每次调用计数步进",.5,1),LiveprogBar("更新周期（次调用）",2.0,2)),
            notes=listOf("参数卡分别展示位数、每次调用步进、调用周期；这些数的单位不同，不放在共用纵轴上比高低。",
                "一个 decimate 函数共享 cnt/out，随后先处理左再处理右；计数器每次加 0.5。不能宣称左右各自独立降到采样率一半。", "静图只演示保持和基础量化原理；真实左右耦合见前后波形。"))
        return LiveprogChartPair(p,LiveprogStaticPlot(f.rightTitle,"共享状态的逐次调用",LiveprogPlotStyle.ROUTING,nodes=listOf(LiveprogNode("l","左调用",.1,.2,0),LiveprogNode("state","cnt / out",.5,.5,2),LiveprogNode("r","右调用",.9,.8,1)),
            edges=listOf(LiveprogEdge("l","state"),LiveprogEdge("state","r")),notes=p.notes))
    }

    private fun fixedMs(c:LiveprogChartConfiguration,f:LiveprogChartFocus,mono:Boolean):LiveprogChartPair {
        val side=if(mono)0.0 else 2.0
        val p=barPlot(f.leftTitle,"Mid ${if(mono)"保留"else"1×"} · Side ${fmt(side)}×",listOf(LiveprogBar("中央",1.0,0),LiveprogBar("两侧",side,1)),0.0,2.2,
            listOf(if(mono)"左右输出均为 (L+R)/2，侧声道抵消。" else "width 固定 2；左右差分振幅相对常规 M/S 重建为 2×，不代表声场实测距离。"))
        fun process(l:Double,r:Double):Pair<Double,Double>{
            val mid=(l+r)*.5
            if(mono)return mid to mid
            val difference=(r-l) // Original width=2: coef_S=width/2=1.
            return mid-difference to mid+difference
        }
        return LiveprogChartPair(p,stereoDiagramPlot(f.rightTitle,if(mono)"共同声音保留 · 两侧差异归零"else"共同声音 1× · 两侧差异 2×",::process,{l,r->l to r},
            "width",2.0,p.notes+listOf("此脚本没有可调参数，当前值即原脚本默认；灰色为未经脚本处理的输入，用于看出固定效果的变化。",
                if(mono)"默认与当前：Mid=1、Side=0；单声道保留共同分量。"else"默认与当前：Mid=1、Side=+2。原式 s=(R−L)·width/2 再 L=Mid−s、R=Mid+s，统一 Side=(L−R)/2 后仍为正 2，不发生左右交换。"),referenceLabel="处理前"))
    }

    private fun denoise(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val p=routing(f.leftTitle,"按频格估计与抑制 · 内容相关",listOf("输入 STFT","频格统计","抑制规则","重叠重建"),
            listOf("STFT 降噪不等于固定 EQ。没有噪声估计内部状态的只读导出，故静图只展示原处理结构，不编造抑制曲线或降噪分贝。"))
        return LiveprogChartPair(p,routing(f.rightTitle,"保留变化声音 · 对比处理前后",listOf("音乐和噪声","逐频格判断","幅度调整","输出音乐"),p.notes))
    }

    private fun generic(c:LiveprogChartConfiguration,f:LiveprogChartFocus):LiveprogChartPair {
        val selected=c.parameters.firstOrNull{it.key==f.key}?:c.parameters.firstOrNull()
        val p=if(selected==null)routing(f.leftTitle,"当前脚本没有可识别的参数声明",listOf("音频输入","导入脚本","音频输出"),listOf("未识别代码的声学意义；不执行脚本建立虚假的频响或声场模型。脚本编辑功能保留。"))
        else LiveprogStaticPlot(f.leftTitle,selected.title,LiveprogPlotStyle.BARS,yLabel="原生参数",yMinimum=min(selected.minimum,selected.maximum),yMaximum=max(selected.minimum+1e-9,selected.maximum),
            bars=listOfNotNull(LiveprogBar("当前",selected.value,0),selected.defaultValue?.let{LiveprogBar("默认",it,1)}),
            notes=listOf("${selected.key} = ${fmt(selected.value)}；声明范围 ${fmt(selected.minimum)}…${fmt(selected.maximum)}。", "导入脚本的单位和声音作用由作者定义；这里显示原始数值，不自动猜成 Hz、dB、毫秒或声场距离。")+if(selected.choices.isNotEmpty())listOf("当前选项：${selected.choices.getOrNull((selected.value-selected.minimum).roundToInt())?:"越界请求"}")else emptyList())
        val selectedIndex=c.parameters.indexOfFirst{it.key==selected?.key}.coerceAtLeast(0)
        val windowStart=(selectedIndex-3).coerceIn(0,(c.parameters.size-8).coerceAtLeast(0))
        val window=c.parameters.drop(windowStart).take(8)
        val items=window.mapIndexed{i,a->LiveprogBar("原列表第${windowStart+i+1}项 · ${a.title}",if(abs(a.maximum-a.minimum)<1e-12)0.0 else ((a.value-a.minimum)/(a.maximum-a.minimum))*100,i%3)}
        val r=if(items.isEmpty())routing(f.rightTitle,"只读监测脚本整体前后",listOf("真实输入","脚本处理","真实输出"),p.notes)
            else barPlot(f.rightTitle,"第 ${windowStart+1}–${windowStart+items.size} 项 · %",items,0.0,100.0,
                listOf("图内 1…${items.size} 对应原列表第 ${windowStart+1}…${windowStart+items.size} 项，共 ${c.parameters.size} 项。跟随当前参数移动最多八项的窗口；每项完整原名和原列表编号见以上对照。",
                    "范围百分比只是控件位置，不是声音能量或处理强度。更多参数仍在原生参数列表；导入代码不执行、不猜测音效类型。"))
        return LiveprogChartPair(p,r)
    }

    private fun LiveprogChartConfiguration.v(key:String,default:Double):Double = parameters.firstOrNull{it.key==key}?.value?.takeIf{it.isFinite()}?:default
    private fun Double.floorSafe()=floor(this)
    private fun db(x:Double)=10.0.pow(x/20)
    private fun pow2(x:Double)=2.0.pow(x/6)
    private fun fmt(x:Double):String=if(!x.isFinite())"—"else if(abs(x)>=1000)String.format(Locale.ROOT,"%.0f",x)else if(abs(x-round(x))<.00001)String.format(Locale.ROOT,"%.0f",x)else String.format(Locale.ROOT,"%.2f",x)
    private fun signed(x:Double)=if(x>0)"+${fmt(x)}"else fmt(x)
    private fun curve(label:String,min:Double,max:Double,color:Int=0,dashed:Boolean=false,fn:(Double)->Double):LiveprogCurve = LiveprogCurve(label,List(145){i->val x=min+(max-min)*i/144;LiveprogPoint(x,fn(x).let{if(it.isFinite())it else Double.NaN})},color,dashed)
    private fun line(label:String,min:Double,max:Double,y:Double,color:Int=0,dashed:Boolean=false)=LiveprogCurve(label,listOf(LiveprogPoint(min,y),LiveprogPoint(max,y)),color,dashed)
    private fun frequencyPlot(c:LiveprogChartConfiguration,title:String,subtitle:String,curves:List<LiveprogCurve>,notes:List<String> = emptyList(),minimumHz:Double=20.0):LiveprogStaticPlot {
        val finite=curves.flatMap{it.points}.map{it.y}.filter{it.isFinite()}
        return LiveprogStaticPlot(title,subtitle,xLabel="Hz",yLabel="dB",xMinimum=minimumHz,xMaximum=min(20000.0,c.rate*.499),yMinimum=min(-24.0,(finite.minOrNull()?:-24.0).coerceAtLeast(-90.0)),yMaximum=max(12.0,(finite.maxOrNull()?:12.0).coerceAtMost(60.0)),logarithmicX=true,curves=curves,notes=notes)
    }
    private fun freqCurve(c:LiveprogChartConfiguration,label:String,color:Int,transfer:(Double)->C):LiveprogCurve {
        val lo=if(recognized(c)=="dc_remove.eel").1 else 20.0;val hi=min(20000.0,c.rate*.499)
        return LiveprogCurve(label,List(181){i->val hz=lo*(hi/lo).pow(i/180.0);val a=transfer(hz).abs;LiveprogPoint(hz,if(a.isFinite())(20*log10(max(1e-9,a))).coerceIn(-180.0,180.0)else Double.NaN)},color)
    }
    private fun barPlot(title:String,subtitle:String,bars:List<LiveprogBar>,min:Double,max:Double,notes:List<String> = emptyList())=LiveprogStaticPlot(title,subtitle,LiveprogPlotStyle.BARS,yMinimum=min,yMaximum=max(min+1e-9,max),bars=bars,notes=notes)
    private fun bands(title:String,subtitle:String,edges:List<Double>,values:List<Double>,notes:List<String> = emptyList(),unit:String="增益 dB"):LiveprogStaticPlot {
        val maxHz=max(20.1,edges.maxOrNull()?:24000.0)
        return LiveprogStaticPlot(title,subtitle,LiveprogPlotStyle.BANDS,"Hz",unit,20.0,maxHz,min(0.0,(values.minOrNull()?:0.0)-1),max(1.0,(values.maxOrNull()?:0.0)+1),true,
            curves=values.mapIndexed{i,v->LiveprogCurve("段${i+1}",listOf(LiveprogPoint(max(20.0,edges[i]),v),LiveprogPoint(max(20.0,edges[i+1]),v)),i%3)},
            regions=values.indices.map{i->LiveprogRegion(max(20.0,edges[i]),max(20.0,edges[i+1]),"段${i+1}",i%3)},notes=notes)
    }
    private fun routing(title:String,subtitle:String,labels:List<String>,notes:List<String> = emptyList()):LiveprogStaticPlot {
        val rows=(labels.size+1)/2
        val nodes=labels.mapIndexed{i,s->LiveprogNode("n$i",s,if(labels.size<=3)i.toDouble()/max(1,labels.size-1)else if(i%2==0).15 else .85,
            if(labels.size<=3).5 else .15+.7*(i/2)/max(1,rows-1),i%3)}
        return LiveprogStaticPlot(title,subtitle,LiveprogPlotStyle.ROUTING,nodes=nodes,edges=(0 until labels.lastIndex).map{i->LiveprogEdge("n$i","n${i+1}",colorIndex=i%3)},notes=notes)
    }
    private fun stereoDiagramPlot(title:String,subtitle:String,current:(Double,Double)->Pair<Double,Double>,
        reference:(Double,Double)->Pair<Double,Double>,emphasis:String="all",maximum:Double=2.0,notes:List<String> = emptyList(),referenceLabel:String="默认"):LiveprogStaticPlot {
        fun probe(label:String,l:Double,r:Double,color:Int):LiveprogStereoProbe {
            val now=current(l,r);val before=reference(l,r)
            return LiveprogStereoProbe(label,(now.first-now.second)*.5,(now.first+now.second)*.5,
                (before.first-before.second)*.5,(before.first+before.second)*.5,color)
        }
        val a=current(1.0,0.0);val b=current(0.0,1.0)
        val ra=reference(1.0,0.0);val rb=reference(0.0,1.0)
        val diagram=LiveprogStereoDiagram(probes=listOf(probe("中央",1.0,1.0,0),probe("两侧",1.0,-1.0,1)),
            leftGain=hypot(a.first,b.first),rightGain=hypot(a.second,b.second),referenceLeftGain=hypot(ra.first,rb.first),referenceRightGain=hypot(ra.second,rb.second),
            emphasis=emphasis,maximum=maximum,referenceLabel=referenceLabel)
        val probes=diagram.probes.map {p->"${p.label}探针：${referenceLabel} Side=${p.referenceSide}、Mid=${p.referenceMid}；当前 Side=${p.side}、Mid=${p.mid}。"}
        return LiveprogStaticPlot(title,subtitle,LiveprogPlotStyle.STEREO,"差异 Side","共同 Mid",-maximum,maximum,-maximum,maximum,
            notes=notes+listOf("命名输入探针固定为：中央 L=1/R=1，两侧 L=1/R=−1；灰色为${referenceLabel}，彩色为当前完整变换结果。",
                "坐标统一为 Side=(左输出−右输出)/2、Mid=(左输出＋右输出)/2，保留正负方向。探针不是当前音乐、物理声源、声压或距离。",
                "左右输出指示采用矩阵各行的长度 √(a²+b²)，作为独立单位输入的系数对照，不能当作真实 RMS 或两耳实际响度。",
                "${referenceLabel}左右输出系数 ${diagram.referenceLeftGain} / ${diagram.referenceRightGain}；当前 ${diagram.leftGain} / ${diagram.rightGain}。固定 ±${maximum} 刻度，不随参数自动缩放。")+probes,
            stereoDiagram=diagram)
    }
    private fun importedArrays(source:String):List<List<Double>> = Regex("importFLTFromStr\\(\"([^\"]+)\"").findAll(source).take(2).map{m->m.groupValues[1].split(',').mapNotNull{it.trim().toDoubleOrNull()}}.toList()

    private data class C(val re:Double,val im:Double) {
        operator fun plus(b:C)=C(re+b.re,im+b.im)
        operator fun minus(b:C)=C(re-b.re,im-b.im)
        operator fun times(b:C)=C(re*b.re-im*b.im,re*b.im+im*b.re)
        operator fun times(b:Double)=C(re*b,im*b)
        operator fun div(b:C):C {val d=b.re*b.re+b.im*b.im;return C((re*b.re+im*b.im)/d,(im*b.re-re*b.im)/d)}
        val abs get()=hypot(re,im)
        companion object {val ONE=C(1.0,0.0);val ZERO=C(0.0,0.0)}
    }
    private fun delay(hz:Double,samples:Double,rate:Int):C {val w=-2*PI*hz*samples/rate;return C(cos(w),sin(w))}
    /** Coefficients exactly as the two stock EEL programs, including nonstandard HP b1. */
    private fun filter(hz:Double,cut:Double,q:Double,rate:Int,high:Boolean):C {
        val w=cut*2*PI/rate;val y=sin(w)/(q*2);val co=cos(w);val z=(1-co)*.5
        val b0=if(high)1-z else z;val b1=if(high)-2*co else 1-co;val b2=b0
        val z1=delay(hz,1.0,rate);val z2=z1*z1
        return (C(b0,0.0)+z1*b1+z2*b2)/(C(1+y,0.0)+z1*(-2*co)+z2*(1-y))
    }
    private fun standardFilter(hz:Double,cut:Double,q:Double,rate:Int,high:Boolean):C {
        val w=cut*2*PI/rate;val a=sin(w)/(2*q);val co=cos(w);val b0=if(high)(1+co)*.5 else (1-co)*.5;val b1=if(high)-(1+co) else 1-co
        val z=delay(hz,1.0,rate)
        return (C(b0,0.0)+z*b1+z*z*b0)/(C(1+a,0.0)+z*(-2*co)+z*z*(1-a))
    }
    private fun poles(hz:Double,cut:Double,rate:Int):C {
        val a=cut*PI/rate;val one=C(a,0.0)/(C.ONE-delay(hz,1.0,rate)*(1-a));return one*one*one*one
    }
}
