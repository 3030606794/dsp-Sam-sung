package me.timschneeberger.rootlessjamesdsp.expansion

import me.timschneeberger.rootlessjamesdsp.R

enum class ExpansionEffectsKind(val id: Int, val title: String, val category: String, val icon: Int) {
    MICROSHIFT(0, "微移调双轨厚化", "空间与声像", R.drawable.ic_expansion_microshift),
    FORMANT(1, "共振峰变换", "音色与变声", R.drawable.ic_expansion_formant),
    FREQUENCY_SHIFT(2, "频率移位器", "音色与变声", R.drawable.ic_expansion_frequency_shift),
    VOCODER(3, "声码器 Vocoder", "音色与变声", R.drawable.ic_expansion_vocoder),
    GATED_REVERB(4, "门控混响", "混响与回声", R.drawable.ic_expansion_gated_reverb),
    PINGPONG(5, "节拍同步乒乓回声", "混响与回声", R.drawable.ic_expansion_pingpong),
    AUTO_WAH(6, "包络滤波 Auto-Wah", "调制与运动", R.drawable.ic_expansion_auto_wah),
    AUTOPAN(7, "自动声像／颤音", "调制与运动", R.drawable.ic_expansion_autopan),
    LOW_PHASE(8, "低频相位塑形", "低频处理", R.drawable.ic_expansion_low_phase),
    TAPE(9, "磁带模拟", "模拟染色", R.drawable.ic_expansion_tape),
    MS_EQ(10, "中侧独立均衡器", "均衡与匹配", R.drawable.ic_expansion_ms_eq),
    MATCH_EQ(11, "参考音色匹配 EQ", "均衡与匹配", R.drawable.ic_expansion_match_eq),
    TRANSIENT(12, "分频瞬态与延音塑形", "动态控制", R.drawable.ic_expansion_transient),
    RESONANCE(13, "自动多峰共振抑制", "动态控制", R.drawable.ic_expansion_resonance),
    SUB_BASS(14, "次谐波低音合成", "低频处理", R.drawable.ic_expansion_sub_bass),
    LOUDNESS(15, "音量联动听感补偿", "听感调节", R.drawable.ic_expansion_loudness),
    DEREVERB(16, "去混响", "音频修复", R.drawable.ic_expansion_dereverb),
    DECLICK(17, "爆点／噼啪修复", "音频修复", R.drawable.ic_expansion_declick),
    DEHUM(18, "自适应嗡声消除", "音频修复", R.drawable.ic_expansion_dehum),
    HEADTRACK(19, "头动跟随虚拟音箱", "耳机空间", R.drawable.ic_expansion_headtrack),
    VOCAL(20, "人声／伴奏独立调节", "声源分离", R.drawable.ic_expansion_vocal),
    SPEAKER_OPTIMIZATION(21, "扬声器优化", "扬声器／频响校正", R.drawable.ic_expansion_speaker_optimization),
    SMART_ADAPT(22, "智能适配", "听感调节", R.drawable.ic_expansion_smart_adapt);

    val prefsName get() = "dsp_expansion_${name.lowercase()}"
    val enableKey get() = "expansion_${name.lowercase()}_enable"
    // 196761 / 132042 are the learned Match EQ profile, not a processor slot.
    val parameter get() = when (this) {
        SPEAKER_OPTIMIZATION -> SpeakerOptimizationProtocol.PARAMETER
        SMART_ADAPT -> SmartAdaptProtocol.PARAMETER
        else -> 196740 + id
    }
    val query get() = when (this) {
        SPEAKER_OPTIMIZATION -> SpeakerOptimizationProtocol.RUNTIME_QUERY
        SMART_ADAPT -> SmartAdaptProtocol.RUNTIME_QUERY
        else -> 132021 + id
    }
}
