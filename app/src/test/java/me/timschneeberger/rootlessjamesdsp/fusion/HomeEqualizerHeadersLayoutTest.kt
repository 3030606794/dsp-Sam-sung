package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fragment.PreferenceGroupFragment
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import kotlin.math.abs

/** Shipping home cards with the actual preference fragments, native fonts and vector icons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    qualifiers = "zh-rCN-w320dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeEqualizerHeadersLayoutTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        stopKoin()
        RuntimeEnvironment.setFontScale(1f)
        listOf(Constants.PREF_APP, Constants.PREF_EQ, Constants.PREF_GEQ).forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        val preferences = Preferences(app)
        startKoin { modules(module { single { preferences.App() } }) }
    }

    @After fun cleanup() {
        RuntimeEnvironment.setFontScale(1f)
        stopKoin()
    }

    @Test fun compactPhoneShowsIndependentIconNameAndSwitchHeaders() = checkHeaders(320, 1f)

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-port-mdpi")
    fun regularPhoneShowsIndependentIconNameAndSwitchHeaders() = checkHeaders(411, 1f)

    @Test fun compactPhoneKeepsAllTextReadableAtOneHundredFiftyPercentFontSize() = checkHeaders(320, 1.5f)

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-port-mdpi")
    fun regularPhoneKeepsBothHeadersReadableAtOneHundredFiftyPercentFontSize() = checkHeaders(411, 1.5f)

    private fun checkHeaders(width: Int, fontScale: Float) {
        RuntimeEnvironment.setFontScale(fontScale)
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        try {
            val host = controller.get()
            val root = host.findViewById<View>(android.R.id.content)
            fun layout() {
                shadowOf(Looper.getMainLooper()).idle()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, 720)
                shadowOf(Looper.getMainLooper()).idle()
            }
            layout()
            // The RecyclerViews must finish their real binding and wrap-content pass.
            layout()
            val definitions = listOf(
                Triple(R.id.card_eq, R.string.key_eq_enable, "多模态均衡器"),
                Triple(R.id.card_geq, R.string.key_geq_enable, "参数均衡器"))
            definitions.forEach { (id, key, title) ->
                val container = host.findViewById<View>(id)
                assertTrue("Both equalizers have independent home cards", container.parent is MaterialCardView)
                assertTrue(container.isShown)
                val fragment = host.supportFragmentManager.findFragmentById(id) as PreferenceGroupFragment
                val preference = fragment.findPreference<SwitchPreferenceGroup>(app.getString(key))!!
                assertFalse("A fresh home card starts folded", preference.isExpanded)
                assertFalse("Rendering the header must not enable audio", preference.isChecked)
                assertEquals(1, fragment.listView.adapter!!.itemCount)
                val header = requireNotNull(fragment.listView.findViewHolderForAdapterPosition(0)).itemView
                checkHeader(header as ViewGroup, title, width, fontScale)
            }
        } finally { controller.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun checkHeader(header: ViewGroup, title: String, width: Int, fontScale: Float) {
        val label = header.findViewById<TextView>(android.R.id.title)
        val icon = header.findViewById<ImageView>(android.R.id.icon)
        val switch = header.findViewById<MaterialSwitch>(R.id.switchWidget)
        val iconBounds = bounds(header, icon)
        val labelBounds = bounds(header, label)
        val switchBounds = bounds(header, switch)
        assertEquals(title, label.text.toString())
        assertTrue(icon.isShown)
        assertNotNull(icon.drawable)
        assertTrue(switch.isShown)
        assertFalse(switch.isChecked)
        assertTrue("The left icon precedes the equalizer name", iconBounds.right <= labelBounds.left)
        assertTrue("The switch stays to the right of the equalizer name", labelBounds.right <= switchBounds.left)
        listOf(iconBounds, labelBounds, switchBounds).forEach { rect ->
            assertTrue("Header child $rect stays inside ${header.width}×${header.height}",
                rect.left >= 0 && rect.top >= 0 && rect.right <= header.width && rect.bottom <= header.height)
        }
        val textLayout = requireNotNull(label.layout)
        assertTrue("$title is permitted one or two lines", textLayout.lineCount in 1..2)
        val availableWidth = label.width - label.compoundPaddingLeft - label.compoundPaddingRight
        repeat(textLayout.lineCount) { line ->
            assertEquals("$title is not ellipsized", 0, textLayout.getEllipsisCount(line))
            assertTrue("Each native text line fits its allocated width", textLayout.getLineWidth(line) <= availableWidth + 0.5f)
        }
        assertTrue("The full text layout fits vertically", textLayout.height <=
            label.height - label.totalPaddingTop - label.totalPaddingBottom)

        val bitmap = Bitmap.createBitmap(header.width, header.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(MaterialColors.getColor(header, com.google.android.material.R.attr.colorSurface))
            header.draw(Canvas(bitmap))
            val directory = File("build/reports/home-equalizer-headers").apply { mkdirs() }
            File(directory, "${if (title == "多模态均衡器") "eq" else "peq"}-$width-${(fontScale * 100).toInt()}.png")
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val foreground = label.currentTextColor
            assertTrue("The name actually renders in the native bitmap",
                countPixels(bitmap, labelBounds) { colorNear(it, foreground) } > 8)
            val background = bitmap.getPixel(0, 0)
            assertTrue("The left filter icon actually renders in the native bitmap",
                countPixels(bitmap, iconBounds) { Color.alpha(it) > 200 && !colorNear(it, background) } > 8)
        } finally { bitmap.recycle() }
    }

    private fun bounds(parent: ViewGroup, view: View) = Rect(0, 0, view.width, view.height).also {
        parent.offsetDescendantRectToMyCoords(view, it)
    }

    private fun countPixels(bitmap: Bitmap, bounds: Rect, predicate: (Int) -> Boolean): Int {
        var count = 0
        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right)
            if (predicate(bitmap.getPixel(x, y))) count++
        return count
    }

    private fun colorNear(actual: Int, expected: Int): Boolean = Color.alpha(actual) >= 250 &&
        abs(Color.red(actual) - Color.red(expected)) <= 12 &&
        abs(Color.green(actual) - Color.green(expected)) <= 12 &&
        abs(Color.blue(actual) - Color.blue(expected)) <= 12

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            // Inflate the shipping home card hierarchy, but bind only the two equalizer
            // fragments so font/layout checks do not depend on 23 unrelated effects.
            setContentView(R.layout.fragment_dsp)
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .add(R.id.card_eq, PreferenceGroupFragment.newInstance(Constants.PREF_EQ, R.xml.dsp_equalizer_preferences))
                .add(R.id.card_geq, PreferenceGroupFragment.newInstance(Constants.PREF_GEQ, R.xml.dsp_graphiceq_preferences))
                .commitNow()
        }
    }
}
