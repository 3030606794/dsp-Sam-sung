package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.view.FloatingToggleButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    qualifiers = "w411dp-h891dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MainPowerInteractionTest {
    @Test fun clickingMainPowerChangesStateAndReturnsToTheSameFrameWithoutAScreenWave() = withPower { host, power ->
        val states = mutableListOf<Boolean>()
        power.setOnToggledListener(object : FloatingToggleButton.OnToggledListener {
            override fun onToggled(enabled: Boolean) { states += enabled }
        })
        val before = pixels(host.window.decorView)

        assertTrue(power.performClick())
        assertTrue(power.isToggled)
        assertTrue(power.isSelected)
        assertTrue(power.performClick())
        assertFalse(power.isToggled)
        assertFalse(power.isSelected)
        assertEquals(listOf(true, false), states)

        // A closing power wave starts at full-screen radius. Drawing immediately after
        // the second click catches that overlay without depending on animation timing.
        assertArrayEquals("Power clicks must not paint a transition over the page",
            before, pixels(host.window.decorView))
        assertNull(power.stateListAnimator)
        assertEquals(Color.TRANSPARENT, power.rippleColor)
        assertEquals(0f, power.elevation, 0f)
        assertEquals(0f, power.translationZ, 0f)
        assertEquals(0f, power.rotation, 0f)
        assertEquals(1f, power.scaleX, 0f)
        assertEquals(1f, power.scaleY, 0f)
        assertEquals(48, power.customSize)
    }

    @Test fun mainPowerStillLetsTheServiceAcceptOrRejectAClick() = withPower { host, power ->
        power.toggleOnClick = false
        var accepted = false
        var clicks = 0
        val states = mutableListOf<Boolean>()
        power.setOnToggledListener(object : FloatingToggleButton.OnToggledListener {
            override fun onToggled(enabled: Boolean) { states += enabled }
        })
        power.setOnToggleClickListener(object : FloatingToggleButton.OnToggleClickListener {
            override fun onClick() {
                clicks++
                if (accepted) power.isToggled = !power.isToggled
            }
        })
        val before = pixels(host.window.decorView)
        power.performClick()
        assertFalse(power.isToggled)
        assertTrue(states.isEmpty())
        assertArrayEquals(before, pixels(host.window.decorView))

        accepted = true
        power.performClick()
        assertTrue(power.isToggled)
        assertEquals(listOf(true), states)
        assertEquals(2, clicks)
        val colors = host.getColorStateList(R.color.selector_floating_toggle_foreground_tint)
        assertEquals(Color.rgb(46, 173, 85), colors.getColorForState(power.drawableState, 0))
    }

    private fun withPower(block: (HostActivity, FloatingToggleButton) -> Unit) {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        try {
            val host = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            val root = host.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(891, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 411, 891)
            val power = host.findViewById<FloatingToggleButton>(R.id.power_toggle)
            assertTrue("Exercise the actual attached homepage power button", power.isAttachedToWindow)
            block(host, power)
        } finally {
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun pixels(view: View): IntArray {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            return IntArray(view.width * view.height).also {
                bitmap.getPixels(it, 0, view.width, 0, 0, view.width, view.height)
            }
        } finally { bitmap.recycle() }
    }

    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            val content = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
            // Inflate the homepage button from its real XML tag, avoiding the page's
            // navigation host and audio-service dependencies in this interaction test.
            val power = resources.getLayout(R.layout.activity_dsp_main).use { parser ->
                while (parser.eventType != XmlPullParser.START_TAG ||
                    parser.name != FloatingToggleButton::class.java.name) {
                    check(parser.next() != XmlPullParser.END_DOCUMENT)
                }
                layoutInflater.createView(FloatingToggleButton::class.java.name, null,
                    Xml.asAttributeSet(parser)) as FloatingToggleButton
            }
            content.addView(power, FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            setContentView(content)
        }
    }
}
