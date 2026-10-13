package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** Uses the shipping home layout and native text/vector rendering at both phone widths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    qualifiers = "zh-rCN-w320dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeEqualizerTabsLayoutTest {
    @Test fun compactPhoneShowsBothEqualizerNamesAndIconsInsideTheExistingRow() = checkTabs(320)

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-port-mdpi")
    fun regularPhoneShowsBothEqualizerNamesAndIconsInsideTheExistingRow() = checkTabs(411)

    private fun checkTabs(width: Int) {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        try {
            val host = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            val root = host.findViewById<View>(android.R.id.content)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, 720)
            shadowOf(Looper.getMainLooper()).idle()
            val eq = host.findViewById<TextView>(R.id.eq_tab)
            val peq = host.findViewById<TextView>(R.id.peq_tab)
            val row = eq.parent as ViewGroup
            val power = host.findViewById<View>(R.id.eq_power)
            val bitmap = Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(MaterialColors.getColor(row, com.google.android.material.R.attr.colorSurface))
                row.draw(Canvas(bitmap))
                val directory = File("build/reports/home-equalizer-tabs").apply { mkdirs() }
                File(directory, "tabs-$width.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }

                assertEquals("The row retains its existing height", 56, row.height)
                assertEquals("多模态均衡器", eq.text.toString())
                assertEquals("参数均衡器", peq.text.toString())
                assertSame(row, peq.parent)
                assertTrue("The first tab ends before the second tab", eq.right <= peq.left)
                assertTrue("The second tab ends before the power button", peq.right <= power.left)
                listOf(eq, peq).forEach { tab -> checkTab(tab, row, bitmap) }
            } finally { bitmap.recycle() }
        } finally { controller.pause().stop().destroy() }
    }

    private fun checkTab(tab: TextView, row: ViewGroup, bitmap: Bitmap) {
        assertTrue(tab.isShown)
        assertTrue(tab.left >= row.paddingLeft && tab.right <= row.width - row.paddingRight)
        assertTrue(tab.top >= 0 && tab.bottom <= row.height)
        val textLayout = requireNotNull(tab.layout)
        assertEquals("${tab.text} stays on one line", 1, textLayout.lineCount)
        assertEquals("${tab.text} is not ellipsized", 0, textLayout.getEllipsisCount(0))
        val availableWidth = tab.width - tab.compoundPaddingLeft - tab.compoundPaddingRight
        assertTrue("${tab.text} fits the native font width without clipping",
            textLayout.getLineWidth(0) <= availableWidth)
        val icon = requireNotNull(tab.compoundDrawables[1])
        assertEquals(24, icon.bounds.width())
        assertEquals(24, icon.bounds.height())
        val textBounds = Rect().also { textLayout.getLineBounds(0, it) }
        val contentHeight = icon.bounds.height() + tab.compoundDrawablePadding + textBounds.height()
        assertTrue("The icon and text fit together inside ${tab.text}",
            contentHeight <= tab.height - tab.paddingTop - tab.paddingBottom)

        // Find rendered foreground pixels above and below the native text/icon gap.
        // This checks the compound icon actually draws, not merely that a drawable exists.
        val foreground = tab.currentTextColor
        val foregroundRows = (tab.top until tab.bottom).filter { y ->
            (tab.left until tab.right).count { x -> colorNear(bitmap.getPixel(x, y), foreground) } >= 2
        }
        assertTrue("${tab.text} draws both its icon and label", foregroundRows.isNotEmpty())
        val groups = mutableListOf<MutableList<Int>>()
        foregroundRows.forEach { y ->
            if (groups.isEmpty() || y > groups.last().last() + 1) groups.add(mutableListOf())
            groups.last().add(y)
        }
        assertTrue("${tab.text} has a separately visible icon above its label", groups.size >= 2)
        assertTrue("${tab.text} does not clip against the top edge", foregroundRows.first() > tab.top)
        assertTrue("${tab.text} does not clip against the bottom edge", foregroundRows.last() < tab.bottom - 1)
    }

    private fun colorNear(actual: Int, expected: Int): Boolean = Color.alpha(actual) >= 250 &&
        abs(Color.red(actual) - Color.red(expected)) <= 12 &&
        abs(Color.green(actual) - Color.green(expected)) <= 12 &&
        abs(Color.blue(actual) - Color.blue(expected)) <= 12

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(R.layout.fragment_dsp)
        }
    }
}
