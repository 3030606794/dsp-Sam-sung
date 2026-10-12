package me.timschneeberger.rootlessjamesdsp.liveprog

import java.io.File
import java.security.MessageDigest

/** Display-only translations. Fingerprints exclude saved parameter values, never DSP code. */
internal object BuiltinLiveprogMetadata {
    private data class Builtin(val fingerprint: String, val title: String)
    private val builtins = mapOf(
        "3bandSplitting.eel" to Builtin("1e2b0702e43df2f29876b1c51bf5f260f0a8cc6216a95778ac7b39e1b4585bf9", "3 频段分频"),
        "8bandSplitting.eel" to Builtin("5859faf0b8dd2ff3287b0f3b959ab8e12c1936437f6ae9dec406c56ce0368884", "8 频段分频"),
        "Joe0Bloggs DRX10K compander-HR.eel" to Builtin("0163384f17f0f8c5e51964a28a0c8ba6c0bd67f43023c8c9a32576fe2bfbcb3b", "Joe0Bloggs DRX10K 动态处理"),
        "audioGlitchGenerator.eel" to Builtin("8ad4147fcff299cef57b1301148b60978ff236cd3be3a0eec6ec0045216c475b", "Avocado 闪断效果生成器"),
        "autoWideness.eel" to Builtin("3d42fd18bf2402abbe2c24683c0ccb327e2d55d1bbd9c8676735d4302ebc914b", "自动声场扩展"),
        "autopeakfilter.eel" to Builtin("ffb1828138b60643c32fb9b1ab8d6daf7f04af89b54b2c5ae668fd91054eef0b", "FFT 峰值跟随滤波器"),
        "butterworth3Band.eel" to Builtin("16b1484ae50a9c54d88f1f0a686859061082132ba32ea52576675f453c15fdd1", "巴特沃斯 3 频段（均衡示例）"),
        "butterworth8Band.eel" to Builtin("66a6510ca2311e59c5186fff5c29d55712b041ccb6a8c6b8f4463ac1fb0051fa", "巴特沃斯 8 频段（均衡示例）"),
        "dc_remove.eel" to Builtin("7f8b70235d1dd153e36e53b937d8b6be14b1e19593bd6b4a2c4a4402b8617a6e", "直流滤除"),
        "decimate.eel" to Builtin("739fb8994ee411bbd9908d5256a42bf5814cce7a4478bf2d6e78c383b1656583", "降采样失真"),
        "delayChorus.eel" to Builtin("4cdc731f38d884a3d5ec83636bbaa943f4a2b04ab11174f93668dc01215aaf7d", "延迟与合唱"),
        "depthsurround.eel" to Builtin("270643e634973e235865354050e57ceebe279192f9bc4d937d501aa8fc0ba57c", "深度环绕"),
        "downmixer.eel" to Builtin("2db8c96c2997719b4f531079eb0833a495cb9b04ea6e7d49ace3b872e031eefd", "立体声转单声道"),
        "fftConvolution2x4x2.eel" to Builtin("b83d95ab96b34d59e7ef00641455e32269001182470d667dfe19a29e77e906ed", "2×4×2 FFT 组合卷积"),
        "fftConvolutionHRTF.eel" to Builtin("1013e69f3a6aea26b403571d89ebe33bee0710e2dcb2c7145106fdf01d062b7b", "2×4×2 HRTF 卷积"),
        "firFilter.eel" to Builtin("5c186b86c08c72b30f0c9e0e705c7252893afdf4380fad8b2454787e29c67cc7", "FIR 滤波"),
        "firlsProc.eel" to Builtin("3acd4b4173de973ab0da08c18c667d24fa51053e285bb495523dde7e99add7a0", "最小二乘 FIR 设计与处理（直接型）"),
        "fractionalDelayline.eel" to Builtin("e3aa63770bf8d5bd576632f765838d3b01e49086e29da663ca1aec0d9d985a34", "分数延迟线"),
        "gainControl.eel" to Builtin("1300c6670dbdf5401d5aa58cc8056206aa105899122b08f9df60930cee9f983d", "增益控制"),
        "hadamVerb.eel" to Builtin("485f78d2536d9d23b098035549e12b5ca4825926e4f860a360aed1e9a98d5b08", "Hadamard 可变延迟阵列"),
        "highpass.eel" to Builtin("69fb1fb0d6f033e1ec26bf69d9d4870f84b5984bb2f98ddeba71a1aa97db6a7d", "高通滤波器"),
        "lofiDistortionMangler.eel" to Builtin("65836142f219ca28ad0b9d8756ee1588501fdcd5a1f2c346fbc62bbaf3911ae6", "Paranoia 失真处理器"),
        "lowpass.eel" to Builtin("ea4d00336e59827fb65de01376a1c40529a36fead2b654beebab84145295bd01", "低通滤波器"),
        "metallic-reverb.eel" to Builtin("86a24e2a4bcb65f62cd55c2eab147234b7208237e5383bd6dcded838269ce31b", "波导延迟效果"),
        "msCentreBoost.eel" to Builtin("cd19c7693337e03ea7296503f281000f0453399dfad247ee5ca6ee9b6bd200f8", "中侧声道中心增强"),
        "phaseshifter.eel" to Builtin("96b58f4be108db4ebc70b63b4dafb7ddc74469c7eccb1840bbe3e045cf82eb17", "6 级移相器"),
        "pitchDownshift.eel" to Builtin("0153334fc59314c8b89a68142a52065eb4466881fb6d2231d5ea32404a55f276", "降调器 2"),
        "polyphaseFilterbank.eel" to Builtin("436c9aced780f90ef11ea9a34c1195c728515387ee66c84a32eca29af7e00553", "多相滤波器组"),
        "polyphaseFilterbankEqualization.eel" to Builtin("f5100ceda82016aed8841560bea307281f6618bfb0e9c33f38272a7bc8f490d7", "多相滤波器组（均衡示例）"),
        "stereoFieldManipulator.eel" to Builtin("e43fc2cc0b2749d73f17e84e9adccc2bcba47973886ccbfeac10e8351375760b", "立体声场调节"),
        "stereoPhaseInvert.eel" to Builtin("d406cb6ebdef33695c338c96dd0071db72bb6276787f178c90a27a37dd7d515e", "声道极性控制"),
        "stereowide.eel" to Builtin("2ca79dd10ca1424f61dd05e6c8d6730405e4ebac31a679edcdefa28d532fa688", "立体声扩展"),
        "stftCentreBoost.eel" to Builtin("49b31a30b02d631b10b21a0d9876d5cc179a13be941463fa7464637bde325146", "STFT 中心增强"),
        "stftCentreCut.eel" to Builtin("3ed1a2d73fc0312689cafaab0e4609319e736dceac5ee62417b6e8d7ef45e2f4", "STFT 中心消除"),
        "stftDenoise.eel" to Builtin("277fd00faf753e528a3c4326d6671dfe9299c48147dc10d731b6a1443379ab0f", "STFT 降噪"),
        "stftFilter.eel" to Builtin("cb5170fcbc798d72aee35a20885284ab1030f0c50e121fa2b9bfefd0ec276b4c", "STFT 滤波器"),
        "swapChannels.eel" to Builtin("e7980671fa1dddbd4df68b4a40d5a3568871d3de0f61251e03314fb8112c9216", "左右声道交换"),
        "timeAdjustment.eel" to Builtin("87690c1a529dd7688b3dc983e10ed3f4e3aabeb8b07ba1faf1e633b83b6d56e0", "时间调整（延迟）"),
        "viper_dynamicbass.eel" to Builtin("689dc7f39afb19f6a15f708c3b30bec495864eeb9f97fe92ccf1a964094dd390", "动态低音（基于 ViperFX，自定义）"),
        "viper_dynamicbass_preset.eel" to Builtin("d802fe95b8cdd641b80fd292469b067d42e651575e0c08644aa6a42005630e0b", "动态低音（基于 ViperFX，预设）"),
    )
    private val legacyBass = mapOf(
        "viper_dynamicbass.eel" to "338b0f9b5331f562ca87a233ccfd0dbaef4bebe89deafbd47c64524a39aa4043",
        "viper_dynamicbass_preset.eel" to "ce4788eed128e47ea283e0538b56d99c257289af1fd3345808f167c0af321dab",
    )

    private fun normalized(text: String) = text.replace("\r\n", "\n")
    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun fingerprint(contents: String): String {
        var source = normalized(contents)
        annotation.findAll(source).map { it.groupValues[1] }.distinct().toList().forEach { key ->
            source = EelNumberRangeProperty.replaceVariable(key, "VALUE", source) ?: source
        }
        source = Regex("""(?m)^(dBassGain:)(?:100|60|5)(<)""").replace(source) {
            "${it.groupValues[1]}DEFAULT${it.groupValues[2]}"
        }
        return digest(source)
    }

    fun identifiedTitle(fileName: String, contents: String): String? {
        val builtin = builtins[fileName] ?: return null
        return builtin.title.takeIf { builtin.fingerprint == fingerprint(contents) }
    }

    private val annotation = Regex("""(\w+):-?\d*\.?\d*<""")
    fun isParameterLine(line: String): Boolean = '<' in line && annotation.containsMatchIn(line)

    /** Exact old stock files only. Saved values or edited scripts are left byte-for-byte intact. */
    fun upgradeUntouchedBass(file: File, bundled: String): Boolean {
        val expected = legacyBass[file.name] ?: return false
        if (!file.isFile || digest(normalized(file.readText())) != expected) return false
        file.writeText(bundled)
        return true
    }

    fun parameterLine(line: String): String {
        val separator = line.indexOf('>')
        if (separator < 0) return line
        var definition = line.substring(0, separator + 1)
        definition = Regex("""\{([^}]*)}""").replace(definition) { match ->
            "{" + match.groupValues[1].split(',').joinToString(",") { text(it.trim()) } + "}"
        }
        return definition + text(line.substring(separator + 1).trim())
    }

    fun bassDefault(line: String): String =
        Regex("""^(dBassGain:)(?:100|60)(<)""").replace(line) { "${it.groupValues[1]}5${it.groupValues[2]}" }

    fun text(value: String): String = labels[value] ?: value

    private val labels = mapOf(
        "Minimum Center Freq (Hz)" to "最低中心频率（Hz）",
        "Maximum Center Freq (Hz)" to "最高中心频率（Hz）",
        "Filter Width (oct)" to "滤波带宽（倍频程）",
        "Peak Gain (dB)" to "峰值增益（dB）",
        "Non-Peak Gain (dB)" to "非峰值增益（dB）",
        "Filter Position Attack Time (ms)" to "滤波位置启动时间（ms）",
        "High End Slope" to "高频斜率",
        "Delay" to "延迟",
        "Feedback" to "反馈",
        "Minimum frequency (Hz)" to "最低频率（Hz）",
        "Maximum frequency (Hz)" to "最高频率（Hz）",
        "Rate (cps)" to "速率（周期/秒）",
        "Depth" to "深度",
        "Octaves Down" to "降低八度数",
        "Semitones Down" to "降低半音数",
        "Cents Down" to "降低音分数",
        "Chunk Size (ms)" to "分块长度（ms）",
        "Overlap Size" to "重叠长度",
        "Dry Mix (dB)" to "干声混合（dB）",
        "Subdivide Ratio" to "细分比例",
        "Subdivide" to "细分",
        "Stereo Width" to "立体声宽度",
        "Bass strength (%)" to "低音强度（%）",
        "Preset" to "预设",
        "Extreme Headphone (v2)" to "极致头戴式耳机（v2）",
        "High-end Headphone (v2)" to "高端头戴式耳机（v2）",
        "Common Headphone (v2)" to "普通头戴式耳机（v2）",
        "Low-end Headphone (v2)" to "入门头戴式耳机（v2）",
        "Common Earphone (v2)" to "普通耳塞（v2）",
        "Extreme Headphone (v1)" to "极致头戴式耳机（v1）",
        "High-end Headphone (v1)" to "高端头戴式耳机（v1）",
        "Common Headphone (v1)" to "普通头戴式耳机（v1）",
        "Common Earphone (v1)" to "普通耳塞（v1）",
        "Apple Earphone" to "苹果耳塞",
        "Monster Earphone" to "魔声耳塞",
        "Moto Earphone" to "摩托罗拉耳塞",
        "Philips Earphone" to "飞利浦耳塞",
        "Unknown Type I" to "未知类型 I",
        "Unknown Type II" to "未知类型 II",
        "Unknown Type III" to "未知类型 III",
        "Unknown Type IV" to "未知类型 IV",
        "Buffer Length (ms)" to "缓冲长度（ms）",
        "Mix (%)" to "混合比例（%）",
        "Buffers" to "缓冲区数量",
        "Repeat Probability (%)" to "重复概率（%）",
        "Pitch Modulation Probability (%)" to "音高调制概率（%）",
        "Reverse Probability (%)" to "反向概率（%）",
        "Fadeout Probability (%)" to "淡出概率（%）",
        "Threshold (%)" to "阈值（%）",
        "Glitch Attack (%)" to "闪断起始强度（%）",
        "Arpeggiator Mode" to "琶音模式",
        "Tempo Sync (64th notes, 0 to disable)" to "节拍同步（64 分音符，0 为关闭）",
        "Off" to "关闭",
        "On" to "开启",
        "Major" to "大调",
        "Minor" to "小调",
        "Fifths" to "五度",
        "Octaves" to "八度",
        "Low frequencies gain (dB)" to "低频增益（dB）",
        "Mid frequencies gain (dB)" to "中频增益（dB）",
        "High frequencies gain (dB)" to "高频增益（dB）",
        "0% Wideness @ (dB)" to "0% 声场宽度对应电平（dB）",
        "100% Wideness @ (dB)" to "100% 声场宽度对应电平（dB）",
        "Attack (ms)" to "启动时间（ms）",
        "Release (ms)" to "释放时间（ms）",
        "Min Width (%)" to "最小声场宽度（%）",
        "Max Width (%)" to "最大声场宽度（%）",
        "Left and right" to "左右声道",
        "Left" to "左声道",
        "Right" to "右声道",
        "0% Signal" to "零宽度信号来源",
        "Reverse Mode" to "反向模式",
        "Director HP (Hz)" to "控制信号高通（Hz）",
        "Director LP (Hz)" to "控制信号低通（Hz）",
        "Delay (ms)" to "延迟（ms）",
        "Feedback (dB)" to "反馈增益（dB）",
        "Output Wet (Chorus) (dB)" to "合唱湿声输出（dB）",
        "Output Wet (Clean) (dB)" to "纯延迟湿声输出（dB）",
        "Output Dry (dB)" to "干声输出（dB）",
        "Chorus Period (ms)" to "合唱周期（ms）",
        "Chorus Length (ms)" to "合唱长度（ms）",
        "Strength" to "强度",
        "Fractional delay L (samples)" to "左声道分数延迟（采样）",
        "Fractional delay R (samples)" to "右声道分数延迟（采样）",
        "Volume gain (dB)" to "音量增益（dB）",
        "Base Delay (s)" to "基础延迟（s）",
        "Fb 1" to "反馈 1",
        "Fb 2" to "反馈 2",
        "VLFO cycle A" to "超低频振荡周期 A",
        "B" to "超低频振荡周期 B",
        "Factor between arrays" to "阵列间倍率",
        "Modulation depth" to "调制深度",
        "Gain (dB)" to "增益（dB）",
        "gain (dB)" to "增益（dB）",
        "Cut-off frequency" to "截止频率",
        "Q factor" to "Q 值",
        "Bass dynamics (x)" to "低频动态（倍）",
        "Mids dynamics (x)" to "中频动态（倍）",
        "Treble dynamics (x)" to "高频动态（倍）",
        "Bass band (Hz)" to "低频分界（Hz）",
        "Treble band (Hz)" to "高频分界（Hz）",
        "Input Gain (dB)" to "输入增益（dB）",
        "Dry Out (dB)" to "干声输出（dB）",
        "Wet Out (dB)" to "湿声输出（dB）",
        "Bad Resampler (Hz)" to "劣化重采样频率（Hz）",
        "Bitcrusher" to "位深失真",
        "On+Noisier" to "开启并增加噪声",
        "Thermonuclear War" to "强烈失真",
        "Bitdepth" to "位深",
        "Gate (%)" to "门限（%）",
        "Love (%)" to "柔化程度（%）",
        "Jive (%)" to "律动程度（%）",
        "Attitude" to "风格",
        "No" to "无",
        "Murky" to "浑浊",
        "Confused" to "混乱",
        "Unpleasant" to "刺耳",
        "Mid boost (dB)" to "中央信号增益（dB）",
        "Side boost (dB)" to "侧向信号增益（dB）",
        "Normal" to "正常",
        "Both" to "双声道",
        "Polarity Mode" to "极性模式",
        "Rotate (°)" to "旋转（°）",
        "Width (%)" to "宽度（%）",
        "Center (%)" to "中心（%）",
        "Left/Right (%)" to "左右平衡（%）",
        "Boost gain (dB)" to "增强增益（dB）",
        "Band edge 1 (Hz)" to "频段边界 1（Hz）",
        "Band edge 2 (Hz)" to "频段边界 2（Hz）",
        "Left post-gain (dB)" to "左声道后级增益（dB）",
        "Right post-gain (dB)" to "右声道后级增益（dB）",
        "Delay amount (ms)" to "延迟时长（ms）",
        "Wet mix (dB)" to "湿声混合（dB）",
        "Dry mix (dB)" to "干声混合（dB）",
        "Additional delay amount (samples)" to "附加延迟（采样）",
        "filter" to "滤波",
        "repair" to "修复",
        "processing" to "处理",
        "stereo" to "立体声",
        "splitting" to "分频",
        "mangler" to "失真处理",
        "glitch" to "闪断",
        "surround" to "环绕",
        "delay" to "延迟",
        "chorus" to "合唱",
        "modulation" to "调制",
        "distortion" to "失真",
        "lo-fi" to "低保真",
        "dynamics" to "动态",
        "reverb" to "混响",
        "gain" to "增益",
        "pitch" to "音高",
        "phase" to "相位",
        "polyphase" to "多相",
        "bass" to "低音",
        "viper" to "ViperFX",
        "Split 1 at frequency (Hz)" to "分频点 1（Hz）",
        "Split 2 at frequency (Hz)" to "分频点 2（Hz）",
        "Split 3 at frequency (Hz)" to "分频点 3（Hz）",
        "Split 4 at frequency (Hz)" to "分频点 4（Hz）",
        "Split 5 at frequency (Hz)" to "分频点 5（Hz）",
        "Split 6 at frequency (Hz)" to "分频点 6（Hz）",
        "Split 7 at frequency (Hz)" to "分频点 7（Hz）",
        "Gain between 0Hz and split 1 (dB)" to "0 Hz 至分频点 1 增益（dB）",
        "Gain between split 1 and split 2 (dB)" to "分频点 1 至 2 增益（dB）",
        "Gain between split 2 and split 3 (dB)" to "分频点 2 至 3 增益（dB）",
        "Gain between split 3 and split 4 (dB)" to "分频点 3 至 4 增益（dB）",
        "Gain between split 4 and split 5 (dB)" to "分频点 4 至 5 增益（dB）",
        "Gain between split 5 and split 6 (dB)" to "分频点 5 至 6 增益（dB）",
        "Gain between split 6 and split 7 (dB)" to "分频点 6 至 7 增益（dB）",
        "Gain between split 7 and 24000Hz (dB)" to "分频点 7 至 24000 Hz 增益（dB）",
        "Filter 1 Minimum frequency (Hz)" to "滤波器 1 最低频率（Hz）",
        "Filter 1 Maximum frequency (Hz)" to "滤波器 1 最高频率（Hz）",
        "Filter 1 Side gain" to "滤波器 1 侧向增益",
        "Filter 2 Minimum frequency (Hz)" to "滤波器 2 最低频率（Hz）",
        "Filter 2 Maximum frequency (Hz)" to "滤波器 2 最高频率（Hz）",
        "Filter 2 Side gain" to "滤波器 2 侧向增益",
    )
}
