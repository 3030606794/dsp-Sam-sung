package me.timschneeberger.rootlessjamesdsp.fusion

/** Focus groups observe the existing room controls; they never own parameter or listening callbacks. */
internal enum class RoomReverbChartFocus(val leftTitle: String, val rightTitle: String, val liveTitle: String) {
    ROOM("房型与基底参数", "房型声场示意", "场景混响实际输出"),
    DECAY("尾音参考衰减", "尾音淡出示意", "真实湿声与尾音"),
    EARLY("早反射抽头时序", "早反射路径示意", "早反射与实际输出"),
    PREDELAY("附加湿声预延迟", "干湿到达示意", "预延迟处理输出"),
    LEVELS("三路相对增益", "干声早反射尾音", "真实输出电平"),
    MIX("干湿联动系数", "原声与湿声比例", "真实干湿处理输出"),
    WIDTH("早反射与尾音矩阵", "两层立体声示意", "双声道声场与相关度"),
    TONE("湿声滤波与阻尼", "尾音明暗示意", "真实频谱与声场"),
    OUTPUT("整体输出增益", "整体电平示意", "真实输出与超限"),
    MONITOR("诊断测点与范围", "监测通道示意", "真实峰值RMS与声场"),
    MATCH("A/B 测量与衰减", "等响度对照示意", "A/B 实际测量"),
    AUDITION("试听与快照路径", "当前试听示意", "实际试听输出");

    companion object {
        fun fromKey(key: String): RoomReverbChartFocus? = when (key) {
            RoomReverbParameters.BASE, RoomReverbParameters.PROFESSIONAL, RoomReverbParameters.ENABLED,
            "room_reverb_presets" -> ROOM
            RoomReverbParameters.TIME_ON, RoomReverbParameters.rt60.key -> DECAY
            RoomReverbParameters.earlyScale.key -> EARLY
            RoomReverbParameters.PREDELAY_ON, RoomReverbParameters.predelay.key -> PREDELAY
            RoomReverbParameters.LEVELS_ON, RoomReverbParameters.dryTrim.key,
            RoomReverbParameters.earlyTrim.key, RoomReverbParameters.lateTrim.key -> LEVELS
            RoomReverbParameters.LINKED, RoomReverbParameters.mix.key -> MIX
            RoomReverbParameters.WIDTHS_ON, RoomReverbParameters.earlyWidth.key, RoomReverbParameters.lateWidth.key -> WIDTH
            RoomReverbParameters.TONE_ON, RoomReverbParameters.lowCut.key,
            RoomReverbParameters.highCut.key, RoomReverbParameters.damp.key -> TONE
            RoomReverbParameters.OUTPUT_ON, RoomReverbParameters.output.key -> OUTPUT
            RoomReverbParameters.METERS, "room_reverb_actual" -> MONITOR
            "room_reverb_measure", "room_reverb_match" -> MATCH
            "room_reverb_save_a", "room_reverb_save_b", "room_reverb_audition", "room_reverb_stop" -> AUDITION
            else -> null
        }
    }
}
