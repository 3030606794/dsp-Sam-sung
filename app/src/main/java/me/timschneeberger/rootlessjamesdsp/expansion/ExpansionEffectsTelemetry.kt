package me.timschneeberger.rootlessjamesdsp.expansion

import java.util.Locale

/** Meter positions are the native processor's published values, not reconstructed input guesses. */
internal object ExpansionEffectsTelemetry {
    fun isSpectrum(kind: ExpansionEffectsKind) = kind in setOf(ExpansionEffectsKind.FORMANT, ExpansionEffectsKind.VOCODER, ExpansionEffectsKind.MATCH_EQ)
    fun minimum(kind: ExpansionEffectsKind): Float = when(kind) {
        ExpansionEffectsKind.HEADTRACK -> -180f
        ExpansionEffectsKind.MATCH_EQ -> -12f
        ExpansionEffectsKind.SMART_ADAPT -> -6f
        ExpansionEffectsKind.AUTO_WAH, ExpansionEffectsKind.AUTOPAN, ExpansionEffectsKind.RESONANCE, ExpansionEffectsKind.LOUDNESS -> 0f
        ExpansionEffectsKind.DEHUM -> 47f
        else -> -120f
    }
    fun maximum(kind: ExpansionEffectsKind): Float = when(kind) {
        ExpansionEffectsKind.HEADTRACK -> 180f
        ExpansionEffectsKind.SMART_ADAPT -> 6f
        ExpansionEffectsKind.AUTO_WAH -> 12000f
        ExpansionEffectsKind.AUTOPAN -> 150f
        ExpansionEffectsKind.RESONANCE, ExpansionEffectsKind.LOUDNESS -> 18f
        ExpansionEffectsKind.DEHUM -> 63f
        else -> 12f
    }
    fun title(kind: ExpansionEffectsKind) = when(kind) {
        ExpansionEffectsKind.FORMANT -> "实际处理频谱 · 16 频带"
        ExpansionEffectsKind.VOCODER -> "实际分析包络 · 16 频带"
        ExpansionEffectsKind.MATCH_EQ -> "实际匹配修正 · 低频到高频"
        ExpansionEffectsKind.AUTO_WAH -> "实际扫频位置 · 最近20秒"
        ExpansionEffectsKind.AUTOPAN -> "实际左右增益 · 最近20秒"
        ExpansionEffectsKind.RESONANCE -> "实际最大压低 · 最近20秒"
        ExpansionEffectsKind.LOUDNESS -> "实际高低频补偿 · 最近20秒"
        ExpansionEffectsKind.HEADTRACK -> "实际头部姿态 · 最近20秒"
        ExpansionEffectsKind.DEHUM -> "实际嗡声跟踪 · 最近20秒"
        ExpansionEffectsKind.VOCAL -> "分离后人声与伴奏 · 最近20秒"
        ExpansionEffectsKind.SMART_ADAPT -> "实际低中高频修正 · 最近20秒"
        else -> "实际输入与输出 · 最近20秒"
    }
    fun legend(kind: ExpansionEffectsKind) = when(kind) {
        ExpansionEffectsKind.FORMANT -> "dBFS · 实际频带幅度"
        ExpansionEffectsKind.VOCODER -> "dBFS · 实际分析包络"
        ExpansionEffectsKind.AUTO_WAH, ExpansionEffectsKind.DEHUM -> "Hz · 实际跟踪频率"
        ExpansionEffectsKind.AUTOPAN -> "% · 左 / 右"
        ExpansionEffectsKind.RESONANCE -> "dB · 最大压低"
        ExpansionEffectsKind.LOUDNESS -> "dB · 低频 / 高频补偿"
        ExpansionEffectsKind.HEADTRACK -> "° · 水平 / 俯仰"
        ExpansionEffectsKind.MATCH_EQ -> "dB · 实际频段修正"
        ExpansionEffectsKind.VOCAL -> "dBFS · 人声 / 伴奏"
        ExpansionEffectsKind.SMART_ADAPT -> "dB · 低频 / 高频修正"
        else -> "dBFS · 输入 / 输出"
    }
    fun first(s: ExpansionEffectsRuntime): Float = when(s.kind) {
        ExpansionEffectsKind.AUTO_WAH, ExpansionEffectsKind.DEHUM, ExpansionEffectsKind.HEADTRACK -> s.meter(4)
        ExpansionEffectsKind.AUTOPAN -> s.meter(5) * 100
        ExpansionEffectsKind.RESONANCE, ExpansionEffectsKind.LOUDNESS -> s.meter(5)
        ExpansionEffectsKind.VOCAL -> s.meter(8)
        ExpansionEffectsKind.SMART_ADAPT -> s.meter(2)
        else -> s.inputPeakDb
    }
    fun second(s: ExpansionEffectsRuntime): Float = when(s.kind) {
        ExpansionEffectsKind.AUTO_WAH, ExpansionEffectsKind.DEHUM -> s.meter(4)
        ExpansionEffectsKind.HEADTRACK -> s.meter(5)
        ExpansionEffectsKind.AUTOPAN -> s.meter(6) * 100
        ExpansionEffectsKind.RESONANCE -> s.meter(5)
        ExpansionEffectsKind.LOUDNESS -> s.meter(6)
        ExpansionEffectsKind.VOCAL -> s.meter(9)
        ExpansionEffectsKind.SMART_ADAPT -> s.meter(4)
        else -> s.outputPeakDb
    }
    fun spectrum(s: ExpansionEffectsRuntime): List<Float> = when(s.kind) {
        ExpansionEffectsKind.MATCH_EQ -> s.meters.drop(84).take(32)
        ExpansionEffectsKind.FORMANT, ExpansionEffectsKind.VOCODER -> s.meters.drop(4).take(16)
        else -> emptyList()
    }
    fun unavailable(s: ExpansionEffectsRuntime): String? = when {
        s.kind == ExpansionEffectsKind.SMART_ADAPT -> smartStatus(s)
        s.kind == ExpansionEffectsKind.SPEAKER_OPTIMIZATION && s.meter(4) < .5f -> "当前路由不适用 · 已旁路，保留开关选择"
        s.kind == ExpansionEffectsKind.SPEAKER_OPTIMIZATION && s.meter(7) < 1f -> "正在积累首个完整频谱窗"
        s.kind == ExpansionEffectsKind.HEADTRACK && s.meter(8) < .5f -> "未连接有效头部跟踪设备 · 保持原声"
        s.kind == ExpansionEffectsKind.VOCAL && s.meter(4).toInt() != 3 -> vocalState(s.meter(4).toInt())
        s.kind == ExpansionEffectsKind.VOCAL && s.meter(10) < .5f -> "模型已就绪 · 等待可用分离音频"
        s.kind == ExpansionEffectsKind.MATCH_EQ && s.meter(6) > .5f -> "${if(s.meter(6).toInt()==1)"参考"else"当前"}学习中 ${number(s.meter(7)*100)}%"
        s.kind == ExpansionEffectsKind.MATCH_EQ && s.meter(4) < .5f -> "等待学习参考音乐"
        s.kind == ExpansionEffectsKind.MATCH_EQ && s.meter(5) < .5f -> "参考已学习 · 等待学习当前音乐"
        else -> null
    }
    private fun number(v: Float, decimals: Int = 1) = String.format(Locale.ROOT, "%.${decimals}f", v)
    private fun smartValid(s: ExpansionEffectsRuntime) = s.meters.size >= 28 &&
        s.meters.take(28).all { it.isFinite() } && s.meter(0).toInt() in 0..6
    private fun smartStatus(s: ExpansionEffectsRuntime): String = when {
        !s.enabled -> "智能适配已关闭"
        !s.isProcessing -> "等待活动音频"
        !smartValid(s) -> "适配读数尚未就绪"
        s.meter(0).toInt() == 2 -> "分析积累 ${number(s.meter(1) / 1000)} 秒 · ${SmartAdaptVisuals.routeName(s.meter(22).toInt())}"
        else -> "${SmartAdaptVisuals.phaseName(s.meter(0).toInt())} · ${SmartAdaptVisuals.routeName(s.meter(22).toInt())}"
    }
    private fun vocalState(state: Int) = when(state) {
        1 -> "模型正在加载 · 保持原声"
        2 -> "分离音频正在缓冲"
        3 -> "模型已就绪 · 正在分离"
        -1 -> "分离模型未就绪 · 保持原声"
        -2 -> "模型推理失败 · 保持原声"
        -3 -> "运算未及时完成 · 正在恢复"
        else -> "分离尚未启动"
    }
    fun readout(s: ExpansionEffectsRuntime): String = when(s.kind) {
        ExpansionEffectsKind.SMART_ADAPT -> if(smartValid(s) && s.enabled && s.isProcessing && s.meter(0) >= 2f)
            "实际修正 低 ${number(s.meter(2))} / 中 ${number(s.meter(3))} / 高 ${number(s.meter(4))} dB"
            else "实际修正 低 — / 中 — / 高 — dB"
        ExpansionEffectsKind.SPEAKER_OPTIMIZATION -> "实际混合 ${number(s.meter(3) * 100, 0)}% · 低切 ${number(s.meter(8), 0)} Hz · 高切 ${number(s.meter(9), 0)} Hz"
        ExpansionEffectsKind.MICROSHIFT -> "实际移调差 ${number(s.meter(5))} 音分 · 延迟 ${number(s.meter(6))} ms"
        ExpansionEffectsKind.FORMANT -> "实际处理后频带幅度 · ${s.sampleRate} Hz"
        ExpansionEffectsKind.FREQUENCY_SHIFT -> "实际频移 ${number(s.meter(5))} Hz · 延迟 ${number(s.meter(7), 2)} ms"
        ExpansionEffectsKind.VOCODER -> "实际输入包络驱动内部载波"
        ExpansionEffectsKind.GATED_REVERB -> "门开启 ${number(s.meter(4) * 100)}% · 保持剩余 ${number(s.meter(5))} ms"
        ExpansionEffectsKind.PINGPONG -> "实际回声间隔 ${number(s.meter(4))} ms · ${number(s.meter(5))} BPM"
        ExpansionEffectsKind.AUTO_WAH -> "当前 ${number(s.meter(4))} Hz · 检测 ${number(s.meter(5))} dBFS"
        ExpansionEffectsKind.AUTOPAN -> "实际左增益 ${number(s.meter(5) * 100)}% · 右 ${number(s.meter(6) * 100)}%"
        ExpansionEffectsKind.LOW_PHASE -> "实际 ${number(s.meter(4))} Hz · ${number(s.meter(5), 0)} 级"
        ExpansionEffectsKind.TAPE -> "驱动 ${number(s.meter(7))} dB · 检测 ${number(s.meter(6))} dBFS"
        ExpansionEffectsKind.MS_EQ -> "中声道 8 段 · 侧声道 8 段独立处理"
        ExpansionEffectsKind.MATCH_EQ -> when(s.meter(6).toInt()) {
            1 -> "学习参考中 ${number(s.meter(7) * 100)}% · 静音暂停累计"
            2 -> "学习当前中 ${number(s.meter(7) * 100)}% · 静音暂停累计"
            else -> "参考${if(s.meter(4) > .5f) "已学习" else "未学习"} · 当前${if(s.meter(5) > .5f) "已学习" else "未学习"}"
        }
        ExpansionEffectsKind.TRANSIENT -> "实际增益 低 ${number(s.meter(4))} / 中 ${number(s.meter(5))} / 高 ${number(s.meter(6))} dB"
        ExpansionEffectsKind.RESONANCE -> "实际 ${number(s.meter(4), 0)} 个峰 · 最大压低 ${number(s.meter(5))} dB"
        ExpansionEffectsKind.SUB_BASS -> "合成频率 ${number(s.meter(4))} Hz · 合成 ${number(s.meter(6))} dBFS"
        ExpansionEffectsKind.LOUDNESS -> "媒体音量 ${number(s.meter(4) * 100)}% · 补偿低 ${number(s.meter(5))} / 高 ${number(s.meter(6))} dB"
        ExpansionEffectsKind.DEREVERB -> "实际平均衰减 ${number(s.meter(4))} dB"
        ExpansionEffectsKind.DECLICK -> "累计修复 ${number(s.meter(4), 0)} 点 · 延迟 ${number(s.meter(6), 2)} ms"
        ExpansionEffectsKind.DEHUM -> "实际嗡声 ${number(s.meter(4), 2)} Hz · 移除分量 ${number(s.meter(5))} dBFS"
        ExpansionEffectsKind.HEADTRACK -> if(s.meter(8) > .5f) "头部跟踪已连接 · 水平 ${number(s.meter(4))}° · 数据年龄 ${number(s.meter(9), 0)} ms" else "未连接有效头部跟踪设备 · 保持原声"
        ExpansionEffectsKind.VOCAL -> "${unavailable(s) ?: vocalState(s.meter(4).toInt())} · 延迟 ${number(s.meter(5), 0)} ms · 推理 ${number(s.meter(6), 0)} ms"
    }
}
