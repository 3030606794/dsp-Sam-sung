package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Draws the actual preference-holder icons so a fixed black vector tint cannot pass. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    qualifiers = "zh-rCN-w411dp-h891dp-port-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EffectHeaderThemeTest {
    private var controller: ActivityController<HostActivity>? = null

    @After fun destroyHost() {
        controller?.pause()?.stop()?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun clarityAndToneIconsUseTheLightThemeControlColor() {
        launch()
        checkHeaderIcons("light")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-port-night-mdpi")
    fun clarityAndToneIconsRemainReadableInTheDarkTheme() {
        launch()
        checkHeaderIcons("dark")
    }

    @Test fun switchingToDarkThemeRebindsBothHeaderIconsWithTheNewColor() {
        launch()
        val light = checkHeaderIcons("before-dark")
        RuntimeEnvironment.setQualifiers("zh-rCN-w411dp-h891dp-port-night-mdpi")
        controller!!.recreate()
        layout()
        val dark = checkHeaderIcons("after-dark")
        assertNotEquals("Theme recreation must resolve the new control color", light, dark)
    }

    private fun launch() {
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout()
    }

    private fun layout() {
        shadowOf(Looper.getMainLooper()).idle()
        controller!!.get().findViewById<View>(android.R.id.content).apply {
            measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(891, View.MeasureSpec.EXACTLY))
            layout(0, 0, 411, 891)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Suppress("RestrictedApi")
    private fun checkHeaderIcons(suffix: String): Int {
        val activity = controller!!.get()
        var controlColor = Color.TRANSPARENT
        mapOf("clarity" to "v_clarity", "tone" to ToneController.ENABLED).forEach { (tag, key) ->
            val fragment = activity.supportFragmentManager.findFragmentByTag(tag) as PreferenceFragmentCompat
            val adapter = fragment.listView.adapter as PreferenceGroupAdapter
            val position = adapter.getPreferenceAdapterPosition(key)
            assertTrue("The $tag header is visible", position >= 0)
            val holder = requireNotNull(fragment.listView.findViewHolderForAdapterPosition(position))
            val icon = holder.itemView.findViewById<ImageView>(android.R.id.icon)
            assertTrue(icon.isShown)
            controlColor = MaterialColors.getColor(icon, androidx.appcompat.R.attr.colorControlNormal)
            val surface = MaterialColors.getColor(icon, com.google.android.material.R.attr.colorSurface)
            assertTrue("$tag icon is readable against the active surface",
                ColorUtils.calculateContrast(controlColor, surface) >= 3.0)
            val bitmap = Bitmap.createBitmap(icon.width, icon.height, Bitmap.Config.ARGB_8888)
            try {
                icon.draw(Canvas(bitmap))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val opaque = pixels.filter { Color.alpha(it) >= 250 }
                assertTrue("$tag icon must draw visible solid pixels", opaque.size > 10)
                assertEquals("$tag icon uses its holder's current theme color", controlColor,
                    opaque.groupingBy { it }.eachCount().maxBy { it.value }.key)
                val directory = File("build/reports/effect-header-theme").apply { mkdirs() }
                File(directory, "$tag-$suffix.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
        return controlColor
    }

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(FrameLayout(context).apply { id = CLARITY_HOST }, LinearLayout.LayoutParams(-1, -2))
                addView(FrameLayout(context).apply { id = TONE_HOST }, LinearLayout.LayoutParams(-1, -2))
            })
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .add(CLARITY_HOST, ViperEffectPreferenceFragment.newInstance("v_clarity"), "clarity")
                .add(TONE_HOST, TonePreferenceFragment(), "tone")
                .commitNow()
        }

        companion object {
            const val CLARITY_HOST = 0x6811
            const val TONE_HOST = 0x6812
        }
    }
}
