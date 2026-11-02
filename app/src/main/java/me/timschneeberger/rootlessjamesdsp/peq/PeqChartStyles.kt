package me.timschneeberger.rootlessjamesdsp.peq

/** Display-only styles shared by settings and the in-page style shortcuts. */
internal object PeqChartStyles {
    val staticNames = listOf("彩色填充", "深色仪表", "精密线图", "冷暖增减", "带宽聚焦")
    val realtimeNames = listOf("彩色柱谱", "渐变面积", "双线对照", "双色柱谱", "霓虹轮廓")

    fun staticStyle(value: Int): Int = value.takeIf { it in staticNames.indices } ?: 0
    fun realtimeStyle(value: Int): Int = value.takeIf { it in realtimeNames.indices } ?: 0
}
