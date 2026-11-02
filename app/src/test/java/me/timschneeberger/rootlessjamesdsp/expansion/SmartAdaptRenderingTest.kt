package me.timschneeberger.rootlessjamesdsp.expansion

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.ContextThemeWrapper
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.effectvisuals.EffectVisualView
import me.timschneeberger.rootlessjamesdsp.ui.EffectIconStyle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h800dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmartAdaptRenderingTest {
    private val kind = ExpansionEffectsKind.SMART_ADAPT
    private fun context(night: Boolean = false): ContextThemeWrapper {
        val base = RuntimeEnvironment.getApplication()
        val config = Configuration(base.resources.configuration).apply {
            fontScale = 1.5f
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_RootlessJamesDSP)
    }

    @Test fun narrowLargeFontLayoutKeepsBothStaticViewsAlignedAndAllGraphTextAtTwelveSp() {
        listOf(false, true).forEach { night ->
            val ctx = context(night)
            val d = ctx.resources.displayMetrics.density
            val values = mapOf<String, Any>(kind.enableKey to true,
                ExpansionEffectsParameters.control(kind, "target").key to 3000)
            for (focus in listOf("strength", "analysis", "width", "trim", "transient", "device", "intensity")) {
                val key = ExpansionEffectsParameters.control(kind, focus).key
                val header = ExpansionEffectsHeader(ctx, kind, true, false, true, {}, {}, {})
                header.setConfiguration(ExpansionEffectsVisuals.static(kind, values, key), values, key)
                val meters = MutableList(116) { 0f }.apply {
                    this[0] = 3f; this[1] = 2500f; this[2] = 1.2f; this[3] = -.8f; this[4] = .4f
                    this[5] = .08f; this[6] = -1.2f; this[11] = .7f; this[15] = .35f
                    this[22] = 2f; this[24] = 2.5f; this[25] = 3f
                    this[28] = 3f; this[29] = 4f
                }
                header.showSample(ExpansionEffectsRuntime(kind, true, 48000, 48000, 1, -12f, -14f, -18f, meters, true, true))
                header.measure(View.MeasureSpec.makeMeasureSpec((320 * d).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                header.layout(0, 0, header.measuredWidth, header.measuredHeight)
                fun field(name: String) = ExpansionEffectsHeader::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.get(header) as View
                fun top(view: View): Int {
                    var current = view; var y = 0
                    while (current !== header) { y += current.top; current = current.parent as View }
                    return y
                }
                val left = field("parameter") as EffectVisualView
                val right = field("illustration") as EffectVisualView
                assertEquals(top(left), top(right))
                assertEquals(left.height, right.height)
                listOf(left, right, field("chart") as EffectVisualView).forEach { graph ->
                    val paint = EffectVisualView::class.java.getDeclaredField("text").apply { isAccessible = true }.get(graph) as Paint
                    assertEquals(12 * ctx.resources.displayMetrics.scaledDensity, paint.textSize, .01f)
                    assertTrue(graph.height >= graph.minimumReadableHeight)
                    assertTrue(graph.width > 100 * d)
                }
                @Suppress("UNCHECKED_CAST")
                val readoutPaints = field("readouts").javaClass.getDeclaredField("paints")
                    .apply { isAccessible = true }.get(field("readouts")) as List<Paint>
                assertTrue(readoutPaints.all { it.textSize >= 12 * ctx.resources.displayMetrics.scaledDensity })
                assertTrue(header.height < 1600 * d)
                val bitmap = Bitmap.createBitmap(header.width, header.height, Bitmap.Config.ARGB_8888)
                header.draw(Canvas(bitmap))
                val output = File("build/reports/smart-adapt-visuals").apply { mkdirs() }
                File(output, "${if (night) "dark" else "light"}-$focus-320dp-150percent.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle(); header.close()
            }
        }
    }

    @Test fun allThreeIconStylesResolveToTheirOwnThemeableTwentyFourDpVector() {
        val ctx = context()
        val base = requireNotNull(EffectIconStyle.baseForEffect(kind.enableKey))
        assertEquals(R.drawable.ic_expansion_smart_adapt, base)
        val expected = mapOf(EffectIconStyle.ORIGINAL to R.drawable.ic_expansion_smart_adapt,
            EffectIconStyle.INSTRUMENT to R.drawable.ic_expansion_instrument_smart_adapt,
            EffectIconStyle.SOLID to R.drawable.ic_expansion_solid_smart_adapt)
        assertEquals(3, expected.values.distinct().size)
        expected.forEach { (style, id) ->
            EffectIconStyle.preferences(ctx).edit().putString(EffectIconStyle.KEY, style).commit()
            assertEquals(id, EffectIconStyle.resolve(ctx, base))
            val drawable = requireNotNull(AppCompatResources.getDrawable(ctx, id))
            assertEquals((24 * ctx.resources.displayMetrics.density).toInt(), drawable.intrinsicWidth)
            assertEquals(drawable.intrinsicWidth, drawable.intrinsicHeight)
        }
        EffectIconStyle.preferences(ctx).edit().remove(EffectIconStyle.KEY).commit()
    }

    @Test fun halfWidthFractionalAxisKeepsItsEntireMiddleTickInsideTheView() {
        val ctx = context()
        val d = ctx.resources.displayMetrics.density
        val values = mapOf<String, Any>(kind.enableKey to true,
            ExpansionEffectsParameters.control(kind, "target").key to 3000,
            ExpansionEffectsParameters.control(kind, "intensity").key to 0)
        val visual = SmartAdaptVisuals.static(values, ExpansionEffectsParameters.control(kind, "width").key).professional
        val graph = EffectVisualView(ctx, 12f).apply { setVisual(visual) }
        val width = (144 * d).toInt()
        val height = graph.minimumReadableHeight
        graph.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        graph.layout(0, 0, width, height)
        val drawn = mutableListOf<Triple<String, Float, Float>>()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = object : Canvas(bitmap) {
            override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
                val length = paint.measureText(text)
                val left = when (paint.textAlign) {
                    Paint.Align.RIGHT -> x - length
                    Paint.Align.CENTER -> x - length / 2
                    else -> x
                }
                drawn += Triple(text, left, left + length)
                super.drawText(text, x, y, paint)
            }
        }
        graph.draw(canvas)
        val middle = drawn.single { it.first == "0.50" }
        assertTrue("Middle tick must keep leading zero inside the view", middle.second >= d)
        assertTrue(middle.third <= width - d)
        bitmap.recycle()
    }
}
